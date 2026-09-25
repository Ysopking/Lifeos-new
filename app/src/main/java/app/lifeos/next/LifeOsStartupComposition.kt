package app.lifeos.next

import app.lifeos.core.runtime.topology.LifeOsProcessTopology
import app.lifeos.core.runtime.topology.SubsystemStartupOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Ordered process lifecycle stages exposed to the unified LIFEOS runtime topology. */
internal enum class LifeOsStartupStage(
    val diagnosticCode: String,
    val displayName: String,
    val subsystemOwner: SubsystemStartupOwner? = null,
) {
    SHARED_RESOURCES(
        diagnosticCode = "BOOT-SR-001",
        displayName = "Vault / Shared Resources",
        subsystemOwner = SubsystemStartupOwner.SHARED_RESOURCES,
    ),
    GOAL_EXECUTION(
        diagnosticCode = "BOOT-GE-001",
        displayName = "Goal Execution",
    ),
    KERNEL_GRAPH(
        diagnosticCode = "BOOT-KG-001",
        displayName = "Kernel Graph",
        subsystemOwner = SubsystemStartupOwner.KERNEL_GRAPH,
    ),
    KERNEL_BOOT(
        diagnosticCode = "BOOT-KB-001",
        displayName = "Kernel Boot",
    ),
    COGNITIVE_STATE_READY(
        diagnosticCode = "BOOT-CS-003",
        displayName = "Cognitive State",
    ),
    DEEP_SEARCH(
        diagnosticCode = "BOOT-DS-001",
        displayName = "Deep Search",
        subsystemOwner = SubsystemStartupOwner.DEEP_SEARCH,
    ),
    SELF_HEALING(
        diagnosticCode = "BOOT-SH-001",
        displayName = "Self-Healing",
        subsystemOwner = SubsystemStartupOwner.SELF_HEALING,
    ),
    DURABLE_GOALS(
        diagnosticCode = "BOOT-DG-001",
        displayName = "Durable Goals",
        subsystemOwner = SubsystemStartupOwner.DURABLE_GOALS,
    ),
    RUNTIME_STARTED(
        diagnosticCode = "BOOT-RT-001",
        displayName = "Runtime",
    ),
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
 * Lifecycle diagnostics emitted around each concrete startup action.
 *
 * Completed events remain canonical per startup layer. Failed identifies the exact stage that threw;
 * sibling cancellation is not misreported as a second startup failure.
 */
internal sealed interface LifeOsStartupStageEvent {
    val stage: LifeOsStartupStage
    val layerIndex: Int

    data class Started(
        override val stage: LifeOsStartupStage,
        override val layerIndex: Int,
    ) : LifeOsStartupStageEvent {
        init {
            require(layerIndex >= 0)
        }
    }

    data class Completed(
        val evidence: LifeOsStartupStageEvidence,
        val durationNanos: Long,
    ) : LifeOsStartupStageEvent {
        override val stage: LifeOsStartupStage
            get() = evidence.stage
        override val layerIndex: Int
            get() = evidence.layerIndex

        init {
            require(durationNanos >= 0L)
        }

        val durationMillis: Long
            get() = durationNanos / 1_000_000L
    }

    data class Failed(
        override val stage: LifeOsStartupStage,
        override val layerIndex: Int,
        val diagnosticCode: String,
        val durationNanos: Long,
        val causeType: String,
        val message: String,
    ) : LifeOsStartupStageEvent {
        init {
            require(layerIndex >= 0)
            require(diagnosticCode == stage.diagnosticCode)
            require(durationNanos >= 0L)
            require(causeType.isNotBlank())
            require(message.isNotBlank())
        }

        val durationMillis: Long
            get() = durationNanos / 1_000_000L
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
    val stageObserver: (LifeOsStartupStageEvent) -> Unit = {},
)

internal object LifeOsStartupComposition {
    suspend fun start(hooks: LifeOsStartupHooks) {
        LifeOsStartupStageGraph.layers.forEachIndexed { layerIndex, layer ->
            val completed = executeLayer(layerIndex, layer, hooks)
            completed.sortedBy { it.spec.stage.ordinal }.forEach { execution ->
                hooks.stageObserver(
                    LifeOsStartupStageEvent.Completed(
                        evidence = evidence(execution.spec.stage, layerIndex),
                        durationNanos = execution.durationNanos,
                    )
                )
            }
        }
    }

    private suspend fun executeLayer(
        layerIndex: Int,
        layer: List<LifeOsStartupStageSpec>,
        hooks: LifeOsStartupHooks,
    ): List<StageExecution> {
        val mayParallelize = layer.size > 1 && layer.all { it.parallelSafe }
        return if (mayParallelize) {
            coroutineScope {
                layer.map { spec ->
                    async {
                        executeStage(spec, layerIndex, hooks)
                    }
                }.awaitAll()
            }
        } else {
            layer.map { spec ->
                executeStage(spec, layerIndex, hooks)
            }
        }
    }

    private suspend fun executeStage(
        spec: LifeOsStartupStageSpec,
        layerIndex: Int,
        hooks: LifeOsStartupHooks,
    ): StageExecution {
        hooks.stageObserver(
            LifeOsStartupStageEvent.Started(
                stage = spec.stage,
                layerIndex = layerIndex,
            )
        )
        val startedNanos = System.nanoTime()
        return try {
            actionFor(spec.stage, hooks).invoke()
            StageExecution(
                spec = spec,
                durationNanos = elapsedNanos(startedNanos),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            hooks.stageObserver(
                LifeOsStartupStageEvent.Failed(
                    stage = spec.stage,
                    layerIndex = layerIndex,
                    diagnosticCode = spec.stage.diagnosticCode,
                    durationNanos = elapsedNanos(startedNanos),
                    causeType = error::class.qualifiedName ?: error::class.simpleName ?: "Throwable",
                    message = error.message?.takeIf { it.isNotBlank() }
                        ?: error::class.simpleName
                        ?: "startup-stage-failed",
                )
            )
            throw error
        }
    }

    private fun elapsedNanos(startedNanos: Long): Long =
        (System.nanoTime() - startedNanos).coerceAtLeast(0L)

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
        LifeOsStartupStage.RUNTIME_STARTED -> suspend { Unit }
    }

    private fun evidence(stage: LifeOsStartupStage, layerIndex: Int): LifeOsStartupStageEvidence {
        val manifests = stage.subsystemOwner
            ?.let(LifeOsProcessTopology.canonicalManifestGraph::manifestsFor)
            .orEmpty()
        return LifeOsStartupStageEvidence(
            stage = stage,
            layerIndex = layerIndex,
            manifestGraphFingerprint = LifeOsProcessTopology.manifestFingerprint,
            ownedManifestFingerprints = manifests.map { it.fingerprint },
        )
    }

    private data class StageExecution(
        val spec: LifeOsStartupStageSpec,
        val durationNanos: Long,
    )
}
