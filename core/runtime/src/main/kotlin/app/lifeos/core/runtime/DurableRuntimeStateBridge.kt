package app.lifeos.core.runtime

import app.lifeos.core.model.task.LifeTask
import app.lifeos.core.model.task.TaskState
import app.lifeos.core.runtime.workers.CognitiveTaskExecutionResult
import app.lifeos.core.runtime.workers.DurableTaskExecutionObserver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class DurableRuntimeStateBridge(
    private val health: app.lifeos.core.runtime.health.RecoveryCoordinator? = null,
) : DurableTaskExecutionObserver {
    private val mutableState = MutableStateFlow(RuntimeState())
    val state: StateFlow<RuntimeState> = mutableState.asStateFlow()

    fun markStarting() {
        mutableState.update { it.copy(status = RuntimeStatus.STARTING) }
    }

    fun markRunning() {
        mutableState.update { it.copy(status = RuntimeStatus.RUNNING) }
    }

    fun markStopping() {
        mutableState.update { it.copy(status = RuntimeStatus.STOPPING) }
    }

    fun markStopped() {
        mutableState.update { it.copy(status = RuntimeStatus.STOPPED) }
    }

    fun markFailed(error: Throwable) {
        health?.graph?.record(app.lifeos.core.runtime.health.HealthNodes.Runtime,
            app.li…4889 tokens truncated…WorkResult): CognitiveTaskExecutionResult {
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

