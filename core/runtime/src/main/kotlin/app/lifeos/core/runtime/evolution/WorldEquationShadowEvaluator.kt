package app.lifeos.core.runtime.evolution

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.field.world.WorldEquationSpec
import app.lifeos.core.runtime.world.InMemoryWorldEquationRegistry
import app.lifeos.core.runtime.world.WorldFormulaExecution
import app.lifeos.core.runtime.world.WorldFormulaExecutionPolicy
import app.lifeos.core.runtime.world.WorldFormulaExecutionState
import app.lifeos.core.runtime.world.WorldFormulaRequest
import app.lifeos.core.runtime.world.WorldFormulaSnapshot
import app.lifeos.core.runtime.world.WorldFormulaSnapshotLoadReport
import app.lifeos.core.runtime.world.WorldFormulaSnapshotRepository
import app.lifeos.core.runtime.world.WorldFormulaStatus
import app.lifeos.core.runtime.world.WorldFormulaCoordinator
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs

data class WorldEquationShadowCase(
    val runId: String,
    val workloadId: String,
    val request: WorldFormulaRequest,
) {
    init {
        require(runId.isNotBlank())
        require(workloadId.isNotBlank())
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "world-equation-shadow-case/v1",
        workloadId,
        request.observedAt.toString(),
        request.config.fingerprint(),
        request.sourceTaskId?.value.orEmpty(),
        request.photonId?.value.orEmpty(),
        *request.inputs.map { it.fingerprint() }.sorted().toTypedArray(),
        *request.interactions.map { it.fingerprint() }.sorted().toTypedArray(),
    )
}

/**
 * Paired, non-productive WorldEquation evaluator.
 *
 * Baseline and candidate receive the exact same frozen inputs/interactions/configuration. Only the
 * equation version changes. The coordinator is hard-bound to SHADOW execution policy and a
 * process-local repository, so evaluation cannot publish ProductiveWorldHead state, capture
 * CognitiveSnapshots or emit cognitive triggers.
 */
class WorldEquationShadowEvaluator {
    suspend fun evaluate(
        baseline: WorldEquationSpec,
        candidate: WorldEquationSpec,
        case: WorldEquationShadowCase,
    ): WorldEquationShadowObservation {
        require(candidate.version != baseline.version)
        require(candidate.schemaFingerprint() == baseline.schemaFingerprint())
        require(candidate.physicsFingerprint() != baseline.physicsFingerprint())

        val snapshots = ShadowSnapshotRepository()
        val coordinator = WorldFormulaCoordinator(
            equations = InMemoryWorldEquationRegistry(listOf(baseline, candidate)),
            snapshots = snapshots,
            executionPolicy = WorldFormulaExecutionPolicy.SHADOW,
        )
        val baselineExecution = coordinator.evaluate(
            case.request.copy(equationVersion = baseline.version)
        )
        require(
            baselineExecution.state == WorldFormulaExecutionState.COMPLETED &&
                baselineExecution.snapshot != null
        ) {
            "WorldEquation shadow baseline must be a valid completed execution"
        }

        val candidateExecution = coordinator.evaluate(
            case.request.copy(equationVersion = candidate.version)
        )
        require(candidateExecution.state != WorldFormulaExecutionState.PERSISTENCE_FAILED) {
            "WorldEquation shadow repository persistence failed"
        }
        require(baselineExecution.scope == app.lifeos.core.runtime.world.WorldFormulaExecutionScope.SHADOW)
        require(candidateExecution.scope == app.lifeos.core.runtime.world.WorldFormulaExecutionScope.SHADOW)
        require(!baselineExecution.productiveCommitAllowed && !candidateExecution.productiveCommitAllowed)

        return WorldEquationShadowObservation(
            caseFingerprint = case.fingerprint(),
            runId = case.runId,
            workloadId = case.workloadId,
            baselineEquationFingerprint = baseline.fingerprint(),
            candidateEquationFingerprint = candidate.fingerprint(),
            baseline = metrics(baselineExecution),
            candidate = metrics(candidateExecution),
        )
    }

    private fun metrics(
        execution: WorldFormulaExecution,
    ): WorldEquationRunMetrics {
        if (execution.state == WorldFormulaExecutionState.INVALID) {
            return WorldEquationRunMetrics(
                status = WorldFormulaStatus.INVALID_EQUATION,
                iterationCount = 0,
                conflictCount = 0,
                anomalyCount = 1,
                terminalDelta = 1.0,
                activeCoefficientIds = emptySet(),
            )
        }
        val snapshot = requireNotNull(execution.snapshot)
        val activeCoefficients = snapshot.iterations
            .flatMap { it.contributions }
            .filter { abs(it.signedDelta) > ACTIVE_EPSILON }
            .mapTo(linkedSetOf()) { it.coefficientId }
        return WorldEquationRunMetrics(
            status = snapshot.status,
            iterationCount = snapshot.iterations.size,
            conflictCount = snapshot.conflicts.size,
            anomalyCount = snapshot.anomalies.size,
            terminalDelta = snapshot.iterations.lastOrNull()?.maxDelta ?: 0.0,
            activeCoefficientIds = activeCoefficients,
        )
    }

    private class ShadowSnapshotRepository : WorldFormulaSnapshotRepository {
        private val mutex = Mutex()
        private val byId = linkedMapOf<String, WorldFormulaSnapshot>()

        override suspend fun save(snapshot: WorldFormulaSnapshot) = mutex.withLock {
            val existing = byId[snapshot.id]
            require(existing == null || existing == snapshot) {
                "Shadow WorldFormula snapshot id collision"
            }
            byId[snapshot.id] = snapshot
            while (byId.size > MAX_SNAPSHOTS) {
                byId.remove(byId.keys.first())
            }
        }

        override suspend fun load(id: String): WorldFormulaSnapshot? = mutex.withLock {
            byId[id]
        }

        @Suppress("DEPRECATION")
        override suspend fun loadLatest(): WorldFormulaSnapshot? = mutex.withLock {
            byId.values.lastOrNull()
        }

        override suspend fun loadReport(): WorldFormulaSnapshotLoadReport = mutex.withLock {
            WorldFormulaSnapshotLoadReport(
                snapshots = byId.values.toList(),
                unreadableEntries = emptyList(),
            )
        }

        override suspend fun delete(id: String) {
            mutex.withLock { byId.remove(id) }
        }
    }

    private companion object {
        const val MAX_SNAPSHOTS = 4
        const val ACTIVE_EPSILON = 1e-12
    }
}
