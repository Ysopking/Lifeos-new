package app.lifeos.core.runtime.workers

import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.model.checkpoint.CheckpointRepository
import app.lifeos.core.model.task.TaskRepository
import app.lifeos.core.model.worker.WorkerId
import app.lifeos.core.runtime.FieldRegistry
import app.lifeos.core.runtime.InfluenceExecutor
import app.lifeos.core.runtime.field.FieldCutoverRuntimeRouter
import app.lifeos.core.runtime.field.FieldShadowAdmissionPolicy
import app.lifeos.core.runtime.field.FieldShadowProcessor
import app.lifeos.core.runtime.tasks.RetryPolicy
import java.time.Duration
import java.time.Instant

data class CognitiveWorkerConfig(
    val leaseDuration: Duration = Duration.ofSeconds(30),
    val heartbeatInterval: Duration = Duration.ofSeconds(10),
) {
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
}

/**
 * Central construction point for cognitive workers. This keeps worker identity,
 * retry policy, checkpointing and lease semantics consistent when more workers
 * are added later.
 */
class CognitiveWorkerFactory(
    private val tasks: TaskRepository,
    private val photons: PhotonRepository,
    private val fields: FieldRegistry,
    private val executor: InfluenceExecutor,
    private val checkpoints: CheckpointRepository? = null,
    private val fieldShadowProcessor: FieldShadowProcessor? = null,
    private val fieldShadowAdmission: FieldShadowAdmissionPolicy = FieldShadowAdmissionPolicy.ALL,
    private val fieldCutoverRouter: FieldCutoverRuntimeRouter? = null,
    private val retryPolicy: RetryPolicy = RetryPolicy(),
    private val config: CognitiveWorkerConfig = CognitiveWorkerConfig(),
    private val now: () -> Instant = Instant::now,
) {
    fun create(workerId: WorkerId): CognitiveTaskWorker = CognitiveTaskWorker(
        workerId = workerId,
        tasks = tasks,
        photons = photons,
        fields = fields,
        executor = executor,
        checkpoints = checkpoints,
        fieldShadowProcessor = fieldShadowProcessor,
        fieldShadowAdmission = fieldShadowAdmission,
        fieldCutoverRouter = fieldCutoverRouter,
        retryPolicy = retryPolicy,
        leaseDuration = config.leaseDuration,
        heartbeatInterval = config.heartbeatInterval,
        now = now,
    )
}
