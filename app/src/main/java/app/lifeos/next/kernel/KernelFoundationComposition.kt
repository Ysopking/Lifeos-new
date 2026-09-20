package app.lifeos.next.kernel

import android.content.Context
import app.lifeos.core.data.EncryptedBinaryAssetStore
import app.lifeos.core.data.EncryptedPhotonStore
import app.lifeos.core.data.cognition.EncryptedCognitionCoverageRepository
import app.lifeos.core.data.cognition.EncryptedCognitionJournalIndexRepository
import app.lifeos.core.data.cognition.EncryptedCognitiveModuleSnapshotRepository
import app.lifeos.core.data.goal.EncryptedGoalPlanRepository
import app.lifeos.core.data.health.EncryptedProtectionStateRepository
import app.lifeos.core.data.learning.EncryptedLearningAdaptationRepository
import app.lifeos.core.data.thought.EncryptedThoughtGraphDeltaRepository
import app.lifeos.core.data.thought.EncryptedThoughtMatrixStateRepository
import app.lifeos.core.image.nativebackend.MmsiRuntimeBackendProbe
import app.lifeos.core.language.GoalPhotonFactory
import app.lifeos.core.language.LanguageUnderstandingEngine
import app.lifeos.core.language.VersionedLanguageRuntime
import app.lifeos.core.language.PhotonLanguageContextBuilder
import app.lifeos.core.runtime.InfluenceExecutor
import app.lifeos.core.runtime.StaticFieldRegistry
import app.lifeos.core.runtime.ThoughtMatrix
import app.lifeos.core.runtime.capability.CapabilityContract
import app.lifeos.core.runtime.capability.CapabilityDescriptor
import app.lifeos.core.runtime.capability.CapabilityId
import app.lifeos.core.runtime.capability.CapabilityRegistry
import app.lifeos.core.runtime.capability.LanguageGoalCapabilityRouter
import app.lifeos.core.runtime.capability.ProviderState
import app.lifeos.core.runtime.capability.ProviderType
import app.lifeos.core.runtime.capability.TrustLevel
import app.lifeos.core.runtime.cognition.CognitionCoverageIndex
import app.lifeos.core.runtime.cognition.CognitionJournalIndex
import app.lifeos.core.runtime.goal.DurableGoalPlanLedger
import app.lifeos.core.runtime.health.CircuitBreaker
import app.lifeos.core.runtime.health.HealthGate
import app.lifeos.core.runtime.health.HealthGraph
import app.lifeos.core.runtime.health.HealthGraphProtectionResumeVerifier
import app.lifeos.core.runtime.health.ProtectionCoordinator
import app.lifeos.core.runtime.health.QuarantineRegistry
import app.lifeos.core.runtime.learning.DurableLearningAdaptationLedger
import app.lifeos.core.runtime.learning.LearnedFieldCalibration
import app.lifeos.core.runtime.learning.LearnedProviderReliabilityResolver
import app.lifeos.core.runtime.personal.DurableLanguageRuntimeCoordinator
import app.lifeos.core.runtime.thought.DurableThoughtGraph
import app.lifeos.core.scene.ProceduralSceneCompiler
import app.lifeos.core.scene.ReferenceCpuSceneRasterizer
import app.lifeos.core.scene.SceneRasterizer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

