package app.lifeos.next

import app.lifeos.core.runtime.topology.LifeOsProcessTopology
import app.lifeos.core.runtime.topology.SubsystemStartupOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking

/** Ordered process lifecycle stages exposed to the unified LIFEOS runtime topology. */
internal enum class LifeOsStartupStage(
    val subsystemOwner: SubsystemStartupOwner? = null,
) {
    SHARED_RESOURCES(SubsystemStartupOwner.SHARED_RESOURCES),
    GOAL_EXECUTION,
    KERNEL_GRAPH(SubsystemStartupOwner.KERNEL_GRAPH),
    KERNEL_BOOT,
    COGNITIVE_STATE_READY,
    DEEP_SEARCH(SubsystemStartupOwner.DEEP_SEARCH),
    SELF_HEALING(SubsystemStartupOwner.SELF_HEALING),
    DURABLE_GOALS(SubsystemStartupOwner.DURABLE_GOALS),
    RUNTIME_STARTED,
}

internal data class LifeOsStartupStageSpec(
    val stage: LifeOsStartupStage,
    val dependencies: Set<LifeOsStartupStage>,
    val parallelSafe: Boolean,
)

/** Deterministic evidence emitted only after one startup action completed successfully. */
internal data class LifeOsStartupStageEvidence(
    val stage: LifeOsStartupStage,
    val layerIndex: Int,
    val manifestGraphFingerprint: String,
    val ownedManifestFingerprints: List<String>,
) {
    init {
        require(layerIndex >= 0)
        require(manifestGraphFingerprint.isNotBlank())
        require(ownedManifestFingerprints.none { it.isBlank() })
    }
}

/**
 * Process-hook DAG. Subsystem dependency truth remains in the core manifest graph; these lifecycle
 * gates model only construction prerequisites that cannot be inferred from runtime subsystem edges.
 */
internal object LifeOsStartupStageGraph {
    val specs: List<LifeOsStartupStageSpec> = listOf(
        LifeOsStartupStageSpec(LifeOsStartupStage.SHARED_RESOURCES, emptySet(), parallelSafe = false),
        LifeOsStartupStageSpec(
            LifeOsStartupStage.GOAL_EXECUTION,
            setOf(LifeOsStartupStage.SHARED_RESOURCES),
            parallelSafe = false,
        ),
        LifeOsStartupStageSpec(
            LifeOsStartupStage.KERNEL_GRAPH,
            setOf(LifeOsStartupStage.GOAL_EXECUTION),
            parallelSafe = false,
        ),
        LifeOsStartupStageSpec(
            LifeOsStartupStage.KERNEL_BOOT,
            setOf(LifeOsStartupStage.KERNEL_GRAPH),
            parallelSafe = false,
        ),
        LifeOsStartupStageSpec(
            LifeOsStartupStage.COGNITIVE_STATE_READY,
            setOf(LifeOsStartupStage.KERNEL_BOOT),
            parallelSafe = false,
        ),
        LifeOsStartupStageSpec(
            LifeOsStartupStage.DEEP_SEARCH,
            setOf(LifeOsStartupStage.COGNITIVE_STATE_READY),
            parallelSafe = true,
        ),
        LifeOsStartupStageSpec(
            LifeOsStartupStage.SELF_HEALING,
            setOf(LifeOsStartupStage.COGNITIVE_STATE_READY),
            parallelSafe = true,
        ),
        LifeOsStartupStageSpec(
            LifeOsStartupStage.DURABLE_GOALS,
            setOf(LifeOsStartupStage.COGNITIVE_STATE_READY),
            parallelSafe = true,
        ),
        LifeOsStartupStageSpec(
            LifeOsStartupStage.RUNTIME_STARTED,
            setOf(
                LifeOsStartupStage.DEEP_SEARCH,
                LifeOsStartupStage.SELF_HEALING,
                LifeOsStartupStage.DURABLE_GOALS,
            ),
            parallelSafe = false,
        ),
    )

    val layers: List<List<LifeOsStartupStageSpec>> = computeLayers(specs)

