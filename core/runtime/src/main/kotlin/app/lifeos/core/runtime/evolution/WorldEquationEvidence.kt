package app.lifeos.core.runtime.evolution

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.field.world.WorldCoefficientId
import app.lifeos.core.field.world.WorldEquationSpec
import app.lifeos.core.runtime.world.WorldFormulaStatus
import kotlin.math.abs

enum class WorldEquationLifecycleState {
    CONJECTURE,
    SHADOW,
    SUPPORTED,
    PROMOTABLE,
    ACTIVE,
    QUARANTINED,
    ROLLED_BACK,
    REJECTED,
}

enum class WorldEquationPrimaryMetric {
    STABILIZATION_ITERATIONS,
    CONFLICT_COUNT,
    TERMINAL_DELTA,
    NON_CONVERGED_RATE,
}

enum class WorldEquationEvidencePartition {
    SHADOW,
    HOLDOUT,
}

enum class WorldEquationEvidenceGate {
    PRIMARY_IMPROVEMENT,
    HELD_OUT_VALIDATION,
    STATUS_NON_INFERIORITY,
    REPRODUCIBILITY,
    CROSS_WORKLOAD_TRANSFER,
    IDENTIFIABILITY,
    NON_DEGENERACY,
    SAFETY,
}

enum class WorldEquationGateStatus {
    PASS,
    FAIL,
    INCONCLUSIVE,
}

data class WorldEquationEvaluationProtocol(
    val version: String,
    val primaryMetric: WorldEquationPrimaryMetric,
    val minimumIndependentRuns: Int,
    val minimumDistinctWorkloads: Int,
    val minimumActiveObservationsPerChangedCoefficient: Int,
    val minimumShadowRuns: Int = 1,
    val minimumHoldoutRuns: Int = 1,
) {
    init {
        require(version.isNotBlank())
        require(minimumIndependentRuns >= 2)
        require(minimumDistinctWorkloads >= 1)
        require(minimumDistinctWorkloads <= minimumIndependentRuns)
        require(minimumActiveObservationsPerChangedCoefficient >= 1)
        require(minimumActiveObservationsPerChangedCoefficient <= minimumIndependentRuns)
        require(minimumShadowRuns >= 1)
        require(minimumHoldoutRuns >= 1)
        require(minimumShadowRuns + minimumHoldoutRuns <= minimumIndependentRuns) {
            "WorldEquation protocol must reserve independent evidence for SHADOW and HOLDOUT"
        }
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "world-equation-evaluation-protocol/v2",
        version,
        primaryMetric.name,
        minimumIndependentRuns.toString(),
        minimumDistinctWorkloads.toString(),
        minimumActiveObservationsPerChangedCoefficient.toString(),
        minimumShadowRuns.toString(),
        minimumHoldoutRuns.toString(),
    )
}

data class WorldEquationRunMetrics(
    val status: WorldFormulaStatus,
    val iterationCount: Int,
    val conflictCount: Int,
    val anomalyCount: Int,
    val terminalDelta: Double,
    val activeCoefficientIds: Set<WorldCoefficientId>,
) {
    init {
        require(iterationCount >= 0)
        require(conflictCount >= 0)
        require(anomalyCount >= 0)
        require(terminalDelta.isFinite() && terminalDelta in 0.0..1.0)
    }

    fun metric(metric: WorldEquationPrimaryMetric): Double = when (metric) {
        WorldEquationPrimaryMetric.STABILIZATION_ITERATIONS -> iterationCount.toDouble()
        WorldEquationPrimaryMetric.CONFLICT_COUNT -> conflictCount.toDouble()
        WorldEquationPrimaryMetric.TERMINAL_DELTA -> terminalDelta
        WorldEquationPrimaryMetric.NON_CONVERGED_RATE ->
            if (status == WorldFormulaStatus.CONVERGED) 0.0 else 1.0
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "world-equation-run-metrics/v1",
        status.name,
        iterationCount.toString(),
        conflictCount.toString(),
        anomalyCount.toString(),
        java.lang.Double.toHexString(terminalDelta),
        *activeCoefficientIds.map { it.value }.sorted().toTypedArray(),
    )
}

data class WorldEquationShadowObservation(
    val caseFingerprint: String,
    val runId: String,
    val workloadId: String,
    val baselineEquationFingerprint: String,
    val candidateEquationFingerprint: String,
    val baseline: WorldEquationRunMetrics,
    val candidate: WorldEquationRunMetrics,
    val partition: WorldEquationEvidencePartition = WorldEquationEvidencePartition.SHADOW,
) {
    init {
        require(caseFingerprint.isNotBlank())
        require(runId.isNotBlank())
        require(workloadId.isNotBlank())
        require(baselineEquationFingerprint.isNotBlank())
        require(candidateEquationFingerprint.isNotBlank())
        require(baselineEquationFingerprint != candidateEquationFingerprint)
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "world-equation-shadow-observation/v2",
        caseFingerprint,
        runId,
        workloadId,
        partition.name,
        baselineEquationFingerprint,
        candidateEquationFingerprint,
        baseline.fingerprint(),
        candidate.fingerprint(),
    )

    fun improvement(metric: WorldEquationPrimaryMetric): Double =
        baseline.metric(metric) - candidate.metric(metric)
}

