package app.lifeos.next.kernel

import app.lifeos.core.runtime.capability.GeneratedToolLifecycleCoordinator
import app.lifeos.core.runtime.capability.GeneratedToolRegistry
import app.lifeos.core.runtime.capability.GeneratedToolTrialLedger
import app.lifeos.core.runtime.evolution.EvolutionCanaryOutcomeCoordinator
import app.lifeos.core.runtime.evolution.EvolutionCanaryRouter
import app.lifeos.core.runtime.evolution.EvolutionPromotionBridge

/**
 * Process-owned evolution graph. All members are deliberately kept together so routing, outcomes,
 * promotion and generated-tool lifecycle share the exact same durable stores and registries.
 */
internal data class EvolutionRuntimeResources(
    val generatedTools: GeneratedToolRegistry,
    val trialLedger: GeneratedToolTrialLedger,
    val lifecycle: GeneratedToolLifecycleCoordinator,
    val canaryRouter: EvolutionCanaryRouter,
    val outcomeCoordinator: EvolutionCanaryOutcomeCoordinator,
    val promotionBridge: EvolutionPromotionBridge,
)
