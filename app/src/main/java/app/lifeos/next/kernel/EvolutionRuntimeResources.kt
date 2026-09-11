package app.lifeos.next.kernel

import app.lifeos.core.runtime.capability.GeneratedToolArtifactRepository
import app.lifeos.core.runtime.capability.GeneratedToolLifecycleCoordinator
import app.lifeos.core.runtime.capability.GeneratedToolRegistry
import app.lifeos.core.runtime.capability.GeneratedToolTrialLedger
import app.lifeos.core.runtime.evolution.EvolutionCanaryOutcomeCoordinator
import app.lifeos.core.runtime.evolution.EvolutionCanaryRouter
import app.lifeos.core.runtime.evolution.EvolutionPromotionBridge
import app.lifeos.core.runtime.evolution.PrivateNovelCapabilityActivationCoordinator

/**
 * Process-owned evolution graph. All members deliberately share the exact same durable stores,
 * generated-tool registry, trial ledger and capability registry.
 */
internal data class EvolutionRuntimeResources(
    val generatedTools: GeneratedToolRegistry,
    val trialLedger: GeneratedToolTrialLedger,
    val lifecycle: GeneratedToolLifecycleCoordinator,
    val canaryRouter: EvolutionCanaryRouter,
    val outcomeCoordinator: EvolutionCanaryOutcomeCoordinator,
    val promotionBridge: EvolutionPromotionBridge,
    val privateNovelActivation: PrivateNovelCapabilityActivationCoordinator,
    val artifactRepository: GeneratedToolArtifactRepository,
)
