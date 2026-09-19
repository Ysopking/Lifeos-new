package app.lifeos.core.runtime.evolution

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.field.world.WorldEquationSpec
import app.lifeos.core.runtime.world.WorldFormulaStatus

data class WorldEquationPromotionPolicy(
    val version: String,
    val primaryImprovementMargin: Double,
    val statusNonInferiorityMargin: Double,
    val minimumImprovedRunFraction: Double,
    val workloadNonInferiorityMargin: Double,
    val minimumCoefficientNormRatio: Double,
    val maximumSingleParameterDelta: Double = 0.25,
) {
    init {
        require(version.isNotBlank())
        require(primaryImprovementMargin.isFinite() && primaryImprovementMargin >= 0.0)
        require(statusNonInferiorityMargin.isFinite() && statusNonInferiorityMargin in 0.0..1.0)
        require(minimumImprovedRunFraction.isFinite() && minimumImprovedRunFraction in 0.5..1.0)
        require(workloadNonInferiorityMargin.isFinite() && workloadNonInferiorityMargin >= 0.0)
        require(minimumCoefficientNormRatio.isFinite() && minimumCoefficientNormRatio in 0.0..1.0)
        require(maximumSingleParameterDelta.isFinite() && maximumSingleParameterDelta in 0.0..1.0)
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "world-equation-promotion-policy/v1",
        version,
        java.lang.Double.toHexString(primaryImprovementMargin),
        java.lang.Double.toHexString(statusNonInferiorityMargin),
        java.lang.Double.toHexString(minimumImprovedRunFraction),
        java.lang.Double.toHexString(workloadNonInferiorityMargin),
        java.lang.Double.toHexString(minimumCoefficientNormRatio),
        java.lang.Double.toHexString(maximumSingleParameterDelta),
    )

    companion object {
        val V1 = WorldEquationPromotionPolicy(
            version = "world-equation-promotion-policy-v1",
            primaryImprovementMargin = 0.0,
            statusNonInferiorityMargin = 0.0,
            minimumImprovedRunFraction = 0.60,
            workloadNonInferiorityMargin = 0.0,
            minimumCoefficientNormRatio = 0.20,
            maximumSingleParameterDelta = 0.25,
        )
    }
}

