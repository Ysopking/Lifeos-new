package app.lifeos.core.runtime.world

import app.lifeos.core.field.StableFieldIds

enum class WorldEquationPackLifecycleState {
    CONJECTURE,
    PREFLIGHT_BLOCKED,
    SHADOW,
    SHADOW_SUPPORTED,
    REJECTED,
}

data class WorldEquationPackEvaluationProtocol(
    val version: String,
    val minimumIndependentRuns: Int,
    val minimumDistinctWorkloads: Int,
    val minimumStructuralExerciseRuns: Int,
    val minimumShadowRuns: Int = 1,
    val minimumHoldoutRuns: Int = 1,
) {
    init {
        require(version.isNotBlank())
        require(minimumIndependentRuns >= 2)
        require(minimumDistinctWorkloads >= 1)
        require(minimumDistinctWorkloads <= minimumIndependentRuns)
        require(minimumStructuralExerciseRuns >= 1)
        require(minimumStructuralExerciseRuns <= minimumIndependentRuns)
        require(minimumShadowRuns >= 1)
        require(minimumHoldoutRuns >= 1)
        require(minimumShadowRuns + minimumHoldoutRuns <= minimumIndependentRuns) {
            "Structural protocol must reserve independent SHADOW and HOLDOUT evidence"
        }
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "world-equation-pack-evaluation-protocol/v1",
        version,
        minimumIndependentRuns.toString(),
        minimumDistinctWorkloads.toString(),
        minimumStructuralExerciseRuns.toString(),
        minimumShadowRuns.toString(),
        minimumHoldoutRuns.toString(),
    )
}

data class WorldEquationPackEvidenceSet(
    val candidatePackVersion: String,
    val candidatePackFingerprint: String,
    val candidateStructuralFingerprint: String,
    val baselinePackVersion: String,
    val baselinePackFingerprint: String,
    val baselineStructuralFingerprint: String,
    val structuralPreflightId: String,
    val registrySnapshotId: String,
    val registryFingerprint: String,
    val protocol: WorldEquationPackEvaluationProtocol,
    val observations: List<WorldEquationPackShadowObservation>,
) {
    init {
        require(candidatePackVersion.isNotBlank())
        require(baselinePackVersion.isNotBlank())
        require(candidatePackVersion != baselinePackVersion)
        require(candidatePackFingerprint.isNotBlank())
        require(baselinePackFingerprint.isNotBlank())
        require(candidatePackFingerprint != baselinePackFingerprint)
        require(candidateStructuralFingerprint.isNotBlank())
        require(baselineStructuralFingerprint.isNotBlank())
        require(candidateStructuralFingerprint != baselineStructuralFingerprint) {
            "Structural evidence requires a structural pack change"
        }
        require(structuralPreflightId.isNotBlank())
        require(registrySnapshotId.isNotBlank())
        require(registryFingerprint.isNotBlank())
        require(observations.map { it.runId }.distinct().size == observations.size) {
            "Structural evidence requires independent run ids"
        }
        require(observations.map { it.caseFingerprint }.distinct().size == observations.size) {
            "Structural evidence cannot count the same deterministic case twice"
        }
        require(observations.all {
            it.candidatePackFingerprint == candidatePackFingerprint &&
                it.baselinePackFingerprint == baselinePackFingerprint
        })
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "world-equation-pack-evidence-set/v1",
        candidatePackVersion,
        candidatePackFingerprint,
        candidateStructuralFingerprint,
        baselinePackVersion,
        baselinePackFingerprint,
        baselineStructuralFingerprint,
        structuralPreflightId,
        registrySnapshotId,
        registryFingerprint,
        protocol.fingerprint(),
        *observations.map { it.fingerprint() }.sorted().toTypedArray(),
    )

    companion object {
        fun empty(
            baseline: WorldEquationPack,
            candidate: WorldEquationPackCandidate,
            preflight: WorldEquationPackStructuralEvidence,
            protocol: WorldEquationPackEvaluationProtocol,
        ): WorldEquationPackEvidenceSet {
            require(candidate.baselinePackFingerprint == baseline.fingerprint())
            require(candidate.changeKind == WorldEquationPackChangeKind.STRUCTURAL)
            require(candidate.candidate.structuralFingerprint() != baseline.structuralFingerprint())
            require(preflight.baselinePackFingerprint == baseline.fingerprint())
            require(preflight.candidatePackFingerprint == candidate.candidate.fingerprint())
            return WorldEquationPackEvidenceSet(
                candidatePackVersion = candidate.candidate.version,
                candidatePackFingerprint = candidate.candidate.fingerprint(),
                candidateStructuralFingerprint = candidate.candidate.structuralFingerprint(),
                baselinePackVersion = baseline.version,
                baselinePackFingerprint = baseline.fingerprint(),
                baselineStructuralFingerprint = baseline.structuralFingerprint(),
                structuralPreflightId = preflight.id,
                registrySnapshotId = preflight.registrySnapshotId,
                registryFingerprint = preflight.registryFingerprint,
                protocol = protocol,
                observations = emptyList(),
            )
        }
    }
}

