package app.lifeos.next.kernel

import app.lifeos.core.data.learning.EncryptedLearningWatermarkRepository
import app.lifeos.core.data.snapshot.EncryptedCognitiveSnapshotRepository
import app.lifeos.core.model.health.ProtectionMode
import app.lifeos.core.model.worker.WorkerId
import app.lifeos.core.runtime.CognitiveSnapshotDependencyState
import app.lifeos.core.runtime.CognitiveSnapshotManager
import app.lifeos.core.runtime.CognitiveSnapshotProducer
import app.lifeos.core.runtime.CognitiveSnapshotRuntimeRegistry
import app.lifeos.core.runtime.DurableLifeOsRuntime
import app.lifeos.core.runtime.DurableRuntimeStateBridge
import app.lifeos.core.runtime.RuntimeExecutionGuard
import app.lifeos.core.runtime.RuntimeSupervisor
import app.lifeos.core.runtime.boot.BootEngineGoalOutcomeLearning
import app.lifeos.core.runtime.boot.BootEngineLearningPhase
import app.lifeos.core.runtime.capability.CapabilityGapDetector
import app.lifeos.core.runtime.cognition.CognitiveScheduler
import app.lifeos.core.runtime.cognition.CompositeDurableTaskExecutionObserver
import app.lifeos.core.runtime.cognition.ContinuousCognitionEngine
import app.lifeos.core.runtime.cognition.DurableCognitionAdmissionController
import app.lifeos.core.runtime.cognition.DurableCognitionDispatcher
import app.lifeos.core.runtime.cognition.DurableCognitionReconciler
import app.lifeos.core.runtime.cognition.DurableCognitionRecoveryObserver
import app.lifeos.core.runtime.cognition.DurableCognitiveTriggerSink
import app.lifeos.core.runtime.cognition.OutcomeTriggerObserver
import app.lifeos.core.runtime.cognition.PhotonBackedCognitiveOutcomeJournal
import app.lifeos.core.runtime.cognition.PhotonBackedCognitiveTriggerSink
import app.lifeos.core.runtime.cognition.PhotonBackedPhotonTransactionJournal
import app.lifeos.core.runtime.cognition.PhotonBackedRuntimeEventJournal
import app.lifeos.core.runtime.cognition.PhotonTransactionObserver
import app.lifeos.core.runtime.context.DurableContextFieldEnricher
import app.lifeos.core.runtime.field.UniversalFieldRuntimeAdapter
import app.lifeos.core.runtime.health.HealthNodeId
import app.lifeos.core.runtime.health.HealthTaskExecutionObserver
import app.lifeos.core.runtime.health.RuntimeHealthMonitor
import app.lifeos.core.runtime.learning.CognitiveEventLearningSource
import app.lifeos.core.runtime.learning.ContinuousLearningCoordinator
import app.lifeos.core.runtime.learning.DurableLearningWorkSink
import app.lifeos.core.runtime.learning.RegistryLearningCapabilityGapDetector
import app.lifeos.core.runtime.life.DurableLifeMemoryRuntimeRegistry
import app.lifeos.core.runtime.recovery.LeaseRecoveryLoop
import app.lifeos.core.runtime.recovery.LeaseRecoveryService
import app.lifeos.core.runtime.self.SelfObservationAuthorityReader
import app.lifeos.core.runtime.self.SelfObservationAuthorityRuntimeRegistry
import app.lifeos.core.runtime.tasks.CognitiveWorkerLane
import app.lifeos.core.runtime.tasks.CognitiveWorkerPool
import app.lifeos.core.runtime.tasks.CognitiveWorkerSlot
import app.lifeos.core.runtime.tasks.ConflatedTaskSchedulerSignal
import app.lifeos.core.runtime.tasks.DurableCognitivePipeline
import app.lifeos.core.runtime.tasks.DurableTaskEngine
import app.lifeos.core.runtime.tasks.PooledTaskScheduler
import app.lifeos.core.runtime.tasks.TaskSchedulerLoop
import app.lifeos.core.runtime.workers.CognitiveWorkerConfig
import app.lifeos.core.runtime.workers.CognitiveWorkerFactory
import app.lifeos.core.runtime.workers.ReportingCognitiveTaskDispatcher
import java.time.Duration

internal data class KernelCognitionGraph(
    val learningWatermarks: EncryptedLearningWatermarkRepository,
    val cognitiveEventJournal: PhotonBackedRuntimeEventJournal,
    val goalOutcomeLearning: BootEngineGoalOutcomeLearning,
    val cognitiveSnapshotManager: CognitiveSnapshotManager,
    val continuousCognition: ContinuousCognitionEngine,
    val cognitionReconciler: DurableCognitionReconciler,
    val photonTransactions: PhotonBackedPhotonTransactionJournal,
    val cognitiveOutcomes: PhotonBackedCognitiveOutcomeJournal,
    val cognitiveTriggers: DurableCognitiveTriggerSink,
    val leaseRecovery: LeaseRecoveryService,
    val durableRuntime: DurableLifeOsRuntime,
    val supervisor: RuntimeSupervisor,
)

