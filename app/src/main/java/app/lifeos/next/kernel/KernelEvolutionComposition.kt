package app.lifeos.next.kernel

import android.content.Context
import app.lifeos.core.data.capability.EncryptedGeneratedToolStateRepository
import app.lifeos.core.data.evolution.EncryptedEvolutionStore
import app.lifeos.core.runtime.capability.CapabilityRegistry
import app.lifeos.core.runtime.capability.GeneratedToolBootStateRehydrator
import app.lifeos.core.runtime.capability.GeneratedToolLifecycleCoordinator
import app.lifeos.core.runtime.capability.GeneratedToolRegistry
import app.lifeos.core.runtime.capability.GeneratedToolTrialLedger
import app.lifeos.core.runtime.capability.LanguageGoalCapabilityRouter
import app.lifeos.core.runtime.evolution.BoundedNovelPromotionCoordinator
import app.lifeos.core.runtime.evolution.EvolutionCanaryOutcomeCoordinator
import app.lifeos.core.runtime.evolution.EvolutionCanaryRouter
import app.lifeos.core.runtime.evolution.EvolutionPromotionBridge
import app.lifeos.core.runtime.evolution.NovelCapabilityAdmissionGate
import app.lifeos.core.runtime.evolution.NovelCapabilityCanaryCoordinator
import app.lifeos.core.runtime.evolution.NovelCapabilityCanaryReadinessGate
import app.lifeos.core.runtime.evolution.PrivateNovelCapabilityActivationCoordinator
import app.lifeos.core.runtime.learning.LearnedProviderReliabilityResolver

internal data class KernelEvolutionGraph(
    val generatedToolStateRepository: EncryptedGeneratedToolStateRepository,
    val generatedTools: GeneratedToolRegistry,
    val privateGeneratedToolRuntime: PrivateGeneratedToolRuntimeResources,
    val evolutionStore: EncryptedEvolutionStore,
    val generatedToolBootRehydrator: GeneratedToolBootStateRehydrator,
    val evolutionResources: EvolutionRuntimeResources,
    val goalCapabilityRouter: LanguageGoalCapabilityRouter,
)

/**
 * Generated-tool and controlled-evolution composition. It consumes only the foundation capability
 * registry/reliability surfaces and returns the durable evolution graph required by world/cognition/boot.
 */
internal class KernelEvolutionComposition(
    private val appContext: Context,
    private val capabilityRegistry: CapabilityRegistry,
    private val learnedProviderReliability: LearnedProviderReliabilityResolver,
) {
    fun compose(): KernelEvolutionGraph {
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

        return KernelEvolutionGraph(
            generatedToolStateRepository = generatedToolStateRepository,
            generatedTools = generatedTools,
            privateGeneratedToolRuntime = privateGeneratedToolRuntime,
            evolutionStore = evolutionStore,
            generatedToolBootRehydrator = generatedToolBootRehydrator,
            evolutionResources = evolutionResources,
            goalCapabilityRouter = goalCapabilityRouter,
        )
    }
}