enum class WorldEquationPackEvidenceGate {
    INDEPENDENT_RUNS,
    PARTITION_COVERAGE,
    WORKLOAD_COVERAGE,
    STRUCTURAL_EXERCISE,
    BASELINE_VALIDITY,
    CANDIDATE_VALIDITY,
}

enum class WorldEquationPackGateStatus {
    PASS,
    FAIL,
    INCONCLUSIVE,
}

data class WorldEquationPackGateResult(
    val gate: WorldEquationPackEvidenceGate,
    val status: WorldEquationPackGateStatus,
    val detail: String,
) {
    init {
        require(detail.isNotBlank())
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "world-equation-pack-gate-result/v1",
        gate.name,
        status.name,
        detail,
    )
}

enum class WorldEquationPackShadowDecision {
    INSUFFICIENT_EVIDENCE,
    SHADOW_SUPPORTED,
    REJECTED,
}

data class WorldEquationPackShadowAssessment(
    val decision: WorldEquationPackShadowDecision,
    val gateResults: List<WorldEquationPackGateResult>,
    val evidenceFingerprint: String,
    val id: String,
) {
    init {
        require(gateResults.map { it.gate }.distinct().size == gateResults.size)
        require(evidenceFingerprint.isNotBlank())
        require(id == expectedId())
    }

    val productiveActivationAllowed: Boolean
        get() = false

    val promotionAuthorityAllowed: Boolean
        get() = false

    private fun expectedId(): String =
        "world-equation-pack-shadow-assessment:" + StableFieldIds.fingerprint(
            "world-equation-pack-shadow-assessment/v1",
            decision.name,
            evidenceFingerprint,
            *gateResults.sortedBy { it.gate.name }.map { it.fingerprint() }.toTypedArray(),
        )

    companion object {
        fun create(
            decision: WorldEquationPackShadowDecision,
            gateResults: List<WorldEquationPackGateResult>,
            evidenceFingerprint: String,
        ): WorldEquationPackShadowAssessment {
            val canonical = gateResults.sortedBy { it.gate.name }
            val id = "world-equation-pack-shadow-assessment:" + StableFieldIds.fingerprint(
                "world-equation-pack-shadow-assessment/v1",
                decision.name,
                evidenceFingerprint,
                *canonical.map { it.fingerprint() }.toTypedArray(),
            )
            return WorldEquationPackShadowAssessment(
                decision = decision,
                gateResults = canonical,
                evidenceFingerprint = evidenceFingerprint,
                id = id,
            )
        }
    }
}

/**
 * Structural evidence is intentionally evaluated with coverage/safety gates only.
 *
 * Passing these gates means the changed topology has survived isolated SHADOW/HOLDOUT execution
 * with actual structural exercise. It does not create a promotion admission and cannot activate a
 * WorldEquationPack.
 */
