package app.lifeos.next

import app.lifeos.core.runtime.topology.LifeOsProcessTopology
import app.lifeos.core.runtime.topology.LifeOsRuntimeBindingRegistry
import app.lifeos.core.runtime.topology.LifeOsRuntimeBindingState
import app.lifeos.core.runtime.topology.SubsystemId
import app.lifeos.core.runtime.topology.SubsystemManifest
import app.lifeos.core.runtime.topology.SubsystemStartupOwner
import app.lifeos.next.kernel.PrivateHotSwapRuntimeRegistry

/** Binds concrete Android process composition stages into the shared core runtime topology. */
internal object LifeOsRuntimeWiring {
    private val lock = Any()
    private val readyOwners = linkedMapOf<SubsystemStartupOwner, String>()

    fun onStageReady(evidence: LifeOsStartupStageEvidence) {
        require(evidence.manifestGraphFingerprint == LifeOsProcessTopology.manifestFingerprint) {
            "Startup evidence belongs to another topology manifest graph"
        }
        evidence.stage.subsystemOwner?.let { owner ->
            synchronized(lock) {
                readyOwners[owner] = sourceFor(evidence.stage)
            }
        }
        installReadyManifests()

        when (evidence.stage) {
            LifeOsStartupStage.KERNEL_GRAPH -> refreshOptionalRuntimes()
            LifeOsStartupStage.RUNTIME_STARTED -> {
                LifeOsRuntimeBindingRegistry.update(
                    subsystemId = SubsystemId("runtime-supervisor"),
                    state = LifeOsRuntimeBindingState.ACTIVE,
                    detail = "kernel-start-requested",
                )
                refreshOptionalRuntimes()
            }
            else -> Unit
        }
    }

    /**
     * Replays canonical graph order after every owner becomes ready. A manifest may bind only after
     * both its process owner and all runtime dependencies are operational. This also resolves cross-
     * owner edges such as decision-trace -> photon-store and goal-resume -> goal-planning.
     */
    private fun installReadyManifests() {
        var progressed: Boolean
        do {
            progressed = false
            LifeOsProcessTopology.canonicalManifestGraph.topologicalOrder.forEach { manifest ->
                if (manifest.startupOwner == SubsystemStartupOwner.OPTIONAL_RUNTIME ||
                    manifest.startupOwner == SubsystemStartupOwner.EXTERNAL_HOST
                ) {
                    return@forEach
                }
                if (LifeOsRuntimeBindingRegistry.current(manifest.id) != null) return@forEach
                val source = synchronized(lock) { readyOwners[manifest.startupOwner] } ?: return@forEach
                if (!dependenciesOperational(manifest)) return@forEach
                LifeOsRuntimeBindingRegistry.install(manifest.id, source = source)
                progressed = true
            }
        } while (progressed)
    }

    fun refreshOptionalRuntimes() {
        val manifest = LifeOsProcessTopology.manifest(SubsystemId("hot-swap-runtime"))
        if (!dependenciesOperational(manifest)) return

        val hotSwap = PrivateHotSwapRuntimeRegistry.current()
        if (hotSwap != null) {
            LifeOsRuntimeBindingRegistry.install(
                subsystemId = manifest.id,
                source = "hotswap-startup-provider",
            )
        } else if (LifeOsRuntimeBindingRegistry.current(manifest.id) == null) {
            LifeOsRuntimeBindingRegistry.install(
                subsystemId = manifest.id,
                state = LifeOsRuntimeBindingState.REGISTERED,
                source = "hotswap-startup-provider",
                detail = "awaiting-generated-tool-runtime",
            )
        }
    }

    private fun dependenciesOperational(manifest: SubsystemManifest): Boolean =
        manifest.dependencies.all { dependency ->
            when (LifeOsRuntimeBindingRegistry.current(dependency)?.state) {
                LifeOsRuntimeBindingState.ACTIVE,
                LifeOsRuntimeBindingState.DEGRADED -> true
                LifeOsRuntimeBindingState.REGISTERED,
                LifeOsRuntimeBindingState.QUARANTINED,
                LifeOsRuntimeBindingState.STOPPED,
                null -> false
            }
        }

    private fun sourceFor(stage: LifeOsStartupStage): String = when (stage) {
        LifeOsStartupStage.SHARED_RESOURCES -> "application:shared-resources"
        LifeOsStartupStage.KERNEL_GRAPH -> "kernel-factory"
        LifeOsStartupStage.DEEP_SEARCH -> "application:deep-search"
        LifeOsStartupStage.SELF_HEALING -> "application:self-healing"
        LifeOsStartupStage.DURABLE_GOALS -> "application:durable-goals"
        LifeOsStartupStage.GOAL_EXECUTION,
        LifeOsStartupStage.KERNEL_BOOT,
        LifeOsStartupStage.COGNITIVE_STATE_READY,
        LifeOsStartupStage.RUNTIME_STARTED -> error("Stage $stage does not own subsystem manifests")
    }

    internal fun clearForTests() = synchronized(lock) {
        readyOwners.clear()
    }
}
