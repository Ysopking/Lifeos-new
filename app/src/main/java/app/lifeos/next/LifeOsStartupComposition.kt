package app.lifeos.next

import app.lifeos.core.runtime.topology.LifeOsProcessTopology
import app.lifeos.core.runtime.topology.SubsystemStartupOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

internal enum class StartupLane {
    CRITICAL,
    WARM,
}

internal enum class LifeOsStartupStage(
    val diagnosticCode: String,
    val displayName: String,
    val subsystemOwner: SubsystemStartupOwner? = null,
) {
    SHARED_RESOURCES("BOOT-SR-001", "Vault / Shared Resources", SubsystemStartupOwner.SHARED_RESOURCES),
    GOAL_EXECUTION("BOOT-GE-001", "Goal Execution"),
    KERNEL_GRAPH("BOOT-KG-001", "Kernel Graph", SubsystemStartupOwner.KERNEL_GRAPH),
    KERNEL_BOOT("BOOT-KB-001", "Kernel Boot"),
    COGNITIVE_STATE_READY("BOOT-CS-003", "Cognitive State"),
    UI_READY("BOOT-UI-001", "UI Ready"),
    KERNEL_WARM_RESTORE("BOOT-KW-001", "Kernel Warm Restore"),
    DEEP_SEARCH("BOOT-DS-001", "Deep Search", SubsystemStartupOwner.DEEP_SEARCH),
    SELF_HEALING("BOOT-SH-001", "Self-Healing", SubsystemStartupOwner.SELF_HEALING),
    DURABLE_GOALS("BOOT-DG-001", "Durable Goals", SubsystemStartupOwner.DURABLE_GOALS),
    RUNTIME_STARTED("BOOT-RT-001", "Runtime"),
}

internal data class LifeOsStartupStageSpec(
    val stage: LifeOsStartupStage,
    val dependencies: Set<LifeOsStartupStage>,
    val parallelSafe: Boolean,
    val lane: StartupLane,
)

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

internal sealed interface LifeOsStartupStageEvent {
    val stage: LifeOsStartupStage
    val layerIndex: Int

    data class Started(
        override val stage: LifeOsStartupStage,
        override val layerIndex: Int,
    ) : LifeOsStartupStageEvent {
        init { require(layerIndex >= 0) }
    }

    data class Completed(
        val evidence: LifeOsStartupStageEvidence,
        val durationNanos: Long,
    ) : LifeOsStartupStageEvent {
        override val stage: LifeOsStartupStage get() = evidence.stage
        override val layerIndex: Int get() = evidence.layerIndex

        init { require(durationNanos >= 0L) }

        val durationMillis: Long get() = durationNanos / 1_000_000L
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

        val durationMillis: Long get() = durationNanos / 1_000_000L
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
    val completed: Set<LifeOsStartupStage>,
    val failures: List<LifeOsWarmStartupFailure>,
    val skippedReason: String? = null,
) {
    init {
        require(failures == failures.sortedBy { it.stage.ordinal })
        require(skippedReason == null || skippedReason.isNotBlank())
    }

    val degraded: Boolean get() = failures.isNotEmpty() || skippedReason != null

    companion object {
        fun skipped(reason: String) = LifeOsWarmStartupReport(
            completed = emptySet(),
            failures = emptyList(),
            skippedReason = reason,
        )
    }
}

internal object LifeOsStartupStageGraph {
    val specs: List<LifeOsStartupStageSpec> = listOf(
        spec(LifeOsStartupStage.SHARED_RESOURCES, emptySet(), false, StartupLane.CRITICAL),
        spec(LifeOsStartupStage.GOAL_EXECUTION, setOf(LifeOsStartupStage.SHARED_RESOURCES), false, StartupLane.CRITICAL),
        spec(LifeOsStartupStage.KERNEL_GRAPH, setOf(LifeOsStartupStage.GOAL_EXECUTION), false, StartupLane.CRITICAL),
        spec(LifeOsStartupStage.KERNEL_BOOT, setOf(LifeOsStartupStage.KERNEL_GRAPH), false, StartupLane.CRITICAL),
        spec(LifeOsStartupStage.COGNITIVE_STATE_READY, setOf(LifeOsStartupStage.KERNEL_BOOT), false, StartupLane.CRITICAL),
        spec(LifeOsStartupStage.UI_READY, setOf(LifeOsStartupStage.COGNITIVE_STATE_READY), false, StartupLane.CRITICAL),
        spec(LifeOsStartupStage.KERNEL_WARM_RESTORE, setOf(LifeOsStartupStage.UI_READY), true, StartupLane.WARM),
        spec(LifeOsStartupStage.DEEP_SEARCH, setOf(LifeOsStartupStage.UI_READY), true, StartupLane.WARM),
        spec(LifeOsStartupStage.SELF_HEALING, setOf(LifeOsStartupStage.UI_READY), true, StartupLane.WARM),
        spec(LifeOsStartupStage.DURABLE_GOALS, setOf(LifeOsStartupStage.UI_READY), true, StartupLane.WARM),
        spec(
            LifeOsStartupStage.RUNTIME_STARTED,
            setOf(
                LifeOsStartupStage.KERNEL_WARM_RESTORE,
                LifeOsStartupStage.DEEP_SEARCH,
                LifeOsStartupStage.SELF_HEALING,
                LifeOsStartupStage.DURABLE_GOALS,
            ),
            false,
            StartupLane.WARM,
        ),
    )

