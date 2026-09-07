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
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
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
    private val now: () -> Instant = Instant::now,
) : ClaimedTaskDispatcher {

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

        return try {
            when (running.type) {
                TaskType.PROCESS_PHOTON -> processPhoton(running)
                else -> fail(
                    running,
                    photonId = running.inputPhotonIds.singleOrNull(),
                    failure = RuntimeFailure(
                        category = RuntimeFailureCategory.INVARIANT,
                        source = "cognitive-worker",
                        message = "Unsupported task type: ${running.type}",
                        photonId = running.inputPhotonIds.singleOrNull(),
                    ),
                )
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                tasks.transition(
                    id = running.id,
                    expected = TaskState.RUNNING,
                    next = TaskState.INTERRUPTED,
                    at = now(),
                )
            }
            throw cancelled
        } catch (error: Exception) {
            fail(
                running,
                photonId = running.inputPhotonIds.singleOrNull(),
                failure = RuntimeFailure(
                    category = RuntimeFailureCategory.UNKNOWN,
                    source = "cognitive-worker",
                    message = error.message ?: error::class.simpleName ?: "Task execution failed",
                    photonId = running.inputPhotonIds.singleOrNull(),
                ),
            )
        }
    }

    private suspend fun processPhoton(task: LifeTask): CognitiveTaskExecutionResult {
        val photonId = task.inputPhotonIds.singleOrNull()
            ?: return fail(
                task,
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
            return fail(
                task,
                photonId = photonId,
                failure = RuntimeFailure(
                    category = RuntimeFailureCategory.STORAGE,
                    source = "photon-repository",
                    message = error.message ?: "Photon lookup failed",
                    photonId = photonId,
                ),
            )
        } ?: return fail(
            task,
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
                return supersede(task, photonId)
            }
            if (photon.revision < expectedRevision) {
                return fail(
                    task,
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
        if (execution.failures.isNotEmpty()) {
            val failedTask = transitionFinal(task, TaskState.FAILED)
            return CognitiveTaskExecutionResult(
                taskId = failedTask.id,
                photonId = photon.id,
                finalState = failedTask.state,
                influences = execution.influences,
                failures = execution.failures,
            )
        }

        val completedTask = transitionFinal(task, TaskState.COMPLETED)
        return CognitiveTaskExecutionResult(
            taskId = completedTask.id,
            photonId = photon.id,
            finalState = completedTask.state,
            influences = execution.influences,
            failures = emptyList(),
        )
    }

    private suspend fun supersede(
        task: LifeTask,
        photonId: PhotonId,
    ): CognitiveTaskExecutionResult {
        val supersededTask = transitionFinal(task, TaskState.SUPERSEDED)
        return CognitiveTaskExecutionResult(
            taskId = supersededTask.id,
            photonId = photonId,
            finalState = supersededTask.state,
            influences = emptyList(),
            failures = emptyList(),
        )
    }

    private suspend fun fail(
        task: LifeTask,
        photonId: PhotonId?,
        failure: RuntimeFailure,
    ): CognitiveTaskExecutionResult {
        val failedTask = transitionFinal(task, TaskState.FAILED)
        return CognitiveTaskExecutionResult(
            taskId = failedTask.id,
            photonId = photonId,
            finalState = failedTask.state,
            influences = emptyList(),
            failures = listOf(failure),
        )
    }

    private suspend fun transitionFinal(task: LifeTask, state: TaskState): LifeTask =
        tasks.transition(
            id = task.id,
            expected = TaskState.RUNNING,
            next = state,
            at = now(),
        ) ?: error("Running task could not transition to $state: ${task.id.value}")
}
