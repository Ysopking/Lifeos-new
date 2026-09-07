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
import app.lifeos.core.runtime.boot.BootCoordinator
import app.lifeos.core.runtime.boot.CapabilityWarmup
import app.lifeos.core.runtime.boot.CapabilityWarmupResult
import app.lifeos.core.runtime.boot.CompositeBootDeltaDetector
import app.lifeos.core.runtime.boot.CompositeStoreVerifier
import app.lifeos.core.runtime.boot.DefaultBootValidator
import app.lifeos.core.runtime.boot.ModuleRehydrator
import app.lifeos.core.runtime.boot.ModuleRestoreSummary
import app.lifeos.core.runtime.boot.PhotonRehydrator
import app.lifeos.core.runtime.boot.RehydratedRuntimeState
import app.lifeos.core.runtime.boot.RuntimeBootstrapper
import app.lifeos.core.runtime.boot.StateRehydrator
import app.lifeos.core.runtime.boot.StoreProbe
import app.lifeos.core.runtime.boot.StoreState
import app.lifeos.core.runtime.boot.StoreStatus
import app.lifeos.core.runtime.boot.ThoughtMatrixWarmup
import app.lifeos.core.runtime.boot.ThoughtMatrixWarmupResult
import app.lifeos.core.runtime.cognition.CognitiveScheduler
import app.lifeos.core.runtime.cognition.CompositeDurableTaskExecutionObserver
import app.lifeos.core.runtime.cognition.ContinuousCognitionEngine
import app.lifeos.core.runtime.cognition.DurableCognitionDispatcher
import app.lifeos.core.runtime.cognition.DurableCognitiveTriggerSink
import app.lifeos.core.runtime.cognition.InMemoryCognitiveEventJournal
import app.lifeos.core.runtime.cognition.InMemoryCognitiveOutcomeJournal
import app.lifeos.core.runtime.cognition.InMemoryCognitiveTriggerSink
import app.lifeos.core.runtime.cognition.InMemoryPhotonTransactionJournal
import app.lifeos.core.runtime.cognition.OutcomeTriggerObserver
import app.lifeos.core.runtime.cognition.PhotonTransactionObserver
import app.lifeos.core.runtime.recovery.LeaseRecoveryLoop
import app.lifeos.core.runtime.recovery.LeaseRecoveryService
import app.lifeos.core.runtime.tasks.ConflatedTaskSchedulerSignal
import app.lifeos.core.runtime.tasks.DurableCognitivePipeline
import app.lifeos.core.runtime.tasks.DurableTaskEngine
import app.lifeos.core.runtime.tasks.TaskScheduler
import app.lifeos.core.runtime.tasks.TaskSchedulerLoop
import app.lifeos.core.runtime.workers.CognitiveWorkerConfig
import app.lifeos.core.runtime.workers.CognitiveWorkerFactory
import app.lifeos.core.runtime.workers.ReportingCognitiveTaskDispatcher
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Single composition point for the process-level LIFEOS runtime and boot graph. */
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

        val cognitiveEventJournal = InMemoryCognitiveEventJournal()
        val cognitiveScheduler = CognitiveScheduler()
        val continuousCognition = ContinuousCognitionEngine(
            journal = cognitiveEventJournal,
            scheduler = cognitiveScheduler,
            durableDispatcher = DurableCognitionDispatcher(taskEngine),
        )
        val photonTransactions = InMemoryPhotonTransactionJournal()
        val cognitiveOutcomes = InMemoryCognitiveOutcomeJournal()
        val cognitiveTriggers = DurableCognitiveTriggerSink(
            journal = InMemoryCognitiveTriggerSink(),
            photons = store,
            taskEngine = taskEngine,
        )

        val durableWorkerId = WorkerId("cognitive-worker-0")
        val workerFactory = CognitiveWorkerFactory(
            tasks = taskRepository,
            photons = store,
            fields = registry,
            executor = executor,
            checkpoints = checkpointRepository,
            config = CognitiveWorkerConfig(
                leaseDuration = TASK_LEASE_DURATION,
                heartbeatInterval = HEARTBEAT_INTERVAL,
            ),
        )
        val cognitiveWorker = workerFactory.create(durableWorkerId)
        val durableStateBridge = DurableRuntimeStateBridge()
        val reportingDispatcher = ReportingCognitiveTaskDispatcher(
            worker = cognitiveWorker,
            observer = CompositeDurableTaskExecutionObserver(
                listOf(
                    durableStateBridge,
                    PhotonTransactionObserver(photonTransactions),
                    OutcomeTriggerObserver(
                        outcomes = cognitiveOutcomes,
                        triggers = cognitiveTriggers,
                    ),
                )
            ),
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

        val bootCoordinator = BootCoordinator(
            runtimeBootstrapper = object : RuntimeBootstrapper {
                override suspend fun bootstrap() = Unit
            },
            storeVerifier = CompositeStoreVerifier(
                probes = listOf(
                    object : StoreProbe {
                        override val storeId: String = "photon-store"

                        override suspend fun probe(): StoreStatus {
                            val report = store.loadReport()
                            return StoreStatus(
                                storeId = storeId,
                                state = if (report.unreadableFiles.isEmpty()) {
                                    StoreState.HEALTHY
                                } else {
                                    StoreState.PARTIALLY_RECOVERABLE
                                },
                                message = if (report.unreadableFiles.isEmpty()) {
                                    null
                                } else {
                                    "unreadable:${report.unreadableFiles.size}"
                                },
                            )
                        }
                    },
                    object : StoreProbe {
                        override val storeId: String = "task-store"

                        override suspend fun probe(): StoreStatus {
                            val now = Instant.now()
                            taskRepository.listRunnable(now, limit = 1)
                            taskRepository.listExpiredLeases(now, limit = 1)
                            return StoreStatus(storeId, StoreState.HEALTHY)
                        }
                    },
                )
            ),
            stateRehydrator = object : StateRehydrator {
                override suspend fun rehydrate(): RehydratedRuntimeState {
                    recoverExpiredLeases(leaseRecovery)
                    return RehydratedRuntimeState()
                }
            },
            photonRehydrator = PhotonRehydrator(store),
            moduleRehydrator = object : ModuleRehydrator {
                override suspend fun rehydrate() = ModuleRestoreSummary(
                    restored = registry.activeFields().size,
                )
            },
            thoughtMatrixWarmup = object : ThoughtMatrixWarmup {
                override suspend fun warmup() = ThoughtMatrixWarmupResult()
            },
            capabilityWarmup = object : CapabilityWarmup {
                override suspend fun warmup() = CapabilityWarmupResult()
            },
            deltaDetector = CompositeBootDeltaDetector(emptyList()),
            validator = DefaultBootValidator(),
        )

        return LifeOsKernel(
            runtime = durableRuntime,
            matrix = matrix,
            photonStore = store,
            supervisor = supervisor,
            scope = scope,
            bootCoordinator = bootCoordinator,
            continuousCognition = continuousCognition,
            photonTransactions = photonTransactions,
            cognitiveOutcomes = cognitiveOutcomes,
            cognitiveTriggers = cognitiveTriggers,
        )
    }

    private suspend fun recoverExpiredLeases(recovery: LeaseRecoveryService) {
        while (true) {
            val result = recovery.recoverExpired(LEASE_RECOVERY_BATCH_SIZE)
            if (result.scanned < LEASE_RECOVERY_BATCH_SIZE || result.recovered == 0) return
        }
    }

    private companion object {
        const val LEASE_RECOVERY_BATCH_SIZE = 100
        val TASK_LEASE_DURATION: Duration = Duration.ofSeconds(30)
        val HEARTBEAT_INTERVAL: Duration = Duration.ofSeconds(10)
        val LEASE_RECOVERY_INTERVAL: Duration = Duration.ofSeconds(30)
        val SCHEDULER_RESCAN_INTERVAL: Duration = Duration.ofSeconds(5)
    }
}