/**
 * Continuous-cognition and worker/runtime composition. Worker sizing remains hardware-adaptive and
 * all durable journals/observers are wired to the same foundation/world stores as before extraction.
 */
internal class KernelCognitionComposition(
    private val foundation: KernelFoundationGraph,
    private val world: KernelWorldGraph,
) {
    fun compose(): KernelCognitionGraph {
        val universalFieldShadow = UniversalFieldRuntimeAdapter(
            snapshotRepository = world.fieldSnapshotRepository,
            requestEnricher = DurableContextFieldEnricher(foundation.store),
            engineProvider = { foundation.learnedFieldCalibration.engine() },
            healthGate = foundation.healthGate,
            thoughtGraphProjection = world.fieldThoughtGraphProjection,
        )
        val schedulerSignal = ConflatedTaskSchedulerSignal()
        val taskEngine = DurableTaskEngine(world.taskRepository, schedulerSignal)

        val cognitiveEventJournal = PhotonBackedRuntimeEventJournal(
            store = foundation.store,
            journalIndex = foundation.cognitionJournalIndex,
        )
        val learningWatermarks = EncryptedLearningWatermarkRepository(foundation.appContext)
        val continuousLearning = ContinuousLearningCoordinator(
            sources = listOf(CognitiveEventLearningSource(cognitiveEventJournal)),
            watermarks = learningWatermarks,
            gapDetector = RegistryLearningCapabilityGapDetector(
                CapabilityGapDetector(foundation.capabilityRegistry)
            ),
            workSink = DurableLearningWorkSink(taskEngine),
        )
        val bootEngineLearning = BootEngineLearningPhase(continuousLearning)
        val goalOutcomeLearning = BootEngineGoalOutcomeLearning(
            worldFormula = world.worldFormulaCoordinator,
            learning = bootEngineLearning,
        )
        val cognitiveSnapshotManager = CognitiveSnapshotManager(
            repository = EncryptedCognitiveSnapshotRepository(foundation.appContext),
        )
        CognitiveSnapshotRuntimeRegistry.install(
            CognitiveSnapshotProducer(
                manager = cognitiveSnapshotManager,
                journal = cognitiveEventJournal,
                worlds = world.worldFormulaSnapshotRepository,
                activeWorldSnapshotId = {
                    world.productiveWorldHeadRepository.load()?.activeSnapshot?.snapshotId
                },
                dependencyState = {
                    foundation.thoughtGraph.snapshot().let { snapshot ->
                        CognitiveSnapshotDependencyState(
                            revision = snapshot.revision,
                            fingerprint = snapshot.contentFingerprint,
                        )
                    }
                },
                memoryFingerprint = {
                    DurableLifeMemoryRuntimeRegistry.current()?.current()?.fingerprint
                },
            )
        )
        SelfObservationAuthorityRuntimeRegistry.install(
            object : SelfObservationAuthorityReader {
                override suspend fun loadProductiveWorldHead() =
                    world.productiveWorldHeadRepository.load()

                override suspend fun loadWorldEquationHead() =
                    world.worldEquationHeads.load()

                override suspend fun loadCommittedBootCycle() =
                    world.bootEngineCycleRepository.loadLatestCommitted()

                override suspend fun loadCognitiveSnapshot() =
                    cognitiveSnapshotManager.latestVerified()
            }
        )
        val cognitiveScheduler = CognitiveScheduler()
        val cognitionAdmission = DurableCognitionAdmissionController(
            tasks = world.taskRepository,
            taskEngine = taskEngine,
        )
        val continuousCognition = ContinuousCognitionEngine(
            journal = cognitiveEventJournal,
            scheduler = cognitiveScheduler,
            durableDispatcher = DurableCognitionDispatcher(
                taskEngine = taskEngine,
                admissionController = cognitionAdmission,
                coverageIndex = foundation.cognitionCoverageIndex,
            ),
        )
        val cognitionReconciler = DurableCognitionReconciler(
            photons = foundation.store,
            tasks = world.taskRepository,
            cognition = continuousCognition,
            taskEngine = taskEngine,
            coverage = foundation.cognitionCoverageIndex,
        )
        val photonTransactions = PhotonBackedPhotonTransactionJournal(
            store = foundation.store,
            journalIndex = foundation.cognitionJournalIndex,
        )
        val cognitiveOutcomes = PhotonBackedCognitiveOutcomeJournal(
            store = foundation.store,
            journalIndex = foundation.cognitionJournalIndex,
        )
        val cognitiveTriggers = DurableCognitiveTriggerSink(
            journal = PhotonBackedCognitiveTriggerSink(
                store = foundation.store,
                journalIndex = foundation.cognitionJournalIndex,
            ),
            photons = foundation.store,
            taskEngine = taskEngine,
        )

        val workerFactory = CognitiveWorkerFactory(
            tasks = world.taskRepository,
            photons = foundation.store,
            fields = foundation.registry,
            executor = foundation.executor,
            checkpoints = world.checkpointRepository,
            fieldShadowProcessor = universalFieldShadow,
            config = CognitiveWorkerConfig(
                leaseDuration = TASK_LEASE_DURATION,
                heartbeatInterval = HEARTBEAT_INTERVAL,
            ),
        )
        val durableStateBridge = DurableRuntimeStateBridge()
        val hardware = foundation.cycleResourceIntelligence.currentHardwareSnapshot()
        val availableCores = hardware.availableProcessors
        val activeWorkers = (availableCores - 1).coerceIn(0, 3)
        val backgroundWorkers = if (availableCores >= 4) 1 else 0
        val maintenanceWorkers = if (availableCores >= 6) 1 else 0

        fun workerSlot(lane: CognitiveWorkerLane, ordinal: Int): CognitiveWorkerSlot {
            val workerId = WorkerId("cognitive-${lane.name.lowercase()}-$ordinal")
            val worker = workerFactory.create(workerId)
            val observer = CompositeDurableTaskExecutionObserver(
                listOf(
                    durableStateBridge,
                    PhotonTransactionObserver(photonTransactions),
                    OutcomeTriggerObserver(
                        outcomes = cognitiveOutcomes,
                        triggers = cognitiveTriggers,
                    ),
                    HealthTaskExecutionObserver(
                        workerNodeId = HealthNodeId("worker:${workerId.value}"),
                        graph = foundation.healthGraph,
                    ),
                    DurableCognitionRecoveryObserver(cognitionReconciler),
                )
            )
            return CognitiveWorkerSlot(
                lane = lane,
                workerId = workerId,
                dispatcher = ReportingCognitiveTaskDispatcher(
                    worker = worker,
                    observer = observer,
                ),
            )
        }

        val workerPool = CognitiveWorkerPool(
            buildList {
                add(workerSlot(CognitiveWorkerLane.INTERACTIVE, 0))
                repeat(activeWorkers) { add(workerSlot(CognitiveWorkerLane.ACTIVE, it)) }
                repeat(backgroundWorkers) { add(workerSlot(CognitiveWorkerLane.BACKGROUND, it)) }
                repeat(maintenanceWorkers) { add(workerSlot(CognitiveWorkerLane.MAINTENANCE, it)) }
            }
        )
        val taskScheduler = PooledTaskScheduler(
            tasks = world.taskRepository,
            workers = workerPool,
            scope = foundation.scope,
            workerAvailableSignal = schedulerSignal,
            leaseDuration = TASK_LEASE_DURATION,
        )
        val schedulerLoop = TaskSchedulerLoop(
            scope = foundation.scope,
            scheduler = taskScheduler,
            wakeSource = schedulerSignal,
            rescanInterval = SCHEDULER_RESCAN_INTERVAL,
        )
        val leaseRecovery = LeaseRecoveryService(
            tasks = world.taskRepository,
            schedulerSignal = schedulerSignal,
        )
        val recoveryLoop = LeaseRecoveryLoop(
            scope = foundation.scope,
            recovery = leaseRecovery,
            interval = LEASE_RECOVERY_INTERVAL,
        )
        val durablePipeline = DurableCognitivePipeline(
            taskEngine = taskEngine,
            schedulerLoop = schedulerLoop,
            recoveryLoop = recoveryLoop,
        )
        val durableRuntime = DurableLifeOsRuntime(
            scope = foundation.scope,
            pipeline = durablePipeline,
            stateBridge = durableStateBridge,
            executionGuard = RuntimeExecutionGuard {
                foundation.protectionCoordinator.snapshot().mode != ProtectionMode.SAFE_MODE
            },
        )
        RuntimeHealthMonitor(
            scope = foundation.scope,
            runtime = durableRuntime,
            graph = foundation.healthGraph,
        ).start()
        val supervisor = RuntimeSupervisor(durableRuntime)

        return KernelCognitionGraph(
            learningWatermarks = learningWatermarks,
            cognitiveEventJournal = cognitiveEventJournal,
            goalOutcomeLearning = goalOutcomeLearning,
            cognitiveSnapshotManager = cognitiveSnapshotManager,
            continuousCognition = continuousCognition,
            cognitionReconciler = cognitionReconciler,
            photonTransactions = photonTransactions,
            cognitiveOutcomes = cognitiveOutcomes,
            cognitiveTriggers = cognitiveTriggers,
            leaseRecovery = leaseRecovery,
            durableRuntime = durableRuntime,
            supervisor = supervisor,
        )
    }

    private companion object {
        val TASK_LEASE_DURATION: Duration = Duration.ofSeconds(30)
        val HEARTBEAT_INTERVAL: Duration = Duration.ofSeconds(10)
        val LEASE_RECOVERY_INTERVAL: Duration = Duration.ofSeconds(30)
        val SCHEDULER_RESCAN_INTERVAL: Duration = Duration.ofSeconds(5)
    }
}
