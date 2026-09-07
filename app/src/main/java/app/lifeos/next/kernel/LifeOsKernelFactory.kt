package app.lifeos.next.kernel

import android.content.Context
import app.lifeos.core.data.EncryptedPhotonStore
import app.lifeos.core.data.checkpoint.EncryptedCheckpointRepository
import app.lifeos.core.data.task.EncryptedTaskRepository
import app.lifeos.core.model.worker.WorkerId
import app.lifeos.core.runtime.CognitiveRuntime
import app.lifeos.core.runtime.InfluenceExecutor
import app.lifeos.core.runtime.RuntimeSupervisor
import app.lifeos.core.runtime.StaticFieldRegistry
import app.lifeos.core.runtime.ThoughtMatrix
import app.lifeos.core.runtime.tasks.ConflatedTaskSchedulerSignal
import app.lifeos.core.runtime.tasks.DurableCognitivePipeline
import app.lifeos.core.runtime.tasks.DurableTaskEngine
import app.lifeos.core.runtime.tasks.TaskScheduler
import app.lifeos.core.runtime.tasks.TaskSchedulerLoop
import app.lifeos.core.runtime.workers.CognitiveTaskWorker
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Single composition point for the current process-level LIFEOS runtime graph. */
class LifeOsKernelFactory(
    private val context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    fun create(): LifeOsKernel {
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val appContext = context.applicationContext
        val store = EncryptedPhotonStore(appContext)
        val matrix = ThoughtMatrix()
        val registry = StaticFieldRegistry(listOf(matrix))
        val executor = InfluenceExecutor()
        val runtime = CognitiveRuntime(
            scope = scope,
            fieldRegistry = registry,
            influenceExecutor = executor,
        )
        val supervisor = RuntimeSupervisor(runtime)

        val taskRepository = EncryptedTaskRepository(appContext)
        val checkpointRepository = EncryptedCheckpointRepository(appContext)
        val schedulerSignal = ConflatedTaskSchedulerSignal()
        val taskEngine = DurableTaskEngine(taskRepository, schedulerSignal)
        val durableWorkerId = WorkerId("cognitive-worker-0")
        val cognitiveWorker = CognitiveTaskWorker(
            workerId = durableWorkerId,
            tasks = taskRepository,
            photons = store,
            fields = registry,
            executor = executor,
        )
        val taskScheduler = TaskScheduler(
            tasks = taskRepository,
            workerId = durableWorkerId,
            dispatcher = cognitiveWorker,
        )
        val schedulerLoop = TaskSchedulerLoop(
            scope = scope,
            scheduler = taskScheduler,
            wakeSource = schedulerSignal,
        )
        val durableResources = DurableRuntimeResources(
            pipeline = DurableCognitivePipeline(taskEngine, schedulerLoop),
            taskRepository = taskRepository,
            checkpointRepository = checkpointRepository,
        )

        return LifeOsKernel(
            runtime = runtime,
            matrix = matrix,
            photonStore = store,
            supervisor = supervisor,
            scope = scope,
            durableResources = durableResources,
        )
    }
}
