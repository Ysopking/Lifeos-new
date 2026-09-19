package app.lifeos.next.kernel

import android.content.Context
import app.lifeos.core.data.EncryptedBinaryAssetStore
import app.lifeos.core.data.EncryptedPhotonStore
import app.lifeos.core.runtime.goal.GoalConvergenceDecisionProvider
import app.lifeos.core.runtime.goal.GoalCycleFrozenInputSource
import app.lifeos.core.runtime.extension.ExtensionRegistryRehydrator
import app.lifeos.core.runtime.convergence.WorldFormulaBoundConvergenceService
import app.lifeos.core.runtime.convergence.DurableConvergenceDecisionCoordinator
import app.lifeos.core.runtime.convergence.DefaultProductiveConvergenceAuthority
import app.lifeos.core.data.convergence.EncryptedConvergenceDecisionCheckpointRepository
import app.lifeos.core.data.cognition.EncryptedCognitionCoverageRepository
import app.lifeos.core.data.cognition.EncryptedCognitionJournalIndexRepository
import app.lifeos.core.data.cognition.EncryptedCognitiveModuleSnapshotRepository
import app.lifeos.core.data.capability.EncryptedGeneratedToolStateRepository
import app.lifeos.core.data.checkpoint.EncryptedCheckpointRepository
import app.lifeos.core.data.evolution.EncryptedEvolutionStore
import app.lifeos.core.data.evolution.EncryptedWorldEquationEvidenceRepository
import app.lifeos.core.data.field.EncryptedFieldSnapshotRepository
import app.lifeos.core.data.health.EncryptedProtectionStateRepository
import app.lifeos.core.data.learning.EncryptedLearningAdaptationRepository
import app.lifeos.core.data.learning.EncryptedLearningWatermarkRepository
import app.lifeos.core.data.snapshot.EncryptedCognitiveSnapshotRepository
import app.lifeos.core.data.goal.EncryptedGoalPlanRepository
import app.lifeos.core.runtime.goal.DurableGoalPlanLedger
import app.lifeos.core.data.task.EncryptedTaskRepository
import app.lifeos.core.data.thought.EncryptedFieldThoughtGraphProjectionOutboxRepository
import app.lifeos.core.data.thought.EncryptedThoughtGraphDeltaRepository
import app.lifeos.core.data.thought.EncryptedThoughtMatrixStateRepository
import app.lifeos.core.data.world.EncryptedWorldFormulaSnapshotRepository
import app.lifeos.core.data.world.EncryptedProductiveWorldHeadRepository
import app.lifeos.core.data.world.EncryptedWorldEquationHeadRepository
import app.lifeos.core.data.world.EncryptedWorldEquationSpecRepository
import app.lifeos.core.data.boot.EncryptedBootEngineCycleRepository
import app.lifeos.core.data.extension.EncryptedExtensionRegistryHeadRepository
import app.lifeos.core.data.extension.EncryptedExtensionRegistrySnapshotRepository
import app.lifeos.core.data.worldmodel.EncryptedWorldModelRepository
import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.image.nativebackend.MmsiRuntimeBackendProbe
import app.lifeos.core.language.GoalPhotonFactory
import app.lifeos.core.language.LanguageUnderstandingEngine
import app.lifeos.core.language.PhotonLanguageContextBuilder
import app.lifeos.core.model.health.ProtectionMode
import app.lifeos.core.model.health.ProtectionStateLoadResult
import app.lifeos.core.model.worker.WorkerId
import app.lifeos.core.runtime.CognitiveSnapshotManager
import app.lifeos.core.runtime.life.DurableLifeMemoryRuntimeRegistry
import app.lifeos.core.runtime.CognitiveSnapshotRuntimeRegistry
import app.lifeos.core.runtime.CognitiveSnapshotProducer
import app.lifeos.core.runtime.CognitiveSnapshotDependencyState
import app.lifeos.core.runtime.DurableLifeOsRuntime
import app.lifeos.core.runtime.DurableRuntimeStateBridge
import app.lifeos.core.runtime.InfluenceExecutor
import app.lifeos.core.runtime.RuntimeExecutionGuard
import app.lifeos.core.runtime.RuntimeSupervisor
import app.lifeos.core.runtime.StaticFieldRegistry
import app.lifeos.core.runtime.ThoughtMatrix
import app.lifeos.core.runtime.boot.BootCoordinator
import app.lifeos.core.runtime.boot.BootEngineFrozenInputs
import app.lifeos.core.runtime.boot.BootEngineRuntime
import app.lifeos.core.runtime.boot.BootEngineLearningPhase
import app.lifeos.core.runtime.boot.BootEngineGoalOutcomeLearning
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
import app.lifeos.core.runtime.boot.CognitiveHeadConsistencyDeltaSource
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
import app.lifeos.core.runtime.self.SelfObservationAuthorityReader
import app.lifeos.core.runtime.self.SelfObservationAuthorityRuntimeRegistry
import app.lifeos.core.runtime.capability.CapabilityContract
import app.lifeos.core.runtime.capability.CapabilityDescriptor
import app.lifeos.core.runtime.capability.CapabilityId
import app.lifeos.core.runtime.capability.CapabilityGapDetector
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
import app.lifeos.core.runtime.evolution.WorldEquationEvolutionAdmissionGate
import app.lifeos.core.runtime.evolution.WorldEquationPromotionEvaluator
import app.lifeos.core.runtime.evolution.WorldEquationPromotionPolicy
import app.lifeos.core.runtime.evolution.WorldEquationEvidenceCoordinator
import app.lifeos.core.runtime.evolution.WorldEquationAutoEvolutionCoordinator
import app.lifeos.core.runtime.evolution.WorldEquationShadowEvaluator
import app.lifeos.core.runtime.evolution.WorldEquationPostActivationSafetyMonitor
import app.lifeos.core.runtime.evolution.WorldEquationPostActivationSafetyRuntimeRegistry
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
import app.lifeos.core.runtime.learning.CognitiveEventLearningSource
import app.lifeos.core.runtime.learning.ContinuousLearningCoordinator
import app.lifeos.core.runtime.learning.DurableLearningAdaptationLedger
import app.lifeos.core.runtime.learning.DurableLearningWorkSink
import app.lifeos.core.runtime.learning.LearningWatermarkLoadResult
import app.lifeos.core.runtime.learning.RegistryLearningCapabilityGapDetector
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
import app.lifeos.core.runtime.world.CognitiveCycleId
import app.lifeos.core.runtime.world.CognitiveWorldEquationProfile
import app.lifeos.core.runtime.world.InMemoryWorldEquationRegistry
import app.lifeos.core.runtime.world.ProductiveWorldHeadCommitter
import app.lifeos.core.runtime.world.SelfStateWorldEquationProfile
import app.lifeos.core.runtime.world.SelfStateWorldFormulaEvaluator
import app.lifeos.core.runtime.world.SelfStateWorldFormulaRuntimeRegistry
import app.lifeos.core.runtime.world.SelfStateWorldFormulaSnapshotRepository
import app.lifeos.core.runtime.world.WorldFormulaCoordinator
import app.lifeos.core.runtime.world.WorldFormulaExecutionPolicy
import app.lifeos.core.runtime.world.WorldEquationActivationAuthority
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
    private val bootReadyMaintenanceTrigger: () -> Unit = {},
) {
    fun create(): LifeOsKernel {
        val foundation = KernelFoundationComposition(
            context = context,
            dispatcher = dispatcher,
            hardwareResourceIntelligence = hardwareResourceIntelligence,
        ).compose()
        val scope = foundation.scope
        val appContext = foundation.appContext
        val cycleResourceIntelligence = foundation.cycleResourceIntelligence
        val store = foundation.store
        val cognitionJournalIndex = foundation.cognitionJournalIndex
        val cognitionCoverageIndex = foundation.cognitionCoverageIndex
        val cognitiveModuleSnapshotRepository = foundation.cognitiveModuleSnapshotRepository
        val learningAdaptationRepository = foundation.learningAdaptationRepository
        val learningAdaptations = foundation.learningAdaptations
        val goalPlanRepository = foundation.goalPlanRepository
        val goalPlans = foundation.goalPlans
        val learnedProviderReliability = foundation.learnedProviderReliability
        val learnedFieldCalibration = foundation.learnedFieldCalibration
        val assetStore = foundation.assetStore
        val thoughtMatrixStateRepository = foundation.thoughtMatrixStateRepository
        val thoughtGraphDeltaRepository = foundation.thoughtGraphDeltaRepository
        val thoughtGraph = foundation.thoughtGraph
        val matrix = foundation.matrix
        val registry = foundation.registry
        val executor = foundation.executor
        val healthGraph = foundation.healthGraph
        val protectionRepository = foundation.protectionRepository
        val protectionCoordinator = foundation.protectionCoordinator
        val healthGate = foundation.healthGate
        val mmsiRuntime = foundation.mmsiRuntime
        val languageUnderstanding = foundation.languageUnderstanding
        val goalPhotonFactory = foundation.goalPhotonFactory
        val languageContextBuilder = foundation.languageContextBuilder
        val sceneCompiler = foundation.sceneCompiler
        val sceneRasterizer = foundation.sceneRasterizer
        val proceduralImageGenerator = foundation.proceduralImageGenerator
        val capabilityRegistry = foundation.capabilityRegistry

        val evolution = KernelEvolutionComposition(
            appContext = appContext,
            capabilityRegistry = capabilityRegistry,
            learnedProviderReliability = learnedProviderReliability,
        ).compose()
        val generatedToolStateRepository = evolution.generatedToolStateRepository
        val generatedTools = evolution.generatedTools
        val privateGeneratedToolRuntime = evolution.privateGeneratedToolRuntime
        val evolutionStore = evolution.evolutionStore
        val generatedToolBootRehydrator = evolution.generatedToolBootRehydrator
        val evolutionResources = evolution.evolutionResources
        val goalCapabilityRouter = evolution.goalCapabilityRouter

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
        val productiveWorldHeadRepository = EncryptedProductiveWorldHeadRepository(appContext)
        val bootEngineCycleRepository = EncryptedBootEngineCycleRepository(appContext)
        val extensionRegistrySnapshotRepository =
            EncryptedExtensionRegistrySnapshotRepository(appContext)
        val extensionRegistryHeadRepository =
            EncryptedExtensionRegistryHeadRepository(appContext)
        val extensionRegistryRehydrator = ExtensionRegistryRehydrator(
            heads = extensionRegistryHeadRepository,
            snapshots = extensionRegistrySnapshotRepository,
        )
        val worldModelRepository = EncryptedWorldModelRepository(appContext)
        val cognitiveWorldEquationProfile = CognitiveWorldEquationProfile()
        val worldEquationRegistry = InMemoryWorldEquationRegistry(
            listOf(cognitiveWorldEquationProfile.spec)
        )
        val worldEquationHeads = EncryptedWorldEquationHeadRepository(appContext)
        val worldEquationSpecs = EncryptedWorldEquationSpecRepository(appContext)
        val worldEquationEvidence = EncryptedWorldEquationEvidenceRepository(appContext)
        val worldEquationPromotionEvaluator = WorldEquationPromotionEvaluator(
            WorldEquationPromotionPolicy.V1
        )
        val worldEquationEvidenceCoordinator = WorldEquationEvidenceCoordinator(
            repository = worldEquationEvidence,
            evaluator = worldEquationPromotionEvaluator,
        )
        val worldEquationAdmissionGate = WorldEquationEvolutionAdmissionGate(
            evidence = worldEquationEvidence,
            evaluator = worldEquationPromotionEvaluator,
        )
        val worldEquationAuthority = WorldEquationActivationAuthority(
            equations = worldEquationRegistry,
            heads = worldEquationHeads,
            baseline = cognitiveWorldEquationProfile.spec,
            specs = worldEquationSpecs,
            admissionVerifier = worldEquationAdmissionGate,
        )
        val worldEquationAutoEvolution = WorldEquationAutoEvolutionCoordinator(
            evidence = worldEquationEvidence,
            evidenceCoordinator = worldEquationEvidenceCoordinator,
            shadow = WorldEquationShadowEvaluator(),
            admissionGate = worldEquationAdmissionGate,
            authority = worldEquationAuthority,
        )
        val worldEquationSafetyMonitor = WorldEquationPostActivationSafetyMonitor(
            evidence = worldEquationEvidence,
            evidenceCoordinator = worldEquationEvidenceCoordinator,
            authority = worldEquationAuthority,
        )
        WorldEquationPostActivationSafetyRuntimeRegistry.install(worldEquationSafetyMonitor)
        val worldFormulaCoordinator = WorldFormulaCoordinator(
            equations = worldEquationRegistry,
            snapshots = worldFormulaSnapshotRepository,
        )
        val selfStateWorldEquationProfile = SelfStateWorldEquationProfile()
        SelfStateWorldFormulaRuntimeRegistry.install(
            SelfStateWorldFormulaEvaluator(
                profile = selfStateWorldEquationProfile,
                coordinator = WorldFormulaCoordinator(
                    equations = InMemoryWorldEquationRegistry(listOf(selfStateWorldEquationProfile.spec)),
                    snapshots = SelfStateWorldFormulaSnapshotRepository(),
                    executionPolicy = WorldFormulaExecutionPolicy.SELF_OBSERVATION,
                ),
            )
        )
        val productiveWorldHeadCommitter = ProductiveWorldHeadCommitter(
            snapshots = worldFormulaSnapshotRepository,
            heads = productiveWorldHeadRepository,
        )
        val bootEngineRuntime = BootEngineRuntime(
            cycles = bootEngineCycleRepository,
            worldHeads = productiveWorldHeadRepository,
            worldCoordinator = worldFormulaCoordinator,
            worldCommitter = productiveWorldHeadCommitter,
            newCycleId = {
                CognitiveCycleId("cycle:${java.util.UUID.randomUUID()}")
            },
        )
        val productiveDecisionCoordinator = DurableConvergenceDecisionCoordinator(
            EncryptedConvergenceDecisionCheckpointRepository(appContext),
        )
        val productiveWorldConvergence = WorldFormulaBoundConvergenceService(
            bootEngine = bootEngineRuntime,
            worldSnapshots = worldFormulaSnapshotRepository,
            decisions = productiveDecisionCoordinator,
        )
        val productiveGoalConvergence = GoalConvergenceDecisionProvider(
            productiveConvergence = DefaultProductiveConvergenceAuthority(productiveWorldConvergence),
            bootEngine = bootEngineRuntime,
            photons = store,
            cycleInputs = GoalCycleFrozenInputSource { workingSet, routing ->
                val hardware = cycleResourceIntelligence.currentHardwareSnapshot()
                val calibration = learnedFieldCalibration.profile()
                val strategyFingerprint = StableFieldIds.fingerprint(
                    "productive-goal-strategy-snapshot/v1",
                    calibration.fingerprint,
                    routing.plan.goal.intent.name,
                    *buildList {
                        routing.selectedProviders.entries
                            .sortedBy { it.key.value }
                            .forEach { (capabilityId, provider) ->
                                add(
                                    "provider:${capabilityId.value}:${provider.providerId}:" +
                                        "${provider.state.name}:${provider.trustLevel.name}:" +
                                        "${java.lang.Double.toHexString(provider.reliability)}:" +
                                        java.lang.Double.toHexString(provider.cost)
                                )
                            }
                        routing.blockingGaps
                            .sortedBy { it.requirement.capabilityId.value }
                            .forEach { gap ->
                                add(
                                    "gap:${gap.requirement.capabilityId.value}:${gap.type.name}:" +
                                        gap.requirement.severity.name
                                )
                                gap.candidateProviderIds.sorted().forEach { candidate ->
                                    add("gap-candidate:${gap.requirement.capabilityId.value}:$candidate")
                                }
                            }
                    }.toTypedArray(),
                )
                BootEngineFrozenInputs(
                    representationSnapshotId = workingSet.sourceSnapshotId,
                    strategySnapshotId = "goal-strategy:$strategyFingerprint",
                    equationVersion = worldEquationAuthority.activeVersion(),
                    resourceSnapshotId = "hardware-state:${hardware.fingerprint()}",
                )
            },
        )

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
        val learningWatermarks = EncryptedLearningWatermarkRepository(appContext)
        val continuousLearning = ContinuousLearningCoordinator(
            sources = listOf(CognitiveEventLearningSource(cognitiveEventJournal)),
            watermarks = learningWatermarks,
            gapDetector = RegistryLearningCapabilityGapDetector(
                CapabilityGapDetector(capabilityRegistry)
            ),
            workSink = DurableLearningWorkSink(taskEngine),
        )
        val bootEngineLearning = BootEngineLearningPhase(continuousLearning)
        val goalOutcomeLearning = BootEngineGoalOutcomeLearning(
            worldFormula = worldFormulaCoordinator,
            learning = bootEngineLearning,
        )
        val cognitiveSnapshotManager = CognitiveSnapshotManager(
            repository = EncryptedCognitiveSnapshotRepository(appContext),
        )
        CognitiveSnapshotRuntimeRegistry.install(
            CognitiveSnapshotProducer(
                manager = cognitiveSnapshotManager,
                journal = cognitiveEventJournal,
                worlds = worldFormulaSnapshotRepository,
                activeWorldSnapshotId = {
                    productiveWorldHeadRepository.load()?.activeSnapshot?.snapshotId
                },
                dependencyState = {
                    thoughtGraph.snapshot().let { snapshot ->
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
                    productiveWorldHeadRepository.load()

                override suspend fun loadWorldEquationHead() =
                    worldEquationHeads.load()

                override suspend fun loadCommittedBootCycle() =
                    bootEngineCycleRepository.loadLatestCommitted()

                override suspend fun loadCognitiveSnapshot() =
                    cognitiveSnapshotManager.latestVerified()
            }
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
        val hardware = cycleResourceIntelligence.currentHardwareSnapshot()
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
            scope = scope,
            workerAvailableSignal = schedulerSignal,
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
                    extensionRegistryRehydrator.rehydrate()
                },
                RuntimeStateRehydrationStep {
                    worldEquationAuthority.activeVersion()
                },
                RuntimeStateRehydrationStep {
                    worldEquationSafetyMonitor.reconcile()
                },
                RuntimeStateRehydrationStep {
                    val head = worldModelRepository.loadHead()
                    if (head != null) {
                        val snapshot = requireNotNull(
                            worldModelRepository.loadSnapshot(head.activeSnapshotId)
                        ) { "WorldModel head points to missing snapshot" }
                        require(snapshot.revision == head.revision) {
                            "WorldModel head/snapshot revision mismatch"
                        }
                        require(snapshot.predecessorSnapshotId == head.predecessorSnapshotId)
                    }
                },
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
                probes = KernelBootStoreProbes(
                    bootReadSession = bootReadSession,
                    goalPlanRepository = goalPlanRepository,
                    learningAdaptationRepository = learningAdaptationRepository,
                    learningWatermarks = learningWatermarks,
                    worldEquationHeads = worldEquationHeads,
                    worldEquationSpecs = worldEquationSpecs,
                    worldEquationEvidence = worldEquationEvidence,
                    thoughtMatrixStateRepository = thoughtMatrixStateRepository,
                    thoughtGraphDeltaRepository = thoughtGraphDeltaRepository,
                    fieldThoughtGraphProjectionOutbox = fieldThoughtGraphProjectionOutbox,
                    protectionRepository = protectionRepository,
                    worldFormulaSnapshotRepository = worldFormulaSnapshotRepository,
                    productiveWorldHeadRepository = productiveWorldHeadRepository,
                    bootEngineCycleRepository = bootEngineCycleRepository,
                    extensionRegistryRehydrator = extensionRegistryRehydrator,
                    worldModelRepository = worldModelRepository,
                    cognitiveModuleSnapshotRepository = cognitiveModuleSnapshotRepository,
                    evolutionStore = evolutionStore,
                    privateGeneratedToolRuntime = privateGeneratedToolRuntime,
                ).create(),
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
            deltaDetector = CompositeBootDeltaDetector(
                listOf(
                    CognitiveHeadConsistencyDeltaSource(
                        worldHeads = productiveWorldHeadRepository,
                        activeCycleFingerprint = {
                            bootEngineCycleRepository.loadActive()?.fingerprint
                        },
                    )
                )
            ),
            validator = DefaultBootValidator(),
        )

        return LifeOsKernel(
            runtime = durableRuntime,
            matrix = matrix,
            photonStore = store,
            supervisor = supervisor,
            scope = scope,
            bootCoordinator = bootCoordinator,
            bootEngineRuntime = bootEngineRuntime,
            continuousCognition = continuousCognition,
            cognitiveModuleSnapshotRepository = cognitiveModuleSnapshotRepository,
            activeExtensionSnapshotId = {
                extensionRegistryHeadRepository.load()?.activeSnapshotId
            },
            bootReadyMaintenanceTrigger = bootReadyMaintenanceTrigger,
            photonTransactions = photonTransactions,
            cognitiveOutcomes = cognitiveOutcomes,
            cognitiveTriggers = cognitiveTriggers,
            mmsiRuntime = mmsiRuntime,
            languageUnderstanding = languageUnderstanding,
            goalPhotonFactory = goalPhotonFactory,
            goalPlans = goalPlans,
            productiveGoalConvergence = productiveGoalConvergence,
            goalOutcomeLearning = goalOutcomeLearning,
            languageContextBuilder = languageContextBuilder,
            goalCapabilityRouter = goalCapabilityRouter,
            privateGeneratedToolRuntime = privateGeneratedToolRuntime,
            evolutionRuntime = evolutionResources,
            worldEquationAutoEvolution = worldEquationAutoEvolution,
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
