package app.lifeos.next

import app.lifeos.core.runtime.topology.LifeOsRuntimeBindingRegistry
import app.lifeos.core.runtime.topology.LifeOsRuntimeBindingState
import app.lifeos.next.kernel.PrivateHotSwapRuntimeRegistry

/** Binds concrete Android process composition stages into the shared core runtime topology. */
internal object LifeOsRuntimeWiring {
    fun onStageReady(stage: LifeOsStartupStage) {
        when (stage) {
            LifeOsStartupStage.SHARED_RESOURCES -> LifeOsRuntimeBindingRegistry.installAll(
                listOf("owner-policy", "resource-intelligence", "resource-budgets", "decision-trace"),
                source = "application:shared-resources",
            )

            LifeOsStartupStage.GOAL_EXECUTION -> Unit

            LifeOsStartupStage.KERNEL_GRAPH -> {
                LifeOsRuntimeBindingRegistry.installAll(
                    listOf(
                        "photon-store",
                        "binary-asset-store",
                        "thought-matrix",
                        "thought-graph",
                        "field-runtime",
                        "field-thought-graph-projection",
                        "world-formula",
                        "language-understanding",
                        "language-context",
                        "goal-resume",
                        "capability-registry",
                        "capability-router",
                        "local-knowledge",
                        "scene-compiler",
                        "scene-rasterizer",
                        "image-renderer",
                        "image-transform",
                        "reminder-scheduler",
                        "communication",
                        "durable-task-engine",
                        "continuous-cognition",
                        "cognition-reconciler",
                        "cognition-outcome-pipeline",
                        "cognitive-worker",
                        "task-scheduler",
                        "lease-recovery",
                        "runtime-supervisor",
                        "health-graph",
                        "protection-coordinator",
                        "tool-workshop",
                        "generated-tool-registry",
                        "evolution-hot-swap",
                        "learning-adaptation",
                    ),
                    source = "kernel-factory",
                )
                refreshOptionalRuntimes()
            }

            LifeOsStartupStage.DEEP_SEARCH -> LifeOsRuntimeBindingRegistry.install(
                subsystemId = "deep-search",
                source = "application:deep-search",
            )

            LifeOsStartupStage.SELF_HEALING -> LifeOsRuntimeBindingRegistry.install(
                subsystemId = "self-healing",
                source = "application:self-healing",
            )

            LifeOsStartupStage.DURABLE_GOALS -> LifeOsRuntimeBindingRegistry.install(
                subsystemId = "goal-planning",
                source = "application:durable-goals",
            )

            LifeOsStartupStage.RUNTIME_STARTED -> {
                LifeOsRuntimeBindingRegistry.update(
                    subsystemId = "runtime-supervisor",
                    state = LifeOsRuntimeBindingState.ACTIVE,
                    detail = "kernel-start-requested",
                )
                refreshOptionalRuntimes()
            }
        }
    }

    fun refreshOptionalRuntimes() {
        val hotSwap = PrivateHotSwapRuntimeRegistry.current()
        if (hotSwap != null) {
            LifeOsRuntimeBindingRegistry.install(
                subsystemId = "hot-swap-runtime",
                source = "hotswap-startup-provider",
            )
        } else if (LifeOsRuntimeBindingRegistry.current("hot-swap-runtime") == null) {
            LifeOsRuntimeBindingRegistry.install(
                subsystemId = "hot-swap-runtime",
                state = LifeOsRuntimeBindingState.REGISTERED,
                source = "hotswap-startup-provider",
                detail = "awaiting-generated-tool-runtime",
            )
        }
    }
}
