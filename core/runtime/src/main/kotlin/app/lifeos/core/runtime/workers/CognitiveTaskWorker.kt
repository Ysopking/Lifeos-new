package app.lifeos.core.runtime.workers

import app.lifeos.core.model.FieldInfluence
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.model.checkpoint.CheckpointRepository
import app.lifeos.core.model.task.LifeTask
import app.lifeos.core.model.task.TaskId
import app.lifeos.core.model.task.TaskRepository
import app.lifeos.core.model.task.TaskState
import app.lifeos.core.model.task.TaskType
import app.lifeos.core.model.worker.WorkerId
import app.lifeos.core.runtime.FieldRegistry
import app.lifeos.core.runtime.InfluenceExecutor
import app.lifeos.core.runtime.RuntimeFailure
import app.lifeos.core.runtime.RuntimeFailureCategory
import app.lifeos.core.runtime.checkpoints.FieldCheckpointManager
import app.lifeos.core.runtime.checkpoints.FieldCheckpointStorageException
import app.lifeos.core.runtime.field.FieldShadowExecution
import app.lifeos.core.runtime.field.FieldShadowProcessor
import app.lifeos.core.runtime.tasks.ClaimedTaskDispatcher
import app.lifeos.core.runtime.tasks.RetryPolicy
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CognitiveTaskExecutionResult(
    val taskId: TaskId,
    val photonId: PhotonId?,
    val finalState: TaskState,
    val influences: List<FieldInfluence>,
    val failures: List<RuntimeFailure>,
    val fieldShadow: FieldShadowExecution? = null,
    val recordedAt: Instant? = null,
)

