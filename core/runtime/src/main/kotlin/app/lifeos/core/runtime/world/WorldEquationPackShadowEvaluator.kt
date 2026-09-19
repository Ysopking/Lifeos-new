package app.lifeos.core.runtime.world

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.field.world.WorldCoefficientId
import app.lifeos.core.field.world.WorldDimensionValue
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs

enum class WorldEquationPackEvidencePartition {
    SHADOW,
    HOLDOUT,
}

data class WorldEquationPackShadowCase(
    val runId: String,
    val workloadId: String,
    val request: WorldFormulaRequest,
    val providerIdByInputFingerprint: Map<String, String>,
    val partition: WorldEquationPackEvidencePartition = WorldEquationPackEvidencePartition.SHADOW,
) {
    init {
        require(runId.isNotBlank())
        require(workloadId.isNotBlank())
        val inputFingerprints = request.inputs.map { it.fingerprint() }.toSet()
        require(providerIdByInputFingerprint.keys == inputFingerprints) {
            "Structural shadow case must bind every frozen input to exactly one provider"
        }
        require(providerIdByInputFingerprint.values.none { it.isBlank() })
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "world-equation-pack-shadow-case/v1",
        request.config.fingerprint(),
        request.sourceTaskId?.value.orEmpty(),
        request.photonId?.value.orEmpty(),
        *buildList {
            request.inputs.sortedBy { it.fingerprint() }.forEach { input ->
                val fingerprint = input.fingerprint()
                val providerId = providerIdByInputFingerprint.getValue(fingerprint)
                add("input:" + fingerprint + ":" + providerId)
            }
            request.interactions.sortedBy { it.fingerprint() }.forEach {
                add("interaction:" + it.fingerprint())
            }
        }.toTypedArray(),
    )
}

data class WorldEquationPackRunMetrics(
    val status: WorldFormulaStatus,
    val iterationCount: Int,
    val conflictCount: Int,
    val anomalyCount: Int,
    val terminalDelta: Double,
    val activeCoefficientIds: Set<WorldCoefficientId>,
    val materializationFingerprint: String,
    val graphFingerprint: String?,
    val finalStateFingerprint: String?,
) {
    init {
        require(iterationCount >= 0)
        require(conflictCount >= 0)
        require(anomalyCount >= 0)
        require(terminalDelta.isFinite() && terminalDelta in 0.0..1.0)
        require(materializationFingerprint.isNotBlank())
        require(graphFingerprint == null || graphFingerprint.isNotBlank())
        require(finalStateFingerprint == null || finalStateFingerprint.isNotBlank())
        if (status == WorldFormulaStatus.INVALID_EQUATION) {
            require(graphFingerprint == null)
            require(finalStateFingerprint == null)
        }
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "world-equation-pack-run-metrics/v1",
        status.name,
        iterationCount.toString(),
        conflictCount.toString(),
        anomalyCount.toString(),
        java.lang.Double.toHexString(terminalDelta),
        materializationFingerprint,
        graphFingerprint.orEmpty(),
        finalStateFingerprint.orEmpty(),
        *activeCoefficientIds.map { it.value }.sorted().toTypedArray(),
    )
}

data class WorldEquationPackShadowObservation(
    val caseFingerprint: String,
    val runId: String,
    val workloadId: String,
    val partition: WorldEquationPackEvidencePartition,
    val baselinePackFingerprint: String,
    val candidatePackFingerprint: String,
    val baseline: WorldEquationPackRunMetrics,
    val candidate: WorldEquationPackRunMetrics,
) {
    init {
        require(caseFingerprint.isNotBlank())
        require(runId.isNotBlank())
        require(workloadId.isNotBlank())
        require(baselinePackFingerprint.isNotBlank())
        require(candidatePackFingerprint.isNotBlank())
        require(baselinePackFingerprint != candidatePackFingerprint)
    }

    val structuralDifferenceExercised: Boolean
        get() = baseline.materializationFingerprint != candidate.materializationFingerprint

    val productiveActivationAllowed: Boolean
        get() = false

    val productiveWorldMutationAllowed: Boolean
        get() = false

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "world-equation-pack-shadow-observation/v1",
        caseFingerprint,
        runId,
        workloadId,
        partition.name,
        baselinePackFingerprint,
        candidatePackFingerprint,
        baseline.fingerprint(),
        candidate.fingerprint(),
    )
}

fun interface WorldEquationPackShadowRunner {
    suspend fun evaluate(
        baseline: WorldEquationPack,
        candidate: WorldEquationPackCandidate,
        case: WorldEquationPackShadowCase,
    ): WorldEquationPackShadowObservation
}

