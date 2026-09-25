package app.lifeos.next

import app.lifeos.core.runtime.topology.LifeOsProcessTopology
import app.lifeos.core.runtime.topology.SubsystemStartupOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.supervisorScope

internal enum class LifeOsStartupLane {
    CRITICAL,
    WARM,
}

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
    PERSONAL_RUNTIME_WARMUP(
        diagnosticCode = "BOOT-PW-001",
        displayName = "Personal Runtime Warmup",
    ),
}

internal data class LifeOsStartupStageSpec(
    val stage: LifeOsStartupStage,
    val dependencies: Set<LifeOsStartupStage>,
    val parallelSafe: Boolean,
    val lane: LifeOsStartupLane,
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
 * Critical failures still fail fast. Warm failures are attributed to the exact stage but are
 * isolated from sibling warm stages and returned in [LifeOsWarmStartupReport].
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

internal data class LifeOsWarmStartupFailure(
    val stage: LifeOsStartupStage,
    val diagnosticCode: String,
    val message: String,
) {
    init {
        require(diagnosticCode == stage.diagnosticCode)
        require(message.isNotBlank())
    }
}

internal data class LifeOsWarmStartupReport(
    val completedStages: Set<LifeOsStartupStage>,
    val failures: List<LifeOsWarmStartupFailure>,
) {
    init {
        require(completedStages.all { LifeOsStartupStageGraph.laneFor(it) == LifeOsStartupLane.WARM })
        require(failures.all { LifeOsStartupStageGraph.laneFor(it.stage) == LifeOsStartupLane.WARM })
        require(failures == failures.sortedBy { it.stage.ordinal })
    }

    val degraded: Boolean
        get() = failures.isNotEmpty()
}

/**
 * Process-hook DAG. Critical startup reaches RUNTIME_STARTED without waiting for optional warm
 * subsystems. Warm stages depend on that boundary and can be isolated independently.
 */
internal object LifeOsStartupStageGraph {
    val specs: List<LifeOsStartupStageSpec> = listOf(
        LifeOsStartupStageSpec(
            LifeOsStartupStage.SHARED_RESOURCES,
            emptySet(),
            parallelSafe = false,
            lane = LifeOsStartupLane.CRITICAL,
        ),
        LifeOsStartupStageSpec(
            LifeOsStartupStage.GOAL_EXECUTION,
            setOf(LifeOsStartupStage.SHARED_RESOURCES),
            parallelSafe = false,
            lane = LifeOsStartupLane.CRITICAL,
        ),
        LifeOsStartupStageSpec(
            LifeOsStartupStage.KERNEL_GRAPH,
            setOf(LifeOsStartupStage.GOAL_EXECUTION),
            parallelSafe = false,
            lane = LifeOsStartupLane.CRITICAL,
        ),
        LifeOsStartupStageSpec(
            LifeOsStartupStage.KERNEL_BOOT,
            setOf(LifeOsStartupStage.KERNEL_GRAPH),
            parallelSafe = false,
            lane = LifeOsStartupLane.CRITICAL,
        ),
        LifeOsStartupStageSpec(
            LifeOsStartupStage.COGNITIVE_STATE_READY,
            setOf(LifeOsStartupStage.KERNEL_BOOT),
            parallelSafe = false,
            lane = LifeOsStartupLane.CRITICAL,
        ),
        LifeOsStartupStageSpec(
            LifeOsStartupStage.RUNTIME_STARTED,
            setOf(LifeOsStartupStage.COGNITIVE_STATE_READY),
            parallelSafe = false,
            lane = LifeOsStartupLane.CRITICAL,
        ),
        LifeOsStartupStageSpec(
            LifeOsStartupStage.PERSONAL_RUNTIME_WARMUP,
            setOf(LifeOsStartupStage.RUNTIME_STARTED),
            parallelSafe = true,
            lane = LifeOsStartupLane.WARM,
        ),
        LifeOsStartupStageSpec(
            LifeOsStartupStage.DEEP_SEARCH,
            setOf(LifeOsStartupStage.RUNTIME_STARTED),
            parallelSafe = true,
            lane = LifeOsStartupLane.WARM,
        ),
        LifeOsStartupStageSpec(
            LifeOsStartupStage.SELF_HEALING,
            setOf(LifeOsStartupStage.RUNTIME_STARTED),
            parallelSafe = true,
            lane = LifeOsStartupLane.WARM,
        ),
        LifeOsStartupStageSpec(
            LifeOsStartupStage.DURABLE_GOALS,
            setOf(LifeOsStartupStage.RUNTIME_STARTED),
            parallelSafe = true,
            lane = LifeOsStartupLane.WARM,
        ),
    )

    val layers: List<List<LifeOsStartupStageSpec>> = computeLayers(specs)
    private val specsByStage = specs.associateBy { it.stage }

    fun laneFor(stage: LifeOsStartupStage): LifeOsStartupLane =
        specsByStage.getValue(stage).lane

    fun specsFor(lane: LifeOsStartupLane): List<LifeOsStartupStageSpec> =
        specs.filter { it.lane == lane }

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

internal data class LifeOsStartupHooks(
    val installSharedResourceRuntime: suspend () -> Unit,
    val installGoalExecutionRuntime: suspend () -> Unit,
    val createKernel: suspend () -> Unit,
    val startKernel: suspend () -> Unit,
    val requireCognitiveStateReady: suspend () -> Unit,
    val warmPersonalRuntime: suspend () -> Unit,
    val installDeepSearchRuntime: suspend () -> Unit,
    val startSelfHealingRuntime: suspend () -> Unit,
    val installDurableGoalPlanRuntime: suspend () -> Unit,
    val stageObserver: (LifeOsStartupStageEvent) -> Unit = {},
)

internal object LifeOsStartupComposition {
    suspend fun start(hooks: LifeOsStartupHooks) {
        startCritical(hooks)
        startWarm(hooks)
    }

    suspend fun startCritical(hooks: LifeOsStartupHooks) {
        LifeOsStartupStageGraph.layers.forEachIndexed { layerIndex, layer ->
            val critical = layer.filter { it.lane == LifeOsStartupLane.CRITICAL }
            if (critical.isEmpty()) return@forEachIndexed
            val completed = executeCriticalLayer(layerIndex, critical, hooks)
            completed.sortedBy { it.spec.stage.ordinal }.forEach { execution ->
                emitCompleted(execution, layerIndex, hooks)
            }
        }
    }

    suspend fun startWarm(hooks: LifeOsStartupHooks): LifeOsWarmStartupReport {
        val completedWarm = linkedSetOf<LifeOsStartupStage>()
        val failures = mutableListOf<LifeOsWarmStartupFailure>()

        LifeOsStartupStageGraph.layers.forEachIndexed { layerIndex, layer ->
            val warm = layer.filter { it.lane == LifeOsStartupLane.WARM }
            if (warm.isEmpty()) return@forEachIndexed

            val runnable = mutableListOf<LifeOsStartupStageSpec>()
            warm.sortedBy { it.stage.ordinal }.forEach { spec ->
                val blockedWarmDependencies = spec.dependencies
                    .filter {
                        LifeOsStartupStageGraph.laneFor(it) == LifeOsStartupLane.WARM &&
                            it !in completedWarm
                    }
                    .sortedBy { it.ordinal }
                if (blockedWarmDependencies.isEmpty()) {
                    runnable += spec
                } else {
                    val message =
                        "blocked-by:" + blockedWarmDependencies.joinToString(",") { it.name }
                    hooks.stageObserver(
                        LifeOsStartupStageEvent.Failed(
                            stage = spec.stage,
                            layerIndex = layerIndex,
                            diagnosticCode = spec.stage.diagnosticCode,
                            durationNanos = 0L,
                            causeType = "WarmDependencyUnavailable",
                            message = message,
                        )
                    )
                    failures += LifeOsWarmStartupFailure(
                        stage = spec.stage,
                        diagnosticCode = spec.stage.diagnosticCode,
                        message = message,
                    )
                }
            }

            val outcomes = executeWarmLayer(layerIndex, runnable, hooks)
            outcomes.sortedBy { it.spec.stage.ordinal }.forEach { outcome ->
                if (outcome.failure == null) {
                    completedWarm += outcome.spec.stage
                    emitCompleted(
                        StageExecution(outcome.spec, outcome.durationNanos),
                        layerIndex,
                        hooks,
                    )
                } else {
                    failures += LifeOsWarmStartupFailure(
                        stage = outcome.spec.stage,
                        diagnosticCode = outcome.spec.stage.diagnosticCode,
                        message = outcome.failure.message?.takeIf { it.isNotBlank() }
                            ?: outcome.failure::class.simpleName
                            ?: "warm-startup-failed",
                    )
                }
            }
        }

        return LifeOsWarmStartupReport(
            completedStages = completedWarm.toSet(),
            failures = failures.sortedBy { it.stage.ordinal },
        )
    }

    private suspend fun executeCriticalLayer(
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

    private suspend fun executeWarmLayer(
        layerIndex: Int,
        layer: List<LifeOsStartupStageSpec>,
        hooks: LifeOsStartupHooks,
    ): List<WarmStageOutcome> {
        if (layer.isEmpty()) return emptyList()
        return supervisorScope {
            layer.map { spec ->
                async {
                    try {
                        val execution = executeStage(spec, layerIndex, hooks)
                        WarmStageOutcome(
                            spec = spec,
                            durationNanos = execution.durationNanos,
                            failure = null,
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        WarmStageOutcome(
                            spec = spec,
                            durationNanos = 0L,
                            failure = error,
                        )
                    }
                }
            }.awaitAll()
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
            val durationNanos = elapsedNanos(startedNanos)
            hooks.stageObserver(
                LifeOsStartupStageEvent.Failed(
                    stage = spec.stage,
                    layerIndex = layerIndex,
                    diagnosticCode = spec.stage.diagnosticCode,
                    durationNanos = durationNanos,
                    causeType = error::class.qualifiedName ?: error::class.simpleName ?: "Throwable",
                    message = error.message?.takeIf { it.isNotBlank() }
                        ?: error::class.simpleName
                        ?: "startup-stage-failed",
                )
            )
            throw error
        }
    }

    private fun emitCompleted(
        execution: StageExecution,
        layerIndex: Int,
        hooks: LifeOsStartupHooks,
    ) {
        hooks.stageObserver(
            LifeOsStartupStageEvent.Completed(
                evidence = evidence(execution.spec.stage, layerIndex),
                durationNanos = execution.durationNanos,
            )
        )
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
        LifeOsStartupStage.PERSONAL_RUNTIME_WARMUP -> hooks.warmPersonalRuntime
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

    private data class WarmStageOutcome(
        val spec: LifeOsStartupStageSpec,
        val durationNanos: Long,
        val failure: Throwable?,
    )
}