    val layers: List<List<LifeOsStartupStageSpec>> = computeLayers(specs)

    fun laneOf(stage: LifeOsStartupStage): StartupLane =
        specs.single { it.stage == stage }.lane

    val criticalStages: Set<LifeOsStartupStage> =
        specs.filter { it.lane == StartupLane.CRITICAL }.mapTo(linkedSetOf()) { it.stage }

    val warmStages: Set<LifeOsStartupStage> =
        specs.filter { it.lane == StartupLane.WARM }.mapTo(linkedSetOf()) { it.stage }

    private fun spec(
        stage: LifeOsStartupStage,
        dependencies: Set<LifeOsStartupStage>,
        parallelSafe: Boolean,
        lane: StartupLane,
    ) = LifeOsStartupStageSpec(stage, dependencies, parallelSafe, lane)

    private fun computeLayers(specs: List<LifeOsStartupStageSpec>): List<List<LifeOsStartupStageSpec>> {
        require(specs.map { it.stage }.distinct().size == specs.size) { "Duplicate startup stage" }
        val known = specs.mapTo(linkedSetOf()) { it.stage }
        specs.forEach { spec ->
            require(spec.stage !in spec.dependencies) { "Startup stage cannot depend on itself: ${spec.stage}" }
            require(known.containsAll(spec.dependencies)) { "Startup stage has an unknown dependency: ${spec.stage}" }
            if (spec.lane == StartupLane.CRITICAL) {
                require(
                    spec.dependencies.none { dependency ->
                        specs.single { it.stage == dependency }.lane == StartupLane.WARM
                    }
                ) {
                    "Critical startup stage cannot depend on warm stage: ${spec.stage}"
                }
            }
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
    val warmKernelRuntime: suspend () -> Unit = {},
    val installDeepSearchRuntime: suspend () -> Unit,
    val startSelfHealingRuntime: suspend () -> Unit,
    val installDurableGoalPlanRuntime: suspend () -> Unit,
    val stageObserver: (LifeOsStartupStageEvent) -> Unit = {},
)

internal object LifeOsStartupComposition {
    suspend fun start(hooks: LifeOsStartupHooks) {
        startCritical(hooks)
        val warm = startWarm(hooks)
        warm.failures.firstOrNull()?.let { throw IllegalStateException(it.message) }
    }

    suspend fun startCritical(hooks: LifeOsStartupHooks) {
        LifeOsStartupStageGraph.layers.forEachIndexed { layerIndex, layer ->
            val critical = layer.filter { it.lane == StartupLane.CRITICAL }
            if (critical.isEmpty()) return@forEachIndexed
            emitCompletions(executeCriticalLayer(layerIndex, critical, hooks), layerIndex, hooks)
        }
    }

    suspend fun startWarm(hooks: LifeOsStartupHooks): LifeOsWarmStartupReport {
        val completed = LifeOsStartupStageGraph.criticalStages.toMutableSet()
        val failed = linkedSetOf<LifeOsStartupStage>()
        val failures = mutableListOf<LifeOsWarmStartupFailure>()

        LifeOsStartupStageGraph.layers.forEachIndexed { layerIndex, layer ->
            val warm = layer.filter { it.lane == StartupLane.WARM }
            if (warm.isEmpty()) return@forEachIndexed

            val blocked = warm.filter { spec -> spec.dependencies.any { it in failed } }
            blocked.sortedBy { it.stage.ordinal }.forEach { spec ->
                val dependencies = spec.dependencies.filter { it in failed }.sortedBy { it.ordinal }
                val message = "dependency-failed:" + dependencies.joinToString(",") { it.name }
                hooks.stageObserver(
                    LifeOsStartupStageEvent.Failed(
                        stage = spec.stage,
                        layerIndex = layerIndex,
                        diagnosticCode = spec.stage.diagnosticCode,
                        durationNanos = 0L,
                        causeType = "DependencyFailure",
                        message = message,
                    )
                )
                failed += spec.stage
                failures += LifeOsWarmStartupFailure(spec.stage, spec.stage.diagnosticCode, message)
            }

            val runnable = warm.filterNot { it in blocked }.filter { spec ->
                spec.dependencies.all { it in completed }
            }
            check(runnable.size + blocked.size == warm.size) {
                "Warm startup layer has unresolved dependencies"
            }

            executeWarmLayer(layerIndex, runnable, hooks)
                .sortedBy { it.spec.stage.ordinal }
                .forEach { outcome ->
                    if (outcome.failure == null) {
                        completed += outcome.spec.stage
                        hooks.stageObserver(
                            LifeOsStartupStageEvent.Completed(
                                evidence = evidence(outcome.spec.stage, layerIndex),
                                durationNanos = outcome.durationNanos,
                            )
                        )
                    } else {
                        failed += outcome.spec.stage
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
            completed = completed.filterTo(linkedSetOf()) { it in LifeOsStartupStageGraph.warmStages },
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
                layer.map { spec -> async { executeCriticalStage(spec, layerIndex, hooks) } }.awaitAll()
            }
        } else {
            layer.map { spec -> executeCriticalStage(spec, layerIndex, hooks) }
        }
    }

    private suspend fun executeWarmLayer(
        layerIndex: Int,
        layer: List<LifeOsStartupStageSpec>,
        hooks: LifeOsStartupHooks,
    ): List<WarmStageOutcome> {
        if (layer.isEmpty()) return emptyList()
        val mayParallelize = layer.size > 1 && layer.all { it.parallelSafe }
        return if (mayParallelize) {
            coroutineScope {
                layer.map { spec -> async { executeWarmStage(spec, layerIndex, hooks) } }.awaitAll()
            }
        } else {
            layer.map { spec -> executeWarmStage(spec, layerIndex, hooks) }
        }
    }

    private suspend fun executeCriticalStage(
        spec: LifeOsStartupStageSpec,
        layerIndex: Int,
        hooks: LifeOsStartupHooks,
    ): StageExecution {
        emitStarted(spec, layerIndex, hooks)
        val started = System.nanoTime()
        return try {
            actionFor(spec.stage, hooks).invoke()
            StageExecution(spec, elapsedNanos(started))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            emitFailed(spec, layerIndex, elapsedNanos(started), error, hooks)
            throw error
        }
    }

    private suspend fun executeWarmStage(
        spec: LifeOsStartupStageSpec,
        layerIndex: Int,
        hooks: LifeOsStartupHooks,
    ): WarmStageOutcome {
        emitStarted(spec, layerIndex, hooks)
        val started = System.nanoTime()
        return try {
            actionFor(spec.stage, hooks).invoke()
            WarmStageOutcome(spec, elapsedNanos(started), null)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val duration = elapsedNanos(started)
            emitFailed(spec, layerIndex, duration, error, hooks)
            WarmStageOutcome(spec, duration, error)
        }
    }

    private fun emitStarted(spec: LifeOsStartupStageSpec, layerIndex: Int, hooks: LifeOsStartupHooks) {
        hooks.stageObserver(LifeOsStartupStageEvent.Started(spec.stage, layerIndex))
    }

    private fun emitFailed(
        spec: LifeOsStartupStageSpec,
        layerIndex: Int,
        durationNanos: Long,
        error: Throwable,
        hooks: LifeOsStartupHooks,
    ) {
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
    }

    private fun emitCompletions(
        executions: List<StageExecution>,
        layerIndex: Int,
        hooks: LifeOsStartupHooks,
    ) {
        executions.sortedBy { it.spec.stage.ordinal }.forEach { execution ->
            hooks.stageObserver(
                LifeOsStartupStageEvent.Completed(
                    evidence = evidence(execution.spec.stage, layerIndex),
                    durationNanos = execution.durationNanos,
                )
            )
        }
    }

    private fun elapsedNanos(started: Long): Long =
        (System.nanoTime() - started).coerceAtLeast(0L)

    private fun actionFor(stage: LifeOsStartupStage, hooks: LifeOsStartupHooks): suspend () -> Unit =
        when (stage) {
            LifeOsStartupStage.SHARED_RESOURCES -> hooks.installSharedResourceRuntime
            LifeOsStartupStage.GOAL_EXECUTION -> hooks.installGoalExecutionRuntime
            LifeOsStartupStage.KERNEL_GRAPH -> hooks.createKernel
            LifeOsStartupStage.KERNEL_BOOT -> hooks.startKernel
            LifeOsStartupStage.COGNITIVE_STATE_READY -> hooks.requireCognitiveStateReady
            LifeOsStartupStage.UI_READY -> suspend { Unit }
            LifeOsStartupStage.KERNEL_WARM_RESTORE -> hooks.warmKernelRuntime
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