    private fun computeLayers(specs: List<LifeOsStartupStageSpec>): List<List<LifeOsStartupStageSpec>> {
        require(specs.map { it.stage }.distinct().size == specs.size) { "Duplicate startup stage" }
        val known = specs.mapTo(linkedSetOf()) { it.stage }
        specs.forEach { spec ->
            require(spec.stage !in spec.dependencies) { "Startup stage cannot depend on itself: ${spec.stage}" }
            require(known.containsAll(spec.dependencies)) { "Startup stage has an unknown dependency: ${spec.stage}" }
        }
        val remaining = specs.associateBy { it.stage }.toMutableMap()
        val resolved = linkedSetOf<LifeOsStartupStage>()
        val layers = mutableListOf<List<LifeOsStartupStageSpec>>()
        while (remaining.isNotEmpty()) {
            val ready = remaining.values
                .filter { resolved.containsAll(it.dependencies) }
                .sortedBy { it.stage.ordinal }
            require(ready.isNotEmpty()) { "Cyclic LIFEOS startup stage dependencies" }
            layers += ready
            ready.forEach { spec ->
                resolved += spec.stage
                remaining -= spec.stage
            }
        }
        return layers
    }
}

/**
 * JVM-safe startup seam for the process composition owned by [LifeOsApplication].
 *
 * The hooks deliberately contain no Android types. Independent post-kernel stages may execute in
 * parallel, while successful completion evidence is always emitted in canonical stage order.
 */
internal data class LifeOsStartupHooks(
    val installSharedResourceRuntime: suspend () -> Unit,
    val installGoalExecutionRuntime: suspend () -> Unit,
    val createKernel: suspend () -> Unit,
    val startKernel: suspend () -> Unit,
    val requireCognitiveStateReady: suspend () -> Unit,
    val installDeepSearchRuntime: suspend () -> Unit,
    val startSelfHealingRuntime: suspend () -> Unit,
    val installDurableGoalPlanRuntime: suspend () -> Unit,
    val stageObserver: (LifeOsStartupStageEvidence) -> Unit = {},
)

internal object LifeOsStartupComposition {
    fun start(hooks: LifeOsStartupHooks) = runBlocking {
        LifeOsStartupStageGraph.layers.forEachIndexed { layerIndex, layer ->
            val completed = executeLayer(layer, hooks)
            completed.sortedBy { it.stage.ordinal }.forEach { spec ->
                hooks.stageObserver(evidence(spec.stage, layerIndex))
            }
        }
    }

    private suspend fun executeLayer(
        layer: List<LifeOsStartupStageSpec>,
        hooks: LifeOsStartupHooks,
    ): List<LifeOsStartupStageSpec> {
        val mayParallelize = layer.size > 1 && layer.all { it.parallelSafe }
        return if (mayParallelize) {
            coroutineScope {
                layer.map { spec ->
                    async(Dispatchers.Default) {
                        actionFor(spec.stage, hooks).invoke()
                        spec
                    }
                }.awaitAll()
            }
        } else {
            layer.map { spec ->
                actionFor(spec.stage, hooks).invoke()
                spec
            }
        }
    }

    private fun actionFor(
        stage: LifeOsStartupStage,
        hooks: LifeOsStartupHooks,
    ): suspend () -> Unit = when (stage) {
        LifeOsStartupStage.SHARED_RESOURCES -> hooks.installSharedResourceRuntime
        LifeOsStartupStage.GOAL_EXECUTION -> hooks.installGoalExecutionRuntime
        LifeOsStartupStage.KERNEL_GRAPH -> hooks.createKernel
        LifeOsStartupStage.KERNEL_BOOT -> hooks.startKernel
        LifeOsStartupStage.COGNITIVE_STATE_READY -> hooks.requireCognitiveStateReady
        LifeOsStartupStage.DEEP_SEARCH -> hooks.installDeepSearchRuntime
        LifeOsStartupStage.SELF_HEALING -> hooks.startSelfHealingRuntime
        LifeOsStartupStage.DURABLE_GOALS -> hooks.installDurableGoalPlanRuntime
        LifeOsStartupStage.RUNTIME_STARTED -> {}
    }

    private fun evidence(stage: LifeOsStartupStage, layerIndex: Int): LifeOsStartupStageEvidence {
        val manifests = stage.subsystemOwner?.let(LifeOsProcessTopology.canonicalManifestGraph::manifestsFor).orEmpty()
        return LifeOsStartupStageEvidence(
            stage = stage,
            layerIndex = layerIndex,
            manifestGraphFingerprint = LifeOsProcessTopology.manifestFingerprint,
            ownedManifestFingerprints = manifests.map { it.fingerprint },
        )
    }
}
