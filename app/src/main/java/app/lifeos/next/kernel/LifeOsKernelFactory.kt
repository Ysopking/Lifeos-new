package app.lifeos.next.kernel

import android.content.Context
import app.lifeos.core.data.EncryptedBinaryAssetStore
import app.lifeos.core.data.EncryptedPhotonStore
import app.lifeos.core.data.cognition.EncryptedCognitionCoverageRepository
import app.lifeos.core.data.cognition.EncryptedCognitionJournalIndexRepository
import app.lifeos.core.data.capability.EncryptedGeneratedToolStateRepository
import app.lifeos.core.data.checkpoint.EncryptedCheckpointRepository
import app.lifeos.core.data.evolution.EncryptedEvolutionStore
import app.lifeos.core.data.field.EncryptedFieldSnapshotRepository
import app.lifeos.core.data.health.EncryptedProtectionStateRepository
import app.lifeos.core.data.learning.EncryptedLearningAdaptationRepository
import app.lifeos.core.data.snapshot.EncryptedCognitiveSnapshotRepository
import app.lifeos.core.data.goal.EncryptedGoalPlanRepository
import app.lifeos.core.runtime.goal.DurableGoalPlanLedger
import app.lifeos.core.data.task.EncryptedTaskRepository
import app.lifeos.core.data.thought.EncryptedFieldThoughtGraphProjectionOutboxRepository
import app.lifeos.core.data.thought.EncryptedThoughtGraphDeltaRepository
import app.lifeos.core.data.thought.EncryptedThoughtMatrixStateRepository
import app.lifeos.core.data.world.EncryptedWorldFormulaSnapshotRepository
import app.lifeos.core.image.nativebackend.MmsiRuntimeBackendProbe
import app.lifeos.core.language.GoalPhotonFactory
import app.lifeos.core.language.LanguageUnderstandingEngine
import app.lifeos.core.language.PhotonLanguageContextBuilder
import app.lifeos.core.model.health.ProtectionMode
import app.lifeos.core.model.health.ProtectionStateLoadResult
import app.lifeos.core.model.worker.WorkerId
import app.lifeos.core.runtime.CognitiveSnapshotManager
import app.lifeos.core.runtime.DurableLifeOsRuntime
import app.lifeos.core.runtime.DurableRuntimeStateBridge
import app.lifeos.core.runtime.InfluenceExecutor
import app.lifeos.core.runtime.RuntimeExecutionGuard
import app.lifeos.core.runtime.RuntimeSupervisor
import app.lifeos.core.runtime.StaticFieldRegistry
import app.lifeos.core.runtime.ThoughtMatrix
import app.lifeos.core.runtime.boot.BootCoordinator
import app.lifeos.core.runtime.boot.BootSnapshotSource
import app.lifeos.core.runtime.boot.TaskRepositoryBootSource
import app.lifeos.core.runtime.boot.PhotonRepositoryBootSource
import app.lifeos.core.runtime.boot.GeneratedToolRegistryBootSource
import app.lifeos.core.runtime.boot.FieldSnapshotRepositoryBootSource
import app.lifeos.core.runtime.boot.CheckpointRepositoryBootSource
import app.lifeos.core.runtime.boot.CapabilityRegistryBootSource
import app.lifeos.core.runtime.boot.BootSnapshotLoader
import app.lifeos.core.runtime.boot.BootReadSession
import app.lifeos.core.runtime.boot.CapabilityWarmup
import app.lifeos.core.runtime.boot.CapabilityWarmupResult
import app.lifeos.core.runtime.boot.ChainedStateRehydrator
import app.lifeos.core.runtime.boot.CompositeBootDeltaDetector
import app.lifeos.core.runtime.boot.CompositeStoreVerifier
import app.lifeos.core.runtime.boot.DefaultBootValidator
import app.lifeos.core.runtime.boot.ModuleRehydrator
import app.lifeos.core.runtime.boot.ModuleRestoreSummary
import app.lifeos.core.runtime.boot.PhotonRehydrator
import app.lifeos.core.runtime.boot.RehydratedRuntimeState
import app.lifeos.core.runtime.boot.RuntimeBootstrapper
import app.lifeos.core.runtime.boot.RuntimeStateRehydrationStep
import app.lifeos.core.runtime.boot.StateRehydrator
import app.lifeos.core.runtime.boot.StoreProbe
import app.lifeos.core.runtime.boot.StoreState
import app.lifeos.core.runtime.boot.StoreStatus
import app.lifeos.core.runtime.boot.ThoughtMatrixWarmup
import app.lifeos.core.runtime.boot.ThoughtMatrixWarmupResult
import app.lifeos.core.runtime.capability.CapabilityContract
import app.lifeos.core.runtime.capability.CapabilityDescriptor
import app.lifeos.core.runtime.capability.CapabilityId
import app.lifeos.core.runtime.capability.CapabilityRegistry
import app.lifeos.core.runtime.capability.GeneratedToolBootStateRehydrator
import app.lifeos.core.runtime.capability.GeneratedToolLifecycleCoordinator
import app.lifeos.core.runtime.capability.GeneratedToolRegistry
import app.lifeos.core.runtime.capability.GeneratedToolState
import app.lifeos.core.runtime.capability.GeneratedToolTrialLedger
import app.lifeos.core.runtime.capability.LanguageGoalCapabilityRouter
import app.lifeos.core.runtime.capability.ProviderState
import app.lifeos.core.runtime.capability.ProviderType
import app.lifeos.core.runtime.capability.TrustLevel
import app.lifeos.core.runtime.cognition.CognitionCoverageIndex
import app.lifeos.core.runtime.cognition.CognitionJournalIndex
import app.lifeos.core.runtime.cognition.CognitiveScheduler
import app.lifeos.core.runtime.cognition.CompositeDurableTaskExecutionObserver
import app.lifeos.core.runtime.cognition.ContinuousCognitionEngine
import app.lifeos.core.runtime.cognition.DurableCognitionAdmissionController
import app.lifeos.core.runtime.cognition.DurableCognitionDispatcher
import app.lifeos.core.runtime.cognition.DurableCognitionReconciler
import app.lifeos.core.runtime.cognition.DurableCognitionRecoveryObserver
import app.lifeos.core.runtime.cognition.DurableCognitiveTriggerSink
import app.lifeos.core.runtime.cognition.PhotonBackedCognitiveOutcomeJournal
import app.lifeos.core.runtime.cognition.PhotonBackedCognitiveTriggerSink
import app.lifeos.core.runtime.cognition.PhotonBackedPhotonTransactionJournal
import app.lifeos.core.runtime.cognition.PhotonBackedRuntimeEventJournal
import app.lifeos.core.runtime.cognition.OutcomeTriggerObserver
import app.lifeos.core.runtime.cognition.PhotonTransactionObserver
import app.lifeos.core.runtime.context.DurableContextFieldEnricher
import app.lifeos.core.runtime.evolution.BoundedNovelPromotionCoordinator
import app.lifeos.core.runtime.evolution.EvolutionCanaryOutcomeCoordinator
import app.lifeos.core.runtime.evolution.EvolutionCanaryRouter
import app.lifeos.core.runtime.evolution.EvolutionPromotionBridge
import app.lifeos.core.runtime.evolution.NovelCapabilityAdmissionGate
import app.lifeos.core.runtime.evolution.NovelCapabilityCanaryCoordinator
import app.lifeos.core.runtime.evolution.NovelCapabilityCanaryReadinessGate
import app.lifeos.core.runtime.evolution.PrivateNovelCapabilityActivationCoordinator
import app.lifeos.core.runtime.field.FieldThoughtGraphProjectionCoordinator
import app.lifeos.core.runtime.field.UniversalFieldRuntimeAdapter
import app.lifeos.core.runtime.health.CircuitBreaker
import app.lifeos.core.runtime.health.HealthGate
import app.lifeos.core.runtime.health.HealthGraph
import app.lifeos.core.runtime.health.HealthGraphProtectionResumeVerifier
import app.lifeos.core.runtime.health.HealthNodeId
import app.lifeos.core.runtime.health.HealthTaskExecutionObserver
import app.lifeos.core.runtime.health.ProtectionCoordinator
import app.lifeos.core.runtime.health.QuarantineRegistry
import app.lifeos.core.runtime.health.RuntimeHealthMonitor
import app.lifeos.core.runtime.learning.DurableLearningAdaptationLedger
import app.lifeos.core.runtime.learning.LearnedFieldCalibration
import app.lifeos.core.runtime.learning.LearnedProviderReliabilityResolver
import app.lifeos.core.runtime.recovery.LeaseRecoveryLoop
import app.lifeos.core.runtime.recovery.LeaseRecoveryService
import app.lifeos.core.runtime.tasks.ConflatedTaskSchedulerSignal
import app.lifeos.core.runtime.tasks.DurableCognitivePipeline
import app.lifeos.core.runtime.tasks.DurableTaskEngine
import app.lifeos.core.runtime.tasks.PooledTaskScheduler
import app.lifeos.core.runtime.tasks.CognitiveWorkerSlot
import app.lifeos.core.runtime.tasks.CognitiveWorkerPool
import app.lifeos.core.runtime.tasks.CognitiveWorkerLane
import app.lifeos.core.runtime.tasks.TaskSchedulerLoop
import app.lifeos.core.runtime.thought.DurableThoughtGraph
import app.lifeos.core.runtime.workers.CognitiveWorkerConfig
import app.lifeos.core.runtime.workers.CognitiveWorkerFactory
import app.lifeos.core.runtime.workers.ReportingCognitiveTaskDispatcher
import app.lifeos.core.scene.ProceduralSceneCompiler
import app.lifeos.core.scene.ReferenceCpuSceneRasterizer
import app.lifeos.core.scene.SceneRasterizer
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
    private val hardwareResourceIntelligence: HardwareResourceIntelligenceRuntime? = null,
) {
    fun create(): LifeOsKernel {
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val appContext = context.applicationContext
        val store = EncryptedPhotonStore(appContext)
        val cognitionJournalIndex = CognitionJournalIndex(
            repository = EncryptedCognitionJournalIndexRepository(appContext),
            photons = store,
        )
        val cognitionCoverageIndex = CognitionCoverageIndex(
            repository = EncryptedCognitionCoverageRepository(appContext),
        )
        val learningAdaptationRepository = EncryptedLearningAdaptationRepository(appContext)
        val learningAdaptations = DurableLearningAdaptationLedger(learningAdaptationRepository)
        val goalPlanRepository = EncryptedGoalPlanRepository(appContext)
        val goalPlans = DurableGoalPlanLedger(goalPlanRepository)
        val learnedProviderReliability = LearnedProviderReliabilityResolver(learningAdaptations)
        val learnedFieldCalibration = LearnedFieldCalibration(learningAdaptations)
        val assetStore = EncryptedBinaryAssetStore(appContext)
        val thoughtMatrixStateRepository = EncryptedThoughtMatrixStateRepository(appContext)
        val thoughtGraphDeltaRepository = EncryptedThoughtGraphDeltaRepository(appContext)
        val thoughtGraph = DurableThoughtGraph(thoughtGraphDeltaRepository)
        val matrix = ThoughtMatrix(durableState = thoughtMatrixStateRepository)
        val registry = StaticFieldRegistry(listOf(matrix))
        val executor = InfluenceExecutor()
        val healthGraph = HealthGraph()
        val circuitBreaker = CircuitBreaker()
        val quarantineRegistry = QuarantineRegistry()
        val protectionRepository = EncryptedProtectionStateRepository(appContext)
        val protectionCoordinator = ProtectionCoordinator(
            repository = protectionRepository,
            quarantineRegistry = quarantineRegistry,
            verifier = HealthGraphProtectionResumeVerifier(healthGraph),
            healthGraph = healthGraph,
        )
        val healthGate = HealthGate(
            circuitBreaker = circuitBreaker,
            quarantineRegistry = quarantineRegistry,
            protectionAdmission = protectionCoordinator,
        )
        val mmsiRuntime = MmsiRuntimeBackendProbe(appContext)
        val languageUnderstanding = LanguageUnderstandingEngine()
        val goalPhotonFactory = GoalPhotonFactory()
        val languageContextBuilder = PhotonLanguageContextBuilder()
        val sceneCompiler = ProceduralSceneCompiler()
        val sceneRasterizer: SceneRasterizer = ReferenceCpuSceneRasterizer()
        val proceduralImageGenerator = ProceduralImageGenerationEngine(
            context = appContext,
            runtimeProbe = mmsiRuntime,
            sceneCompiler = sceneCompiler,
            sceneRasterizer = sceneRasterizer,
            computeDispatcher = dispatcher,
        )
        val capabilityRegistry = CapabilityRegistry(
            listOf(
                CapabilityDescriptor(
                    capabilityId = CapabilityId("language.understand"),
                    providerId = "language-core",
                    providerType = ProviderType.MODULE,
                    contract = CapabilityContract(
                        requiredInputs = setOf("chat-photon"),
                        outputs = setOf("goal-photon"),
                    ),
                    state = ProviderState.ACTIVE,
                    trustLevel = TrustLevel.SYSTEM,
                    reliability = 1.0,
                    cost = 0.0,
                ),
                CapabilityDescriptor(
                    capabilityId = CapabilityId("scene.construct.procedural"),
                    providerId = "procedural-scene-core",
                    providerType = ProviderType.MODULE,
                    contract = CapabilityContract(
                        requiredInputs = setOf("goal-photon"),
                        outputs = setOf("scene-graph"),
                    ),
                    state = ProviderState.ACTIVE,
                    trustLevel = TrustLevel.SYSTEM,
                    reliability = 0.92,
                    cost = 0.0,
                ),
                CapabilityDescriptor(
                    capabilityId = CapabilityId("scene.rasterize.mmsi"),
                    providerId = "scene-reference-rasterizer",
                    providerType = ProviderType.MODULE,
                    contract = CapabilityContract(
                        requiredInputs = setOf("scene-graph"),
                        outputs = setOf("mmsi-geometry-buffers"),
                    ),
                    state = ProviderState.ACTIVE,
                    trustLevel = TrustLevel.SYSTEM,
                    reliability = 0.96,
                    cost = 0.0,
                ),
                CapabilityDescriptor(
                    capabilityId = CapabilityId("image.render.mmsi"),
                    providerId = "mmsi-runtime",
                    providerType = ProviderType.MODULE,
                    contract = CapabilityContract(
                        requiredInputs = setOf("mmsi-geometry-buffers"),
                        outputs = setOf("image-photon"),
                    ),
                    state = ProviderState.ACTIVE,
                    trustLevel = TrustLevel.SYSTEM,
                    reliability = 0.95,
                    cost = 0.0,
                ),
            ) + LanguageGoalCapabilityRouter.LOCAL_SYSTEM_PROVIDERS
        )

        val generatedToolStateRepository = EncryptedGeneratedToolStateRepository(appContext)
        val generatedTools = GeneratedToolRegistry(durableState = generatedToolStateRepository)
        val generatedToolTrials = GeneratedToolTrialLedger(durableState = generatedToolStateRepository)
        val generatedToolLifecycle = GeneratedToolLifecycleCoordinator(
            tools = generatedTools,
            trialLedger = generatedToolTrials,
            capabilityRegistry = capabilityRegistry,
        )
        val privateGeneratedToolRuntime = PrivateGeneratedToolRuntimeResources.create(
            context = appContext,
            stateRepository = generatedToolStateRepository,
            tools = generatedTools,
            lifecycle = generatedToolLifecycle,
        )
        val evolutionStore = EncryptedEvolutionStore(appContext)
        val novelAdmissionGate = NovelCapabilityAdmissionGate(
            capabilities = capabilityRegistry,
            tools = generatedTools,
            artifacts = privateGeneratedToolRuntime.artifactRepository,
        )
        val novelCanary = NovelCapabilityCanaryCoordinator(
            admissionGate = novelAdmissionGate,
            trialRunner = privateGeneratedToolRuntime.trialRunner,
            trialLedger = generatedToolTrials,
            store = evolutionStore,
        )
        val novelReadiness = NovelCapabilityCanaryReadinessGate(
            admissionGate = novelAdmissionGate,
            trialLedger = generatedToolTrials,
            store = evolutionStore,
        )
        val boundedNovelPromotion = BoundedNovelPromotionCoordinator(
            admissionGate = novelAdmissionGate,
            readinessGate = novelReadiness,
            promotionStore = evolutionStore,
            capabilities = capabilityRegistry,
            tools = generatedTools,
            artifacts = privateGeneratedToolRuntime.artifactRepository,
            trials = generatedToolTrials,
            lifecycle = generatedToolLifecycle,
        )
        val privateNovelActivation = PrivateNovelCapabilityActivationCoordinator(
            capabilities = capabilityRegistry,
            tools = generatedTools,
            artifacts = privateGeneratedToolRuntime.artifactRepository,
            trialLedger = generatedToolTrials,
            canary = novelCanary,
            admissionGate = novelAdmissionGate,
            readinessGate = novelReadiness,
            promotionStore = evolutionStore,
            promotion = boundedNovelPromotion,
        )
        val generatedToolBootRehydrator = GeneratedToolBootStateRehydrator(
            repository = generatedToolStateRepository,
            tools = generatedTools,
            trialLedger = generatedToolTrials,
            capabilityRegistry = capabilityRegistry,
            artifactRepository = privateGeneratedToolRuntime.artifactRepository,
            novelPromotionStore = evolutionStore,
        )
        val evolutionResources = EvolutionRuntimeResources(
            generatedTools = generatedTools,
            trialLedger = generatedToolTrials,
            lifecycle = generatedToolLifecycle,
            canaryRouter = EvolutionCanaryRouter(evolutionStore),
            outcomeCoordinator = EvolutionCanaryOutcomeCoordinator(
                runtimeStore = evolutionStore,
                outcomeStore = evolutionStore,
                lifecycle = generatedToolLifecycle,
            ),
            promotionBridge = EvolutionPromotionBridge(
                runtimeStore = evolutionStore,
                outcomeStore = evolutionStore,
                lifecycle = generatedToolLifecycle,
            ),
            privateNovelActivation = privateNovelActivation,
            artifactRepository = privateGeneratedToolRuntime.artifactRepository,
        )
        val goalCapabilityRouter = LanguageGoalCapabilityRouter(
            registry = capabilityRegistry,
            reliability = learnedProviderReliability,
        )

        val taskRepository = EncryptedTaskRepository(appContext)
        val checkpointRepository = EncryptedCheckpointRepository(appContext)
        val fieldSnapshotRepository = EncryptedFieldSnapshotRepository(appContext)
        val bootReadSession = BootReadSession(
            BootSnapshotLoader(
                photons = PhotonRepositoryBootSource(store),
                tasks = TaskRepositoryBootSource(taskRepository),
                checkpoints = CheckpointRepositoryBootSource(checkpointRepository),
                capabilities = CapabilityRegistryBootSource(capabilityRegistry),
                tools = GeneratedToolRegistryBootSource(generatedTools),
                fieldSnapshots = FieldSnapshotRepositoryBootSource(fieldSnapshotRepository),
            )
        )
        val fieldThoughtGraphProjectionOutbox =
            EncryptedFieldThoughtGraphProjectionOutboxRepository(appContext)
        val fieldThoughtGraphProjection = FieldThoughtGraphProjectionCoordinator(
            outbox = fieldThoughtGraphProjectionOutbox,
            snapshots = fieldSnapshotRepository,
            graph = thoughtGraph,
        )
        val worldFormulaSnapshotRepository = EncryptedWorldFormulaSnapshotRepository(appContext)
        val universalFieldShadow = UniversalFieldRuntimeAdapter(
            snapshotRepository = fieldSnapshotRepository,
            requestEnricher = DurableContextFieldEnricher(store),
            engineProvider = { learnedFieldCalibration.engine() },
            healthGate = healthGate,
            thoughtGraphProjection = fieldThoughtGraphProjection,
        )
        val schedulerSignal = ConflatedTaskSchedulerSignal()
        val taskEngine = DurableTaskEngine(taskRepository, schedulerSignal)

        val cognitiveEventJournal = PhotonBackedRuntimeEventJournal(
            store = store,
            journalIndex = cognitionJournalIndex,
        )
        val cognitiveSnapshotManager = CognitiveSnapshotManager(
            repository = EncryptedCognitiveSnapshotRepository(appContext),
        )
        val cognitiveScheduler = CognitiveScheduler()
        val cognitionAdmission = DurableCognitionAdmissionController(
            tasks = taskRepository,
            taskEngine = taskEngine,
        )
        val continuousCognition = ContinuousCognitionEngine(
            journal = cognitiveEventJournal,
            scheduler = cognitiveScheduler,
            durableDispatcher = DurableCognitionDispatcher(
                taskEngine = taskEngine,
                admissionController = cognitionAdmission,
                coverageIndex = cognitionCoverageIndex,
            ),
        )
        val cognitionReconciler = DurableCognitionReconciler(
            photons = store,
            tasks = taskRepository,
            cognition = continuousCognition,
            taskEngine = taskEngine,
            coverage = cognitionCoverageIndex,
        )
        val photonTransactions = PhotonBackedPhotonTransactionJournal(
            store = store,
            journalIndex = cognitionJournalIndex,
        )
        val cognitiveOutcomes = PhotonBackedCognitiveOutcomeJournal(
            store = store,
            journalIndex = cognitionJournalIndex,
        )
        val cognitiveTriggers = DurableCognitiveTriggerSink(
            journal = PhotonBackedCognitiveTriggerSink(
                store = store,
                journalIndex = cognitionJournalIndex,
            ),
            photons = store,
            taskEngine = taskEngine,
        )

        val workerFactory = CognitiveWorkerFactory(
            tasks = taskRepository,
            photons = store,
            fields = registry,
            executor = executor,
            checkpoints = checkpointRepository,
            fieldShadowProcessor = universalFieldShadow,
            config = CognitiveWorkerConfig(
                leaseDuration = TASK_LEASE_DURATION,
                heartbeatInterval = HEARTBEAT_INTERVAL,
            ),
        )
        val durableStateBridge = DurableRuntimeStateBridge()
        val hardware = hardwareResourceIntelligence?.currentHardwareSnapshot()
        val availableCores = hardware?.availableProcessors ?: 1
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
                        graph = healthGraph,
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
            tasks = taskRepository,
            workers = workerPool,
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
            executionGuard = RuntimeExecutionGuard {
                protectionCoordinator.snapshot().mode != ProtectionMode.SAFE_MODE
            },
        )
        RuntimeHealthMonitor(
            scope = scope,
            runtime = durableRuntime,
            graph = healthGraph,
        ).start()
        val supervisor = RuntimeSupervisor(durableRuntime)

        val primaryStateRehydrator = object : StateRehydrator {
            override suspend fun rehydrate(): RehydratedRuntimeState {
                protectionCoordinator.rehydrate()
                recoverExpiredLeases(leaseRecovery)
                return RehydratedRuntimeState()
            }
        }
        val stateRehydrator = ChainedStateRehydrator(
            primary = primaryStateRehydrator,
            additionalSteps = listOf(
                RuntimeStateRehydrationStep {
                    goalPlans.rehydrate()
                },
                RuntimeStateRehydrationStep {
                    learningAdaptations.rehydrate()
                },
                RuntimeStateRehydrationStep {
                    thoughtGraph.rehydrate()
                },
                RuntimeStateRehydrationStep {
                    fieldThoughtGraphProjection.reconcile()
                },
                RuntimeStateRehydrationStep {
                    cognitionJournalIndex.reconcile()
                },
                RuntimeStateRehydrationStep {
                    cognitiveSnapshotManager.replay(cognitiveEventJournal)
                },
                RuntimeStateRehydrationStep {
                    cognitionReconciler.reconcile()
                },
                RuntimeStateRehydrationStep {
                    evolutionStore.killSwitch(BOOT_PROBE_ADOPTION_ID)
                },
                RuntimeStateRehydrationStep {
                    generatedToolStateRepository.loadAll()
                },
                RuntimeStateRehydrationStep {
                    privateGeneratedToolRuntime.artifactBootVerifier.verify()
                },
                RuntimeStateRehydrationStep {
                    generatedToolBootRehydrator.rehydrateOrVerify()
                },
            ),
        )

        val bootCoordinator = BootCoordinator(
            runtimeBootstrapper = object : RuntimeBootstrapper {
                override suspend fun bootstrap() = Unit
            },
            storeVerifier = CompositeStoreVerifier(
                probes = listOf(
                    object : StoreProbe {
                        override val storeId: String = "goal-plan-ledger"
                        override suspend fun probe(): StoreStatus {
                            val report = bootReadSession.readOnce("goal-plan-ledger") {
                                goalPlanRepository.loadReport()
                            }
                            return StoreStatus(
                                storeId = storeId,
                                state = if (report.isCorrupted) StoreState.CORRUPTED else StoreState.HEALTHY,
                                message = if (report.isCorrupted) "unreadable:${report.unreadableEntries.size}" else null,
                            )
                        }
                    },
                    object : StoreProbe {
                        override val storeId: String = "photon-store"
                        override suspend fun probe(): StoreStatus {
                            val failures = bootReadSession.readFailures(BootSnapshotSource.PHOTON)
                            return StoreStatus(
                                storeId = storeId,
                                state = if (failures.isEmpty()) StoreState.HEALTHY else StoreState.PARTIALLY_RECOVERABLE,
                                message = if (failures.isEmpty()) null else "unreadable:${failures.size}",
                            )
                        }
                    },
                    object : StoreProbe {
                        override val storeId: String = "learning-adaptation-ledger"
                        override suspend fun probe(): StoreStatus {
                            val report = bootReadSession.readOnce("learning-adaptation-ledger") {
                                learningAdaptationRepository.loadReport()
                            }
                            return StoreStatus(
                                storeId = storeId,
                                state = if (report.unreadableEntries.isEmpty()) StoreState.HEALTHY else StoreState.CORRUPTED,
                                message = if (report.unreadableEntries.isEmpty()) null else "unreadable:${report.unreadableEntries.size}",
                            )
                        }
                    },
                    object : StoreProbe {
                        override val storeId: String = "thought-matrix-state-store"
                        override suspend fun probe(): StoreStatus {
                            bootReadSession.readOnce("thought-matrix-state-store") {
                                thoughtMatrixStateRepository.load()
                            }
                            return StoreStatus(storeId, StoreState.HEALTHY)
                        }
                    },
                    object : StoreProbe {
                        override val storeId: String = "thought-graph-delta-store"
                        override suspend fun probe(): StoreStatus {
                            val report = bootReadSession.readOnce("thought-graph-delta-store") {
                                thoughtGraphDeltaRepository.loadReport()
                            }
                            return StoreStatus(
                                storeId = storeId,
                                state = if (report.unreadableEntries.isEmpty()) StoreState.HEALTHY else StoreState.CORRUPTED,
                                message = if (report.unreadableEntries.isEmpty()) null else "unreadable:${report.unreadableEntries.size}",
                            )
                        }
                    },
                    object : StoreProbe {
                        override val storeId: String = "field-thought-graph-projection-outbox"
                        override suspend fun probe(): StoreStatus {
                            val report = bootReadSession.readOnce("field-thought-graph-projection-outbox") {
                                fieldThoughtGraphProjectionOutbox.loadReport()
                            }
                            return StoreStatus(
                                storeId = storeId,
                                state = if (report.unreadableEntries.isEmpty()) StoreState.HEALTHY else StoreState.CORRUPTED,
                                message = if (report.unreadableEntries.isEmpty()) null else "unreadable:${report.unreadableEntries.size}",
                            )
                        }
                    },
                    object : StoreProbe {
                        override val storeId: String = "task-store"
                        override suspend fun probe(): StoreStatus {
                            val failures = bootReadSession.readFailures(BootSnapshotSource.TASK)
                            return StoreStatus(
                                storeId = storeId,
                                state = if (failures.isEmpty()) StoreState.HEALTHY else StoreState.CORRUPTED,
                                message = if (failures.isEmpty()) null else "unreadable:${failures.size}",
                            )
                        }
                    },
                    object : StoreProbe {
                        override val storeId: String = "runtime-protection-store"
                        override suspend fun probe(): StoreStatus = when (
                            val protection = bootReadSession.readOnce("runtime-protection-store") {
                                protectionRepository.load()
                            }
                        ) {
                            ProtectionStateLoadResult.Missing -> StoreStatus(storeId = storeId, state = StoreState.HEALTHY)
                            is ProtectionStateLoadResult.Loaded -> StoreStatus(
                                storeId = storeId,
                                state = if (protection.state.protected) StoreState.LOCKED else StoreState.HEALTHY,
                                message = protection.state.takeIf { it.protected }?.let { "active:${it.mode.name.lowercase()}:generation-${it.generation}" },
                            )
                            is ProtectionStateLoadResult.Unreadable -> StoreStatus(storeId = storeId, state = StoreState.CORRUPTED, message = protection.message)
                        }
                    },
                    object : StoreProbe {
                        override val storeId: String = "field-snapshot-store"
                        override suspend fun probe(): StoreStatus {
                            val failures = bootReadSession.readFailures(BootSnapshotSource.FIELD)
                            return StoreStatus(
                                storeId = storeId,
                                state = if (failures.isEmpty()) StoreState.HEALTHY else StoreState.PARTIALLY_RECOVERABLE,
                                message = if (failures.isEmpty()) null else "unreadable:${failures.size}",
                            )
                        }
                    },
                    object : StoreProbe {
                        override val storeId: String = "world-formula-snapshot-store"
                        override suspend fun probe(): StoreStatus {
                            val report = bootReadSession.readOnce("world-formula-snapshot-store") {
                                worldFormulaSnapshotRepository.loadReport()
                            }
                            return StoreStatus(
                                storeId = storeId,
                                state = if (report.unreadableEntries.isEmpty()) StoreState.HEALTHY else StoreState.PARTIALLY_RECOVERABLE,
                                message = if (report.unreadableEntries.isEmpty()) null else "unreadable:${report.unreadableEntries.size}",
                            )
                        }
                    },
                    object : StoreProbe {
                        override val storeId: String = "evolution-store"
                        override suspend fun probe(): StoreStatus {
                            bootReadSession.readOnce("evolution-store") {
                                evolutionStore.killSwitch(BOOT_PROBE_ADOPTION_ID)
                            }
                            return StoreStatus(storeId, StoreState.HEALTHY)
                        }
                    },
                    object : StoreProbe {
                        override val storeId: String = "generated-tool-state-store"
                        override suspend fun probe(): StoreStatus {
                            bootReadSession.snapshot()
                            val failures = bootReadSession.readFailures(BootSnapshotSource.TOOL)
                            return StoreStatus(
                                storeId = storeId,
                                state = if (failures.isEmpty()) StoreState.HEALTHY else StoreState.CORRUPTED,
                                message = if (failures.isEmpty()) null else "unreadable:${failures.size}",
                            )
                        }
                    },
                    object : StoreProbe {
                        override val storeId: String = "generated-tool-artifact-store"
                        override suspend fun probe(): StoreStatus {
                            privateGeneratedToolRuntime.artifactBootVerifier.verify()
                            return StoreStatus(storeId, StoreState.HEALTHY)
                        }
                    },
                )
            ),
            stateRehydrator = stateRehydrator,
            photonRehydrator = PhotonRehydrator(
                repository = store,
                journalIndex = cognitionJournalIndex,
                bootReadSession = bootReadSession,
            ),
            moduleRehydrator = object : ModuleRehydrator {
                override suspend fun rehydrate(): ModuleRestoreSummary {
                    matrix.rehydrate()
                    return ModuleRestoreSummary(restored = registry.activeFields().size)
                }
            },
            thoughtMatrixWarmup = object : ThoughtMatrixWarmup {
                override suspend fun warmup() = ThoughtMatrixWarmupResult()
            },
            capabilityWarmup = object : CapabilityWarmup {
                override suspend fun warmup(): CapabilityWarmupResult {
                    val activeGeneratedToolIds = evolutionResources.generatedTools.snapshot()
                        .filter { it.state == GeneratedToolState.ACTIVE }
                        .map { it.manifest.toolId }
                        .toSet()
                    val providers = capabilityRegistry.all(includeUnavailable = true)
                    val generatedProviderIds = providers.filter { it.providerType == ProviderType.GENERATED_TOOL }
                        .map { it.providerId }
                        .toSet()
                    require(generatedProviderIds == activeGeneratedToolIds) {
                        "Generated-tool capability registry differs from rehydrated ACTIVE tool set"
                    }
                    val availableCapabilityIds = providers
                        .filter { it.state == ProviderState.ACTIVE || it.state == ProviderState.DEGRADED }
                        .map { it.capabilityId }
                        .toSet()
                    val degradedCapabilityIds = providers
                        .filter { it.state == ProviderState.DEGRADED }
                        .map { it.capabilityId }
                        .toSet()
                    return CapabilityWarmupResult(
                        availableCapabilities = availableCapabilityIds.size,
                        degradedCapabilities = degradedCapabilityIds.size,
                    )
                }
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
            mmsiRuntime = mmsiRuntime,
            languageUnderstanding = languageUnderstanding,
            goalPhotonFactory = goalPhotonFactory,
            goalPlans = goalPlans,
            languageContextBuilder = languageContextBuilder,
            goalCapabilityRouter = goalCapabilityRouter,
            privateGeneratedToolRuntime = privateGeneratedToolRuntime,
            evolutionRuntime = evolutionResources,
            localReminderScheduler = AndroidLocalReminderScheduler(appContext),
            sceneCompiler = sceneCompiler,
            sceneRasterizer = sceneRasterizer,
            imageAssets = assetStore,
            proceduralImageGenerator = proceduralImageGenerator,
        )
    }

    private suspend fun recoverExpiredLeases(recovery: LeaseRecoveryService) {
        while (true) {
            val result = recovery.recoverExpired(LEASE_RECOVERY_BATCH_SIZE)
            if (result.scanned < LEASE_RECOVERY_BATCH_SIZE || result.recovered == 0) return
        }
    }

    private companion object {
        const val BOOT_PROBE_ADOPTION_ID = "__lifeos_boot_integrity_probe__"
        const val LEASE_RECOVERY_BATCH_SIZE = 100
        val TASK_LEASE_DURATION: Duration = Duration.ofSeconds(30)
        val HEARTBEAT_INTERVAL: Duration = Duration.ofSeconds(10)
        val LEASE_RECOVERY_INTERVAL: Duration = Duration.ofSeconds(30)
        val SCHEDULER_RESCAN_INTERVAL: Duration = Duration.ofSeconds(5)
    }
}
