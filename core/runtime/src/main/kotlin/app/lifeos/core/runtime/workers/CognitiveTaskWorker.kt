package app.lifeos.core.runtime.workers

import app.lifeos.core.model.FieldInfluence
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonRepository
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
import app.lifeos.core.runtime.tasks.ClaimedTaskDispatcher
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
)

class CognitiveTaskWorker(
    private val workerId: WorkerId,
    private val tasks: TaskRepository,
    private val photons: PhotonRepository,
    private val fields: FieldRegistry,
    private val executor: InfluenceExecutor,
    private val leaseDuration: Duration = Duration.ofSeconds(30),
    private val heartbeatInterval: Duration = Duration.ofSeconds(10),
    private val now: () -> Instant = Instant::now,
) : ClaimedTaskDispatcher {
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

        val running = tasks.transition(
            id = task.id,
            expected = TaskState.CLAIMED,
            next = TaskState.RUNNING,
            at = startedAt,
        ) ?: error("Claimed task could not transition to RUNNING: ${task.id.value}")

        val activeTask = renewLeaseOrThrow(running, startedAt)

        return try {
            val work = when (activeTask.type) {
                TaskType.PROCESS_PHOTON -> withLeaseHeartbeat(activeTask) {
                    processPhoton(activeTask)
                }
                else -> failureWork(
                    photonId = activeTask.inputPhotonIds.singleOrNull(),
                    failure = RuntimeFailure(
                        category = RuntimeFailureCategory.INVARIANT,
                        source = "cognitive-worker",
                        message = "Unsupported task type: ${activeTask.type}",
                        photonId = activeTask.inputPhotonIds.singleOrNull(),
                    ),
                )
            }
            finish(activeTask, work)
        } catch (leaseLost: LeaseOwnershipLostException) {
            throw leaseLost
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                tasks.transition(
                    id = activeTask.id,
                    expected = TaskState.RUNNING,
                    next = TaskState.INTERRUPTED,
                    at = now(),
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
                    message = "PROCESS_PHOTON requires exactly one input photon",
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
                        photonId = photonId,
                    ),
                )
            }
        }

        val execution = executor.execute(photon, fields.activeFields())
        return if (execution.failures.isNotEmpty()) {
            WorkResult(
                photonId = photon.id,
                finalState = TaskState.FAILED,
                influences = execution.influences,
                failures = execution.failures,
            )
        } else {
            WorkResult(
                photonId = photon.id,
                finalState = TaskState.COMPLETED,
                influences = execution.influences,
            )
        }
    }

    private suspend fun finish(task: LifeTask, work: WorkResult): CognitiveTaskExecutionResult {
        val finalTask = transitionFinal(task, work.finalState)
        return CognitiveTaskExecutionResult(
            taskId = finalTask.id,
            photonId = work.photonId,
            finalState = finalTask.state,
            influences = work.influences,
            failures = work.failures,
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
    } catch (error: Exception) {
        throw LeaseOwnershipLostException(
            message = "Task lease renewal failed: ${task.id.value}",
            cause = error,
        )
    }

    private suspend fun <T> withLeaseHeartbeat(
        task: LifeTask,
        block: suspend () -> T,
    ): T = coroutineScope {
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
                            LeaseOwnershipLostException("Task lease ownership lost: ${task.id.value}")
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    this@coroutineScope.cancel(
                        LeaseOwnershipLostException(
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

    private suspend fun transitionFinal(task: LifeTask, state: TaskState): LifeTask =
        tasks.transition(
            id = task.id,
            expected = TaskState.RUNNING,
            next = state,
            at = now(),
        ) ?: error("Running task could not transition to $state: ${task.id.value}")

    private data class WorkResult(
        val photonId: PhotonId?,
        val finalState: TaskState,
        val influences: List<FieldInfluence> = emptyList(),
        val failures: List<RuntimeFailure> = emptyList(),
    )

    private class LeaseOwnershipLostException(
        message: String,
        cause: Throwable? = null,
    ) : CancellationException(message) {
        init {
            if (cause != null) initCause(cause)
        }
    }
}