/**
 * Isolated structural WorldEquationPack evaluator.
 *
 * Each pack materializes its own graph from the same frozen superset of inputs/interactions.
 * Provider selection, node kinds, signal dimensions and interaction-schema restrictions are applied
 * before execution. Both executions are hard-bound to SHADOW scope with a process-local snapshot
 * repository; no ProductiveWorldHead, cognitive snapshot or trigger path is reachable.
 */
class WorldEquationPackShadowEvaluator(
    private val materializer: WorldEquationPackCaseMaterializer = WorldEquationPackCaseMaterializer(),
) : WorldEquationPackShadowRunner {
    override suspend fun evaluate(
        baseline: WorldEquationPack,
        candidate: WorldEquationPackCandidate,
        case: WorldEquationPackShadowCase,
    ): WorldEquationPackShadowObservation {
        require(candidate.baselinePackFingerprint == baseline.fingerprint()) {
            "Structural shadow candidate targets another baseline pack"
        }
        require(candidate.changeKind == WorldEquationPackChangeKind.STRUCTURAL) {
            "Structural shadow evaluation rejects parameter-only candidates"
        }

        val baselineMaterialized = materializer.materialize(baseline, case)
        val candidateMaterialized = materializer.materialize(candidate.candidate, case)
        val baselineMetrics = evaluateMaterialized(baseline, baselineMaterialized)
        val candidateMetrics = evaluateMaterialized(candidate.candidate, candidateMaterialized)

        return WorldEquationPackShadowObservation(
            caseFingerprint = case.fingerprint(),
            runId = case.runId,
            workloadId = case.workloadId,
            partition = case.partition,
            baselinePackFingerprint = baseline.fingerprint(),
            candidatePackFingerprint = candidate.candidate.fingerprint(),
            baseline = baselineMetrics,
            candidate = candidateMetrics,
        )
    }

    private suspend fun evaluateMaterialized(
        pack: WorldEquationPack,
        materialized: WorldEquationPackMaterializedCase,
    ): WorldEquationPackRunMetrics {
        val request = materialized.request ?: return invalidMetrics(
            materialized.materializationFingerprint
        )
        val snapshots = ShadowSnapshotRepository()
        val coordinator = WorldFormulaCoordinator(
            equations = InMemoryWorldEquationRegistry(listOf(pack.equation)),
            snapshots = snapshots,
            executionPolicy = WorldFormulaExecutionPolicy.SHADOW,
        )
        val execution = coordinator.evaluate(request)
        require(execution.scope == WorldFormulaExecutionScope.SHADOW)
        require(!execution.productiveCommitAllowed)
        require(execution.state != WorldFormulaExecutionState.PERSISTENCE_FAILED) {
            "Structural shadow repository persistence failed"
        }
        if (execution.state == WorldFormulaExecutionState.INVALID) {
            return invalidMetrics(materialized.materializationFingerprint)
        }
        val snapshot = requireNotNull(execution.snapshot)
        val active = snapshot.iterations
            .flatMap { it.contributions }
            .filter { abs(it.signedDelta) > ACTIVE_EPSILON }
            .mapTo(linkedSetOf()) { it.coefficientId }

        return WorldEquationPackRunMetrics(
            status = snapshot.status,
            iterationCount = snapshot.iterations.size,
            conflictCount = snapshot.conflicts.size,
            anomalyCount = snapshot.anomalies.size,
            terminalDelta = snapshot.iterations.lastOrNull()?.maxDelta ?: 0.0,
            activeCoefficientIds = active,
            materializationFingerprint = materialized.materializationFingerprint,
            graphFingerprint = snapshot.graphFingerprint,
            finalStateFingerprint = snapshot.finalState.fingerprint(),
        )
    }

    private fun invalidMetrics(materializationFingerprint: String) =
        WorldEquationPackRunMetrics(
            status = WorldFormulaStatus.INVALID_EQUATION,
            iterationCount = 0,
            conflictCount = 0,
            anomalyCount = 1,
            terminalDelta = 1.0,
            activeCoefficientIds = emptySet(),
            materializationFingerprint = materializationFingerprint,
            graphFingerprint = null,
            finalStateFingerprint = null,
        )

    private class ShadowSnapshotRepository : WorldFormulaSnapshotRepository {
        private val mutex = Mutex()
        private val byId = linkedMapOf<String, WorldFormulaSnapshot>()

        override suspend fun save(snapshot: WorldFormulaSnapshot) = mutex.withLock {
            val existing = byId[snapshot.id]
            require(existing == null || existing == snapshot) {
                "Structural shadow WorldFormula snapshot id collision"
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