class WorldEquationPromotionEvaluator(
    val policy: WorldEquationPromotionPolicy = WorldEquationPromotionPolicy.V1,
) {
    fun evaluate(
        candidate: WorldEquationSpec,
        baseline: WorldEquationSpec,
        evidence: WorldEquationEvidenceSet,
    ): WorldEquationPromotionVerdict {
        require(evidence.policyFingerprint == policy.fingerprint()) {
            "World equation evidence was collected under another promotion policy"
        }
        require(evidence.candidateEquationFingerprint == candidate.fingerprint())
        require(evidence.candidatePhysicsFingerprint == candidate.physicsFingerprint())
        require(evidence.baselineEquationFingerprint == baseline.fingerprint())
        require(evidence.baselinePhysicsFingerprint == baseline.physicsFingerprint())
        require(evidence.equationSchemaFingerprint == candidate.schemaFingerprint())
        require(candidate.schemaFingerprint() == baseline.schemaFingerprint())

        val observations = evidence.observations
        val protocol = evidence.protocol
        val changed = candidate.changedCoefficientIdsComparedWith(baseline)

        val independentRuns = observations.map { it.runId }.distinct().size
        val distinctWorkloads = observations.map { it.workloadId }.distinct().size
        val shadowRuns = observations
            .filter { it.partition == WorldEquationEvidencePartition.SHADOW }
            .map { it.runId }
            .distinct()
            .size
        val holdoutObservations = observations.filter {
            it.partition == WorldEquationEvidencePartition.HOLDOUT
        }
        val holdoutRuns = holdoutObservations.map { it.runId }.distinct().size
        val enoughRuns = independentRuns >= protocol.minimumIndependentRuns
        val enoughWorkloads = distinctWorkloads >= protocol.minimumDistinctWorkloads

        val primary = if (!enoughRuns) {
            result(
                WorldEquationEvidenceGate.PRIMARY_IMPROVEMENT,
                WorldEquationGateStatus.INCONCLUSIVE,
                "independent-runs:" + independentRuns + "/" + protocol.minimumIndependentRuns,
            )
        } else {
            val meanImprovement = observations
                .map { it.improvement(protocol.primaryMetric) }
                .average()
            result(
                WorldEquationEvidenceGate.PRIMARY_IMPROVEMENT,
                if (meanImprovement > policy.primaryImprovementMargin) {
                    WorldEquationGateStatus.PASS
                } else {
                    WorldEquationGateStatus.FAIL
                },
                "mean-improvement=" + hex(meanImprovement) +
                    ";margin=" + hex(policy.primaryImprovementMargin),
            )
        }

        val heldOutValidation = if (
            shadowRuns < protocol.minimumShadowRuns ||
            holdoutRuns < protocol.minimumHoldoutRuns
        ) {
            result(
                WorldEquationEvidenceGate.HELD_OUT_VALIDATION,
                WorldEquationGateStatus.INCONCLUSIVE,
                "shadow-runs=" + shadowRuns + "/" + protocol.minimumShadowRuns +
                    ";holdout-runs=" + holdoutRuns + "/" + protocol.minimumHoldoutRuns,
            )
        } else {
            val holdoutImprovement = holdoutObservations
                .map { it.improvement(protocol.primaryMetric) }
                .average()
            result(
                WorldEquationEvidenceGate.HELD_OUT_VALIDATION,
                if (holdoutImprovement > policy.primaryImprovementMargin) {
                    WorldEquationGateStatus.PASS
                } else {
                    WorldEquationGateStatus.FAIL
                },
                "holdout-mean-improvement=" + hex(holdoutImprovement) +
                    ";margin=" + hex(policy.primaryImprovementMargin),
            )
        }

        val status = if (!enoughRuns) {
            result(
                WorldEquationEvidenceGate.STATUS_NON_INFERIORITY,
                WorldEquationGateStatus.INCONCLUSIVE,
                "independent-runs:" + independentRuns + "/" + protocol.minimumIndependentRuns,
            )
        } else {
            val baselineBad = observations.count {
                it.baseline.status != WorldFormulaStatus.CONVERGED
            }.toDouble() / observations.size
            val candidateBad = observations.count {
                it.candidate.status != WorldFormulaStatus.CONVERGED
            }.toDouble() / observations.size
            val regression = candidateBad - baselineBad
            result(
                WorldEquationEvidenceGate.STATUS_NON_INFERIORITY,
                if (regression <= policy.statusNonInferiorityMargin) {
                    WorldEquationGateStatus.PASS
                } else {
                    WorldEquationGateStatus.FAIL
                },
                "bad-status-regression=" + hex(regression) +
                    ";margin=" + hex(policy.statusNonInferiorityMargin),
            )
        }

        val reproducibility = if (!enoughRuns) {
            result(
                WorldEquationEvidenceGate.REPRODUCIBILITY,
                WorldEquationGateStatus.INCONCLUSIVE,
                "independent-runs:" + independentRuns + "/" + protocol.minimumIndependentRuns,
            )
        } else {
            val fraction = observations.count {
                it.improvement(protocol.primaryMetric) > 0.0
            }.toDouble() / observations.size
            result(
                WorldEquationEvidenceGate.REPRODUCIBILITY,
                if (fraction >= policy.minimumImprovedRunFraction) {
                    WorldEquationGateStatus.PASS
                } else {
                    WorldEquationGateStatus.FAIL
                },
                "improved-run-fraction=" + hex(fraction) +
                    ";minimum=" + hex(policy.minimumImprovedRunFraction),
            )
        }

        val transfer = if (!enoughWorkloads) {
            result(
                WorldEquationEvidenceGate.CROSS_WORKLOAD_TRANSFER,
                WorldEquationGateStatus.INCONCLUSIVE,
                "workloads:" + distinctWorkloads + "/" + protocol.minimumDistinctWorkloads,
            )
        } else {
            val worstMean = observations
                .groupBy { it.workloadId }
                .values
                .minOf { group ->
                    group.map { it.improvement(protocol.primaryMetric) }.average()
                }
            result(
                WorldEquationEvidenceGate.CROSS_WORKLOAD_TRANSFER,
                if (worstMean >= -policy.workloadNonInferiorityMargin) {
                    WorldEquationGateStatus.PASS
                } else {
                    WorldEquationGateStatus.FAIL
                },
                "worst-workload-improvement=" + hex(worstMean) +
                    ";margin=" + hex(policy.workloadNonInferiorityMargin),
            )
        }

        val identifiability = if (changed.isEmpty()) {
            result(
                WorldEquationEvidenceGate.IDENTIFIABILITY,
                WorldEquationGateStatus.FAIL,
                "no-changed-coefficients",
            )
        } else {
            val minimumSeen = changed.minOf { id ->
                observations.count { id in it.candidate.activeCoefficientIds }
            }
            val status = when {
                minimumSeen >= protocol.minimumActiveObservationsPerChangedCoefficient ->
                    WorldEquationGateStatus.PASS
                !enoughRuns -> WorldEquationGateStatus.INCONCLUSIVE
                else -> WorldEquationGateStatus.FAIL
            }
            result(
                WorldEquationEvidenceGate.IDENTIFIABILITY,
                status,
                "minimum-active-observations=" + minimumSeen + "/" +
                    protocol.minimumActiveObservationsPerChangedCoefficient,
            )
        }

        val baselineNorm = baseline.absoluteMultiplierNorm()
        val candidateNorm = candidate.absoluteMultiplierNorm()
        val ratio = if (baselineNorm == 0.0) {
            if (candidateNorm == 0.0) 0.0 else 1.0
        } else {
            candidateNorm / baselineNorm
        }
        val nonDegeneracy = result(
            WorldEquationEvidenceGate.NON_DEGENERACY,
            if (ratio >= policy.minimumCoefficientNormRatio) {
                WorldEquationGateStatus.PASS
            } else {
                WorldEquationGateStatus.FAIL
            },
            "coefficient-norm-ratio=" + hex(ratio) +
                ";minimum=" + hex(policy.minimumCoefficientNormRatio),
        )

        val maximumParameterDelta = candidate.maximumParameterDeltaComparedWith(baseline)
        val boundedChange = result(
            WorldEquationEvidenceGate.BOUNDED_PARAMETER_CHANGE,
            if (maximumParameterDelta <= policy.maximumSingleParameterDelta) {
                WorldEquationGateStatus.PASS
            } else {
                WorldEquationGateStatus.FAIL
            },
            "maximum-parameter-delta=" + hex(maximumParameterDelta) +
                ";maximum=" + hex(policy.maximumSingleParameterDelta),
        )

        val invalidRuns = observations.count {
            it.candidate.status == WorldFormulaStatus.INVALID_EQUATION
        }
        val safety = result(
            WorldEquationEvidenceGate.SAFETY,
            if (invalidRuns == 0) WorldEquationGateStatus.PASS else WorldEquationGateStatus.FAIL,
            "invalid-candidate-runs=" + invalidRuns,
        )

        val gates = listOf(
            primary,
            heldOutValidation,
            status,
            reproducibility,
            transfer,
            identifiability,
            nonDegeneracy,
            boundedChange,
            safety,
        )
        val decision = when {
            gates.any { it.status == WorldEquationGateStatus.FAIL } ->
                WorldEquationPromotionDecision.REJECTED
            gates.any { it.status == WorldEquationGateStatus.INCONCLUSIVE } &&
                primary.status == WorldEquationGateStatus.PASS ->
                WorldEquationPromotionDecision.SUPPORTED
            gates.any { it.status == WorldEquationGateStatus.INCONCLUSIVE } ->
                WorldEquationPromotionDecision.INSUFFICIENT_EVIDENCE
            else -> WorldEquationPromotionDecision.PROMOTABLE
        }
        return WorldEquationPromotionVerdict.create(
            decision = decision,
            gateResults = gates,
            evidenceFingerprint = evidence.fingerprint(),
            policyFingerprint = policy.fingerprint(),
        )
    }

    private fun result(
        gate: WorldEquationEvidenceGate,
        status: WorldEquationGateStatus,
        detail: String,
    ) = WorldEquationGateResult(gate, status, detail)

    private fun hex(value: Double): String = java.lang.Double.toHexString(value)
}