data class WorldEquationEvidenceSet(
    val candidateVersion: String,
    val candidateEquationFingerprint: String,
    val candidatePhysicsFingerprint: String,
    val baselineVersion: String,
    val baselineEquationFingerprint: String,
    val baselinePhysicsFingerprint: String,
    val equationSchemaFingerprint: String,
    val protocol: WorldEquationEvaluationProtocol,
    val policyFingerprint: String,
    val observations: List<WorldEquationShadowObservation>,
) {
    init {
        require(candidateVersion.isNotBlank() && baselineVersion.isNotBlank())
        require(candidateVersion != baselineVersion)
        require(candidateEquationFingerprint.isNotBlank())
        require(candidatePhysicsFingerprint.isNotBlank())
        require(baselineEquationFingerprint.isNotBlank())
        require(baselinePhysicsFingerprint.isNotBlank())
        require(equationSchemaFingerprint.isNotBlank())
        require(policyFingerprint.isNotBlank())
        require(candidateEquationFingerprint != baselineEquationFingerprint)
        require(candidatePhysicsFingerprint != baselinePhysicsFingerprint)
        require(observations.map { it.fingerprint() }.distinct().size == observations.size)
        require(observations.all {
            it.candidateEquationFingerprint == candidateEquationFingerprint &&
                it.baselineEquationFingerprint == baselineEquationFingerprint
        })
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "world-equation-evidence-set/v2",
        candidateVersion,
        candidateEquationFingerprint,
        candidatePhysicsFingerprint,
        baselineVersion,
        baselineEquationFingerprint,
        baselinePhysicsFingerprint,
        equationSchemaFingerprint,
        protocol.fingerprint(),
        policyFingerprint,
        *observations.map { it.fingerprint() }.sorted().toTypedArray(),
    )

    companion object {
        fun empty(
            candidate: WorldEquationSpec,
            baseline: WorldEquationSpec,
            protocol: WorldEquationEvaluationProtocol,
            policyFingerprint: String,
        ): WorldEquationEvidenceSet {
            require(candidate.version != baseline.version)
            require(candidate.physicsFingerprint() != baseline.physicsFingerprint())
            require(candidate.schemaFingerprint() == baseline.schemaFingerprint())
            return WorldEquationEvidenceSet(
                candidateVersion = candidate.version,
                candidateEquationFingerprint = candidate.fingerprint(),
                candidatePhysicsFingerprint = candidate.physicsFingerprint(),
                baselineVersion = baseline.version,
                baselineEquationFingerprint = baseline.fingerprint(),
                baselinePhysicsFingerprint = baseline.physicsFingerprint(),
                equationSchemaFingerprint = candidate.schemaFingerprint(),
                protocol = protocol,
                policyFingerprint = policyFingerprint,
                observations = emptyList(),
            )
        }
    }
}

data class WorldEquationGateResult(
    val gate: WorldEquationEvidenceGate,
    val status: WorldEquationGateStatus,
    val detail: String,
) {
    init {
        require(detail.isNotBlank())
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "world-equation-gate-result/v1",
        gate.name,
        status.name,
        detail,
    )
}

enum class WorldEquationPromotionDecision {
    INSUFFICIENT_EVIDENCE,
    SUPPORTED,
    PROMOTABLE,
    REJECTED,
    QUARANTINED,
}

data class WorldEquationPromotionVerdict(
    val decision: WorldEquationPromotionDecision,
    val gateResults: List<WorldEquationGateResult>,
    val evidenceFingerprint: String,
    val policyFingerprint: String,
    val id: String,
) {
    init {
        require(gateResults.map { it.gate }.distinct().size == gateResults.size)
        require(evidenceFingerprint.isNotBlank())
        require(policyFingerprint.isNotBlank())
        require(id == expectedId())
    }

    private fun expectedId(): String =
        "world-equation-promotion:" + StableFieldIds.fingerprint(
            "world-equation-promotion-verdict/v1",
            decision.name,
            evidenceFingerprint,
            policyFingerprint,
            *gateResults.sortedBy { it.gate.name }.map { it.fingerprint() }.toTypedArray(),
        )

    companion object {
        fun create(
            decision: WorldEquationPromotionDecision,
            gateResults: List<WorldEquationGateResult>,
            evidenceFingerprint: String,
            policyFingerprint: String,
        ): WorldEquationPromotionVerdict {
            val id = "world-equation-promotion:" + StableFieldIds.fingerprint(
                "world-equation-promotion-verdict/v1",
                decision.name,
                evidenceFingerprint,
                policyFingerprint,
                *gateResults.sortedBy { it.gate.name }.map { it.fingerprint() }.toTypedArray(),
            )
            return WorldEquationPromotionVerdict(
                decision = decision,
                gateResults = gateResults.sortedBy { it.gate.name },
                evidenceFingerprint = evidenceFingerprint,
                policyFingerprint = policyFingerprint,
                id = id,
            )
        }
    }
}

internal fun WorldEquationSpec.changedCoefficientIdsComparedWith(
    baseline: WorldEquationSpec,
): Set<WorldCoefficientId> {
    require(schemaFingerprint() == baseline.schemaFingerprint())
    val baselineById = baseline.stableCoefficients().associateBy { it.id }
    return stableCoefficients()
        .filter { candidate ->
            val previous = baselineById.getValue(candidate.id)
            candidate.physicsFingerprint() != previous.physicsFingerprint()
        }
        .mapTo(linkedSetOf()) { it.id }
}

internal fun WorldEquationSpec.absoluteMultiplierNorm(): Double =
    stableCoefficients().sumOf { abs(it.multiplier) }
