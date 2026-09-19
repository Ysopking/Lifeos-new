package app.lifeos.core.runtime.world

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.field.world.WorldEquationContribution
import app.lifeos.core.field.world.WorldFieldEquation
import app.lifeos.core.field.world.WorldFieldNodeId
import app.lifeos.core.field.world.WorldFieldState
import app.lifeos.core.field.world.WorldFieldVector
import app.lifeos.core.field.world.WorldSignalDimension
import app.lifeos.core.runtime.CognitiveSnapshotRuntimeRegistry
import app.lifeos.core.runtime.cognition.CognitiveTriggerSink
import kotlinx.coroutines.CancellationException

/**
 * Bounded deterministic I02 runtime coordinator. It never mutates input snapshots, never introduces
 * a scalar truth score and emits cognition feedback only after the content-addressed world snapshot
 * has been persisted successfully.
 */
class WorldFormulaCoordinator(
    private val equations: WorldEquationRegistry,
    private val snapshots: WorldFormulaSnapshotRepository,
    private val triggerSink: CognitiveTriggerSink? = null,
    private val triggerPolicy: WorldFormulaTriggerPolicy = DefaultWorldFormulaTriggerPolicy,
    private val captureCognitiveSnapshots: Boolean = true,
    private val executionPolicy: WorldFormulaExecutionPolicy =
        if (captureCognitiveSnapshots) {
            WorldFormulaExecutionPolicy.PRODUCTIVE
        } else {
            WorldFormulaExecutionPolicy.SELF_OBSERVATION
        },
) : WorldFormulaExecutor {
    override val scope: WorldFormulaExecutionScope
        get() = executionPolicy.scope

    override suspend fun evaluate(request: WorldFormulaRequest): WorldFormulaExecution {
        val spec = equations.resolve(request.equationVersion)
            ?: return invalid(request, "missing-equation-version:${request.equationVersion}")
        val graph = request.buildGraph()
        val equation = WorldFieldEquation(spec)
        val validation = equation.validate(graph)
        if (validation.isNotEmpty()) {
            return invalid(request, validation.joinToString(";"))
        }

        val equationFingerprint = spec.fingerprint()
        val graphFingerprint = graph.fingerprint()
        val configFingerprint = request.config.fingerprint()
        val runId = "world-run:${StableFieldIds.fingerprint(
            "world-formula-run/v1",
            request.id,
            graphFingerprint,
            equationFingerprint,
            configFingerprint,
        )}"
        var state = WorldFieldState.initial(graph, equationFingerprint)
        var stableRounds = 0
        val iterations = mutableListOf<WorldFormulaIteration>()
        var status = WorldFormulaStatus.MAX_ITERATIONS

        for (iterationIndex in 1..request.config.maxIterations) {
            val before = state
            val result = equation.evaluate(graph, before)
            state = result.state
            val maxDelta = maxDelta(before, state)
            stableRounds = if (maxDelta <= request.config.epsilon) stableRounds + 1 else 0
            iterations += WorldFormulaIteration(
                index = iterationIndex,
                beforeStateFingerprint = before.fingerprint(),
                afterStateFingerprint = state.fingerprint(),
                maxDelta = maxDelta,
                stableRounds = stableRounds,
                contributions = result.contributions,
            )
            if (stableRounds >= request.config.requiredStableRounds) {
                status = WorldFormulaStatus.CONVERGED
                break
            }
        }

        val conflicts = detectConflicts(
            iterations.lastOrNull()?.contributions.orEmpty(),
            request.config.opposingContributionThreshold,
        )
        val anomalies = buildList {
            conflicts.forEach { conflict ->
                add(
                    WorldFormulaAnomaly(
                        type = WorldFormulaAnomalyType.OPPOSING_INFLUENCES,
                        key = "opposing:${conflict.key}",
                        detail = "opposing typed influences on ${conflict.targetNodeId.value}:${conflict.dimension.name}",
                    )
                )
            }
            if (status == WorldFormulaStatus.MAX_ITERATIONS) {
                add(
                    WorldFormulaAnomaly(
                        type = WorldFormulaAnomalyType.MAX_ITERATIONS,
                        key = "max-iterations:${request.config.maxIterations}",
                        detail = "world formula did not stabilize within configured iteration bound",
                    )
                )
            }
        }
        if (status == WorldFormulaStatus.CONVERGED && conflicts.isNotEmpty()) {
            status = WorldFormulaStatus.UNRESOLVED
        }
        val snapshot = WorldFormulaSnapshot.create(
            runId = runId,
            requestId = request.id,
            equationVersion = request.equationVersion,
            equationFingerprint = equationFingerprint,
            graphFingerprint = graphFingerprint,
            configFingerprint = configFingerprint,
            status = status,
            finalState = state,
            iterations = iterations,
            conflicts = conflicts,
            anomalies = anomalies,
            inputSnapshotFingerprints = request.inputs.mapTo(sortedSetOf()) { it.sourceSnapshotFingerprint },
        )

        try {
            snapshots.save(snapshot)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return WorldFormulaExecution(
                scope = executionPolicy.scope,
                state = WorldFormulaExecutionState.PERSISTENCE_FAILED,
                status = status,
                snapshot = snapshot,
                persisted = false,
                message = "snapshot-persistence-failed:${stableError(error)}",
            )
        }

        if (executionPolicy.captureCognitiveSnapshots) {
            captureCognitiveSnapshotBestEffort(snapshot)
        }
        if (executionPolicy.emitCognitiveTriggers) {
            emitTriggerBestEffort(request, snapshot)
        }
        return WorldFormulaExecution(
            scope = executionPolicy.scope,
            state = WorldFormulaExecutionState.COMPLETED,
            status = status,
            snapshot = snapshot,
            persisted = true,
            message = "world-formula-${status.name.lowercase()}",
        )
    }

    private fun invalid(request: WorldFormulaRequest, detail: String): WorldFormulaExecution =
        WorldFormulaExecution(
            scope = executionPolicy.scope,
            state = WorldFormulaExecutionState.INVALID,
            status = WorldFormulaStatus.INVALID_EQUATION,
            snapshot = null,
            persisted = false,
            message = "invalid-world-formula:${request.id}:${detail.take(240)}",
        )

    private suspend fun captureCognitiveSnapshotBestEffort(
        snapshot: WorldFormulaSnapshot,
    ) {
        try {
            CognitiveSnapshotRuntimeRegistry.capturePersistedWorld(snapshot)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // WorldFormula persistence remains authoritative even if checkpoint capture is unavailable.
        }
    }

    private suspend fun emitTriggerBestEffort(
        request: WorldFormulaRequest,
        snapshot: WorldFormulaSnapshot,
    ) {
        check(executionPolicy.emitCognitiveTriggers) {
            "WorldFormula execution scope does not permit cognitive triggers"
        }
        val sink = triggerSink ?: return
        val trigger = triggerPolicy.triggerFor(request, snapshot) ?: return
        try {
            sink.emit(trigger)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Observation feedback cannot retroactively invalidate a persisted deterministic snapshot.
        }
    }

    private fun maxDelta(before: WorldFieldState, after: WorldFieldState): Double {
        val nodeIds = before.vectors.keys + after.vectors.keys
        return nodeIds.maxOfOrNull { nodeId ->
            val left = before[nodeId]
            val right = after[nodeId]
            val dimensions = left.orEmptyDimensions() + right.orEmptyDimensions()
            dimensions.maxOfOrNull { dimension ->
                val leftValue = left?.get(dimension)
                val rightValue = right?.get(dimension)
                if ((leftValue == null) != (rightValue == null)) {
                    1.0
                } else {
                    maxOf(
                        kotlin.math.abs((leftValue?.value ?: 0.0) - (rightValue?.value ?: 0.0)),
                        kotlin.math.abs((leftValue?.confidence ?: 0.0) - (rightValue?.confidence ?: 0.0)),
                    )
                }
            } ?: 0.0
        } ?: 0.0
    }

    private fun WorldFieldVector?.orEmptyDimensions(): Set<WorldSignalDimension> =
        this?.dimensions().orEmpty()

    private fun detectConflicts(
        contributions: List<WorldEquationContribution>,
        threshold: Double,
    ): List<WorldFormulaConflict> = contributions
        .groupBy { it.targetNodeId to it.targetDimension }
        .mapNotNull { (target, group) ->
            val positive = group.filter { it.signedDelta >= threshold }
            val negative = group.filter { it.signedDelta <= -threshold }
            if (positive.isEmpty() || negative.isEmpty()) return@mapNotNull null
            val nodeId = target.first
            val dimension = target.second
            WorldFormulaConflict(
                key = StableFieldIds.fingerprint(
                    "world-formula-opposition/v1",
                    nodeId.value,
                    dimension.name,
                    *positive.map { it.edgeId.value }.sorted().toTypedArray(),
                    *negative.map { it.edgeId.value }.sorted().toTypedArray(),
                ),
                targetNodeId = nodeId,
                dimension = dimension,
                positiveEdgeIds = positive.mapTo(sortedSetOf()) { it.edgeId.value },
                negativeEdgeIds = negative.mapTo(sortedSetOf()) { it.edgeId.value },
                maxPositive = positive.maxOf { it.signedDelta },
                maxNegativeMagnitude = negative.maxOf { kotlin.math.abs(it.signedDelta) },
            )
        }
        .sortedBy { it.key }

    private fun stableError(error: Exception): String =
        "${error::class.simpleName ?: "Exception"}:${error.message.orEmpty().take(160)}"
}