class CognitiveTaskWorker(
    private val workerId: WorkerId,
    private val tasks: TaskRepository,
    private val photons: PhotonRepository,
    private val fields: FieldRegistry,
    private val executor: InfluenceExecutor,
    checkpoints: CheckpointRepository? = null,
    private val fieldShadowProcessor: FieldShadowProcessor? = null,
    private val retryPolicy: RetryPolicy = RetryPolicy(),
    private val leaseDuration: Duration = Duration.ofSeconds(30),
    private val heartbeatInterval: Duration = Duration.ofSeconds(10),
    private val now: () -> Instant = Instant::now,
) : ClaimedTaskDispatcher {
    private val checkpointManager = FieldCheckpointManager(checkpoints)

    init {
        require(!leaseDuration.isZero && !leaseDuration.isNegative) {
            "Worker lease duration must be positive"
        }
        require(!heartbeatInterval.isZero && !heartbeatInterval.isNegative) {
            "Worker heartbeat interval must be positive"
        }
        require(heartbeatInterval < leaseDuration) {
            "Worker heartbeat interval must be shorter than lease duration"
        }
    }

    override suspend fun dispatch(task: LifeTask) {
        execute(task)
    }

    suspend fun execute(task: LifeTask): CognitiveTaskExecutionResult {
        require(task.state == TaskState.CLAIMED) { "Worker accepts only CLAIMED tasks" }
        require(task.claimedBy == workerId) { "Task is claimed by a different worker" }
        val startedAt = now()
        require(task.leaseExpiresAt?.isAfter(startedAt) == true) { "Task lease has expired" }

        val running = tasks.startExecution(
            id = task.id,
            workerId = workerId,
            startedAt = startedAt,
        ) ?: error(
            "Claimed task could not start execution because ownership, lease, state, or attempt budget changed: ${task.id.value}"
        )

        val activeTask = renewLeaseOrThrow(running, startedAt)

        return try {
            val work = when (activeTask.type) {
                TaskType.PROCESS_PHOTON,
                TaskType.REPROCESS_PHOTON -> withLeaseHeartbeat(activeTask) {
                    processPhoton(activeTask)
                }
                else -> failureWork(
                    photonId = activeTask.inputPhotonIds.singleOrNull(),
                    failure = RuntimeFailure(
                        category = RuntimeFailureCategory.INVARIANT,
                        source = "cognitive-worker",
                        message = "Unsupported task type: ${activeTask.type}",
                        recoverable = false,
                        photonId = activeTask.inputPhotonIds.singleOrNull(),
                    ),
                )
            }
            finish(activeTask, work)
        } catch (leaseLost: LeaseOwnershipLostException) {
            throw leaseLost
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                tasks.interruptExecution(
                    id = activeTask.id,
                    workerId = workerId,
                    interruptedAt = now(),
                )
            }
            throw cancelled
        } catch (error: Exception) {
            finish(
                activeTask,
                failureWork(
                    photonId = activeTask.inputPhotonIds.singleOrNull(),
                    failure = RuntimeFailure(
                        category = RuntimeFailureCategory.UNKNOWN,
                        source = "cognitive-worker",
                        message = error.message ?: error::class.simpleName ?: "Task execution failed",
                        photonId = activeTask.inputPhotonIds.singleOrNull(),
                    ),
                ),
            )
        }
    }

    private suspend fun processPhoton(task: LifeTask): WorkResult {
        val photonId = task.inputPhotonIds.singleOrNull()
            ?: return failureWork(
                photonId = null,
                failure = RuntimeFailure(
                    category = RuntimeFailureCategory.INVARIANT,
                    source = "cognitive-worker",
                    message = "Photon-processing task requires exactly one input photon",
                    recoverable = false,
                ),
            )

        val photon = try {
            photons.load(photonId)
        } catch (error: Exception) {
            return failureWork(
                photonId = photonId,
                failure = RuntimeFailure(
                    category = RuntimeFailureCategory.STORAGE,
                    source = "photon-repository",
                    message = error.message ?: "Photon lookup failed",
                    photonId = photonId,
                ),
            )
        } ?: return failureWork(
            photonId = photonId,
            failure = RuntimeFailure(
                category = RuntimeFailureCategory.STORAGE,
                source = "photon-repository",
                message = "Input photon not found",
                recoverable = false,
                photonId = photonId,
            ),
        )

        val expectedRevision = task.inputPhotonRevisions[photonId]
        if (expectedRevision != null) {
            if (photon.revision > expectedRevision) {
                return WorkResult(
                    photonId = photonId,
                    finalState = TaskState.SUPERSEDED,
                )
            }
            if (photon.revision < expectedRevision) {
                return failureWork(
                    photonId = photonId,
                    failure = RuntimeFailure(
                        category = RuntimeFailureCategory.STORAGE,
                        source = "photon-repository",
                        message = "Expected photon revision $expectedRevision but found older revision ${photon.revision}",
                        recoverable = false,
                        photonId = photonId,
                    ),
                )
            }
        }

        val activeFields = fields.activeFields()
        var checkpoint = try {
            checkpointManager.load(task.id, activeFields)
        } catch (error: FieldCheckpointStorageException) {
            return checkpointFailure(photonId, error)
        }

        val execution = try {
            executor.execute(
                photon = photon,
                fields = activeFields,
                completedFieldIndexes = checkpoint.completedFieldIndexes,
                onFieldSuccess = { fieldIndex ->
                    val checkpointTime = now()
                    renewLeaseOrThrow(task, checkpointTime)
                    checkpoint = checkpointManager.recordSuccess(
                        taskId = task.id,
                        state = checkpoint,
                        fieldIndex = fieldIndex,
                        createdAt = checkpointTime,
                    )
                },
            )
        } catch (leaseLost: LeaseOwnershipLostException) {
            throw leaseLost
        } catch (error: FieldCheckpointStorageException) {
            return checkpointFailure(photonId, error)
        }

        val fieldShadow = processFieldShadow(photon)
        return if (execution.failures.isNotEmpty()) {
            WorkResult(
                photonId = photon.id,
                finalState = TaskState.FAILED,
                influences = execution.influences,
                failures = execution.failures,
                fieldShadow = fieldShadow,
            )
        } else {
            WorkResult(
                photonId = photon.id,
                finalState = TaskState.COMPLETED,
                influences = execution.influences,
                fieldShadow = fieldShadow,
            )
        }
    }

    private suspend fun processFieldShadow(photon: Photon): FieldShadowExecution? {
        val processor = fieldShadowProcessor ?: return null
        return try {
            processor.process(photon)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            FieldShadowExecution.failed(
                message = error.message ?: error::class.simpleName ?: "universal-field-shadow-failed",
            )
        }
    }

    private fun checkpointFailure(
        photonId: PhotonId,
        error: FieldCheckpointStorageException,
    ) = failureWork(
        photonId = photonId,
        failure = RuntimeFailure(
            category = RuntimeFailureCategory.STORAGE,
            source = "checkpoint-repository",
            message = error.message ?: "Checkpoint persistence failed",
            photonId = photonId,
        ),
    )

    private suspend fun finish(task: LifeTask, work: WorkResult): CognitiveTaskExecutionResult {
        val finalTask = if (work.finalState == TaskState.FAILED) {
            scheduleRetryOrFinish(task, work.failures)
        } else {
            finishOwnedExecution(task, work.finalState)
        }

        if (finalTask.state == TaskState.COMPLETED || finalTask.state == TaskState.SUPERSEDED) {
            checkpointManager.clearBestEffort(task.id)
        }

        return CognitiveTaskExecutionResult(
            taskId = finalTask.id,
            photonId = work.photonId,
            finalState = finalTask.state,
            influences = work.influences,
            failures = work.failures,
            fieldShadow = work.fieldShadow,
            recordedAt = finalTask.updatedAt,
        )
    }

    private suspend fun scheduleRetryOrFinish(
        task: LifeTask,
        failures: List<RuntimeFailure>,
    ): LifeTask {
        val scheduledAt = now()
        val retry = retryPolicy.nextRetry(
            task = task,
            failures = failures,
            scheduledAt = scheduledAt,
        ) ?: return finishOwnedExecution(task, TaskState.FAILED)

        return tasks.scheduleRetry(
            id = task.id,
            workerId = workerId,
            retryAt = retry.retryAt,
            scheduledAt = scheduledAt,
        ) ?: throw LeaseOwnershipLostException(
            "Task execution ownership lost before retry scheduling: ${task.id.value}"
        )
    }

    private fun failureWork(
        photonId: PhotonId?,
        failure: RuntimeFailure,
    ) = WorkResult(
        photonId = photonId,
        finalState = TaskState.FAILED,
        failures = listOf(failure),
    )

    private suspend fun renewLeaseOrThrow(task: LifeTask, renewedAt: Instant): LifeTask = try {
        tasks.renewLease(
            id = task.id,
            workerId = workerId,
            renewedAt = renewedAt,
            leaseUntil = renewedAt.plus(leaseDuration),
        ) ?: throw LeaseOwnershipLostException("Task lease ownership lost: ${task.id.value}")
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (leaseLost: LeaseOwnershipLostException) {
        throw leaseLost
    } catch (error: Exception) {
        throw LeaseOwnershipLostException(
            message = "Task lease renewal failed: ${task.id.value}",
            cause = error,
        )
    }

    private suspend fun <T> withLeaseHeartbeat(
        task: LifeTask,
        block: suspend () -> T,
    ): T = try {
        coroutineScope {
            val heartbeat = launch {
                while (isActive) {
                    delay(heartbeatInterval.toMillis())
                    val renewedAt = now()
                    try {
                        val renewed = tasks.renewLease(
                            id = task.id,
                            workerId = workerId,
                            renewedAt = renewedAt,
                            leaseUntil = renewedAt.plus(leaseDuration),
                        )
                        if (renewed == null) {
                            this@coroutineScope.cancel(
                                LeaseOwnershipLostCancellation(
                                    "Task lease ownership lost: ${task.id.value}"
                                )
                            )
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        this@coroutineScope.cancel(
                            LeaseOwnershipLostCancellation(
                                message = "Task lease heartbeat failed: ${task.id.value}",
                                cause = error,
                            )
                        )
                    }
                }
            }

            try {
                block()
            } finally {
                heartbeat.cancelAndJoin()
            }
        }
    } catch (leaseLost: LeaseOwnershipLostCancellation) {
        throw LeaseOwnershipLostException(
            message = leaseLost.message ?: "Task lease ownership lost: ${task.id.value}",
            cause = leaseLost.cause,
        )
    }

    private suspend fun finishOwnedExecution(task: LifeTask, state: TaskState): LifeTask {
        val finishedAt = now()
        return tasks.finishExecution(
            id = task.id,
            workerId = workerId,
            finalState = state,
            finishedAt = finishedAt,
        ) ?: throw LeaseOwnershipLostException(
            "Task execution ownership lost before $state: ${task.id.value}"
        )
    }

    private data class WorkResult(
        val photonId: PhotonId?,
        val finalState: TaskState,
        val influences: List<FieldInfluence> = emptyList(),
        val failures: List<RuntimeFailure> = emptyList(),
        val fieldShadow: FieldShadowExecution? = null,
    )

    private class LeaseOwnershipLostException(
        message: String,
        cause: Throwable? = null,
    ) : Exception(message, cause)

    private class LeaseOwnershipLostCancellation(
        message: String,
        cause: Throwable? = null,
    ) : CancellationException(message) {
        init {
            if (cause != null) initCause(cause)
        }
    }
}
