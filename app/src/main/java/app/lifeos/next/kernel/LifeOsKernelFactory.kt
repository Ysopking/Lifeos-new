package app.lifeos.next.kernel

import android.content.Context
import app.lifeos.core.data.EncryptedPhotonStore
import app.lifeos.core.data.checkpoint.EncryptedCheckpointRepository
import app.lifeos.core.data.task.EncryptedTaskRepository
import app.lifeos.core.model.worker.WorkerId
import app.lifeos.core.runtime.DurableLifeOsRuntime
import app.lifeos.core.runtime.DurableRuntimeStateBridge
import app.lifeos.core.runtime.InfluenceExecutor
import app.lifeos.core.runtime.RuntimeSupervisor
import app.lifeos.core.runtime.StaticFieldRegistry
import app.lifeos.core.runtime.ThoughtMatrix
import app.lifeos.core.runtime.recovery.LeaseRecoveryLoop
import app.lifeos.core.runtime.recovery.LeaseRecoveryService
import app.lifeos.core.runtime.tasks.ConflatedTaskSchedulerSignal
import app.lifeos.core.runtime.tasks.DurableCognitivePipeline
import app.lifeos.core.runtime.tasks.DurableTaskEngine
import app.lifeos.core.runtime.tasks.TaskScheduler
import app.lifeos.core.runtime.tasks.TaskSchedulerLoop
import app.lifeos.core.runtime.workers.CognitiveTaskWorker
import app.lifeos.core.runtime.workers.ReportingCognitiveTaskDispatcher
import java.time.Duration
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
            leaseDuration = TASK_LEASE_DURATION,
            heartbeatInterval = HEARTBEAT_INTERVAL,
        )
        val durableStateBridge = DurableRuntimeStateBridge()
        val reportingDispatcher = ReportingCognitiveTaskDispatcher(
            worker = cognitiveWorker,
            observer = durableStateBridge,
        )
        val taskScheduler = TaskScheduler(
            tasks = taskRepository,
            workerId = durableWorkerId,
            dispatcher = reportingDispatcher,
            leaseDuration = TASK_LEASE_DURATION,
        )
        val schedulerLoop = TaskSchedulerLoop(
            scope = scope,
            scheduler = taskScheduler,
            wakeSource = schedulerSignal,
            rescanInterval = SCHEDULER_RESCAN_INTERVAL,
        )
        val leaseRecovery = LeaseRecoveryService(
            tasks = taskRepository,
            schedulerSignal = schedulerSignal,
        )
        val recoveryLoop = LeaseRecoveryLoop(
            scope = scope,
            recovery = leaseRecovery,
            interval = LEASE_RECOVERY_INTERVAL,
        )
        val durablePipeline = DurableCognitivePipeline(
            taskEngine = taskEngine,
            schedulerLoop = schedulerLoop,
            recoveryLoop = recoveryLoop,
        )
        val durableRuntime = DurableLifeOsRuntime(
            scope = scope,
            pipeline = durablePipeline,
            stateBridge = durableStateBridge,
        )
        val supervisor = RuntimeSupervisor(durableRuntime)
        val durableResources = DurableRuntimeResources(
            runtime = durableRuntime,
            taskRepository = taskRepository,
            checkpointRepository = checkpointRepository,
            leaseRecovery = leaseRecovery,
        )

        return LifeOsKernel(
            runtime = durableRuntime,
            matrix = matrix,
            photonStore = store,
            supervisor = supervisor,
            scope = scope,
            durableResources = durableResources,
        )
    }

    private companion object {
        val TASK_LEASE_DURATION: Duration = Duration.ofSeconds(30)
        val HEARTBEAT_INTERVAL: Duration = Duration.ofSeconds(10)
        val LEASE_RECOVERY_INTERVAL: Duration = Duration.ofSeconds(30)
        val SCHEDULER_RESCAN_INTERVAL: Duration = Duration.ofSeconds(5)
    }
}