internal data class KernelFoundationGraph(
    val scope: CoroutineScope,
    val appContext: Context,
    val cycleResourceIntelligence: HardwareResourceIntelligenceRuntime,
    val store: EncryptedPhotonStore,
    val cognitionJournalIndex: CognitionJournalIndex,
    val cognitionCoverageIndex: CognitionCoverageIndex,
    val cognitiveModuleSnapshotRepository: EncryptedCognitiveModuleSnapshotRepository,
    val learningAdaptationRepository: EncryptedLearningAdaptationRepository,
    val learningAdaptations: DurableLearningAdaptationLedger,
    val goalPlanRepository: EncryptedGoalPlanRepository,
    val goalPlans: DurableGoalPlanLedger,
    val learnedProviderReliability: LearnedProviderReliabilityResolver,
    val learnedFieldCalibration: LearnedFieldCalibration,
    val assetStore: EncryptedBinaryAssetStore,
    val thoughtMatrixStateRepository: EncryptedThoughtMatrixStateRepository,
    val thoughtGraphDeltaRepository: EncryptedThoughtGraphDeltaRepository,
    val thoughtGraph: DurableThoughtGraph,
    val matrix: ThoughtMatrix,
    val registry: StaticFieldRegistry,
    val executor: InfluenceExecutor,
    val healthGraph: HealthGraph,
    val protectionRepository: EncryptedProtectionStateRepository,
    val protectionCoordinator: ProtectionCoordinator,
    val healthGate: HealthGate,
    val mmsiRuntime: MmsiRuntimeBackendProbe,
    val languageUnderstanding: LanguageUnderstandingEngine,
    val goalPhotonFactory: GoalPhotonFactory,
    val languageContextBuilder: PhotonLanguageContextBuilder,
    val sceneCompiler: ProceduralSceneCompiler,
    val sceneRasterizer: SceneRasterizer,
    val proceduralImageGenerator: ProceduralImageGenerationEngine,
    val capabilityRegistry: CapabilityRegistry,
    val languageRuntime: VersionedLanguageRuntime,
    val languageRuntimeState: DurableLanguageRuntimeCoordinator,
)

/**
 * Base process graph: durable stores, health/protection authority, language/image primitives and
 * the static system capability baseline. No generated-tool, evolution, world or boot ownership lives here.
 */
internal class KernelFoundationComposition(
    private val context: Context,
    private val dispatcher: CoroutineDispatcher,
    private val hardwareResourceIntelligence: HardwareResourceIntelligenceRuntime?,
) {
    fun compose(): KernelFoundationGraph {
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val appContext = context.applicationContext
        val cycleResourceIntelligence =
            hardwareResourceIntelligence ?: HardwareResourceIntelligenceRuntime(appContext)
        val store = EncryptedPhotonStore(appContext)
        val cognitionJournalIndex = CognitionJournalIndex(
            repository = EncryptedCognitionJournalIndexRepository(appContext),
            photons = store,
        )
        val cognitionCoverageIndex = CognitionCoverageIndex(
            repository = EncryptedCognitionCoverageRepository(appContext),
        )
        val cognitiveModuleSnapshotRepository =
            EncryptedCognitiveModuleSnapshotRepository(appContext)
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
        val languageRuntime = VersionedLanguageRuntime()
        val languageRuntimeState = DurableLanguageRuntimeCoordinator(languageRuntime, store)
        val languageUnderstanding = languageRuntime.current().understanding
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

        return KernelFoundationGraph(
            scope = scope,
            appContext = appContext,
            cycleResourceIntelligence = cycleResourceIntelligence,
            store = store,
            cognitionJournalIndex = cognitionJournalIndex,
            cognitionCoverageIndex = cognitionCoverageIndex,
            cognitiveModuleSnapshotRepository = cognitiveModuleSnapshotRepository,
            learningAdaptationRepository = learningAdaptationRepository,
            learningAdaptations = learningAdaptations,
            goalPlanRepository = goalPlanRepository,
            goalPlans = goalPlans,
            learnedProviderReliability = learnedProviderReliability,
            learnedFieldCalibration = learnedFieldCalibration,
            assetStore = assetStore,
            thoughtMatrixStateRepository = thoughtMatrixStateRepository,
            thoughtGraphDeltaRepository = thoughtGraphDeltaRepository,
            thoughtGraph = thoughtGraph,
            matrix = matrix,
            registry = registry,
            executor = executor,
            healthGraph = healthGraph,
            protectionRepository = protectionRepository,
            protectionCoordinator = protectionCoordinator,
            healthGate = healthGate,
            mmsiRuntime = mmsiRuntime,
            languageUnderstanding = languageUnderstanding,
            languageRuntime = languageRuntime,
            languageRuntimeState = languageRuntimeState,
            goalPhotonFactory = goalPhotonFactory,
            languageContextBuilder = languageContextBuilder,
            sceneCompiler = sceneCompiler,
            sceneRasterizer = sceneRasterizer,
            proceduralImageGenerator = proceduralImageGenerator,
            capabilityRegistry = capabilityRegistry,
        )
    }
}