class WorldEquationPackEvidenceEvaluator {
    fun evaluate(
        evidence: WorldEquationPackEvidenceSet,
    ): WorldEquationPackShadowAssessment {
        val protocol = evidence.protocol
        val observations = evidence.observations
        val shadowRuns = observations.count {
            it.partition == WorldEquationPackEvidencePartition.SHADOW
        }
        val holdoutRuns = observations.count {
            it.partition == WorldEquationPackEvidencePartition.HOLDOUT
        }
        val workloads = observations.map { it.workloadId }.toSet().size
        val structuralExerciseRuns = observations.count { it.structuralDifferenceExercised }

        val independentRuns = gate(
            WorldEquationPackEvidenceGate.INDEPENDENT_RUNS,
            if (observations.size >= protocol.minimumIndependentRuns) {
                WorldEquationPackGateStatus.PASS
            } else {
                WorldEquationPackGateStatus.INCONCLUSIVE
            },
            "runs=" + observations.size + "/" + protocol.minimumIndependentRuns,
        )
        val partitionCoverage = gate(
            WorldEquationPackEvidenceGate.PARTITION_COVERAGE,
            if (
                shadowRuns >= protocol.minimumShadowRuns &&
                holdoutRuns >= protocol.minimumHoldoutRuns
            ) {
                WorldEquationPackGateStatus.PASS
            } else {
                WorldEquationPackGateStatus.INCONCLUSIVE
            },
            "shadow=" + shadowRuns + "/" + protocol.minimumShadowRuns +
                ";holdout=" + holdoutRuns + "/" + protocol.minimumHoldoutRuns,
        )
        val workloadCoverage = gate(
            WorldEquationPackEvidenceGate.WORKLOAD_COVERAGE,
            if (workloads >= protocol.minimumDistinctWorkloads) {
                WorldEquationPackGateStatus.PASS
            } else {
                WorldEquationPackGateStatus.INCONCLUSIVE
            },
            "workloads=" + workloads + "/" + protocol.minimumDistinctWorkloads,
        )
        val structuralExercise = gate(
            WorldEquationPackEvidenceGate.STRUCTURAL_EXERCISE,
            if (structuralExerciseRuns >= protocol.minimumStructuralExerciseRuns) {
                WorldEquationPackGateStatus.PASS
            } else if (observations.size >= protocol.minimumIndependentRuns) {
                WorldEquationPackGateStatus.FAIL
            } else {
                WorldEquationPackGateStatus.INCONCLUSIVE
            },
            "exercised=" + structuralExerciseRuns + "/" +
                protocol.minimumStructuralExerciseRuns,
        )

        val invalidBaseline = observations.count {
            it.baseline.status == WorldFormulaStatus.INVALID_EQUATION
        }
        val baselineValidity = gate(
            WorldEquationPackEvidenceGate.BASELINE_VALIDITY,
            if (invalidBaseline == 0) {
                WorldEquationPackGateStatus.PASS
            } else {
                WorldEquationPackGateStatus.FAIL
            },
            "invalid-baseline-runs=" + invalidBaseline,
        )
        val invalidCandidate = observations.count {
            it.candidate.status == WorldFormulaStatus.INVALID_EQUATION
        }
        val candidateValidity = gate(
            WorldEquationPackEvidenceGate.CANDIDATE_VALIDITY,
            if (invalidCandidate == 0) {
                WorldEquationPackGateStatus.PASS
            } else {
                WorldEquationPackGateStatus.FAIL
            },
            "invalid-candidate-runs=" + invalidCandidate,
        )

        val gates = listOf(
            independentRuns,
            partitionCoverage,
            workloadCoverage,
            structuralExercise,
            baselineValidity,
            candidateValidity,
        )
        val decision = when {
            gates.any { it.status == WorldEquationPackGateStatus.FAIL } ->
                WorldEquationPackShadowDecision.REJECTED
            gates.any { it.status == WorldEquationPackGateStatus.INCONCLUSIVE } ->
                WorldEquationPackShadowDecision.INSUFFICIENT_EVIDENCE
            else -> WorldEquationPackShadowDecision.SHADOW_SUPPORTED
        }
        return WorldEquationPackShadowAssessment.create(
            decision = decision,
            gateResults = gates,
            evidenceFingerprint = evidence.fingerprint(),
        )
    }

    private fun gate(
        gate: WorldEquationPackEvidenceGate,
        status: WorldEquationPackGateStatus,
        detail: String,
    ) = WorldEquationPackGateResult(gate, status, detail)
}
