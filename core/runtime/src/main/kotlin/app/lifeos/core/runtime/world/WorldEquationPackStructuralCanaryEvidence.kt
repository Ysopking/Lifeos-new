package app.lifeos.core.runtime.world

import app.lifeos.core.field.StableFieldIds

enum class WorldEquationPackStructuralCanaryGate {
    INDEPENDENT_CASES,
    PARTITION_COVERAGE,
    DETERMINISTIC_REPLAY,
    CANDIDATE_VALIDITY,
}

enum class WorldEquationPackStructuralCanaryGateStatus {
    PASS,
    FAIL,
    INCONCLUSIVE,
}

enum class WorldEquationPackStructuralCanaryDecision {
    INSUFFICIENT_EVIDENCE,
    CANARY_SUPPORTED,
    REJECTED,
}

data class WorldEquationPackStructuralCanaryProtocol(
    val version: String,
    val minimumIndependentCases: Int,
    val minimumShadowReferences: Int = 1,
    val minimumHoldoutReferences: Int = 1,
) {
    init {
        require(version.isNotBlank())
        require(minimumIndependentCases >= 2)
        require(minimumShadowReferences >= 1)
        require(minimumHoldoutReferences >= 1)
        require(
            minimumShadowReferences + minimumHoldoutReferences <=
                minimumIndependentCases
        )
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "world-equation-pack-structural-canary-protocol/v1",
        version,
        minimumIndependentCases.toString(),
        minimumShadowReferences.toString(),
        minimumHoldoutReferences.toString(),
    )
}

data class WorldEquationPackStructuralCanaryReplay(
    val reference: WorldEquationPackShadowObservation,
    val canary: WorldEquationPackStructuralCanaryObservation,
) {
    init {
        require(reference.caseFingerprint == canary.caseFingerprint) {
            "Structural canary replay must bind the same deterministic case"
        }
        require(reference.candidatePackFingerprint == canary.candidatePackFingerprint) {
            "Structural canary replay candidate mismatch"
        }
    }

    val deterministicMatch: Boolean
        get() = reference.candidate.fingerprint() == canary.metrics.fingerprint()

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "world-equation-pack-structural-canary-replay/v1",
        reference.fingerprint(),
        canary.fingerprint,
    )
}

data class WorldEquationPackStructuralCanaryEvidenceSet(
    val planFingerprint: String,
    val candidatePackFingerprint: String,
    val protocol: WorldEquationPackStructuralCanaryProtocol,
    val replays: List<WorldEquationPackStructuralCanaryReplay>,
) {
    init {
        require(planFingerprint.isNotBlank())
        require(candidatePackFingerprint.isNotBlank())
        require(replays.map { it.reference.caseFingerprint }.distinct().size == replays.size) {
            "Structural canary evidence cannot count a deterministic case twice"
        }
        require(replays.all {
            it.canary.planFingerprint == planFingerprint &&
                it.canary.candidatePackFingerprint == candidatePackFingerprint
        })
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "world-equation-pack-structural-canary-evidence-set/v1",
        planFingerprint,
        candidatePackFingerprint,
        protocol.fingerprint(),
        *replays.map { it.fingerprint() }.sorted().toTypedArray(),
    )
}

data class WorldEquationPackStructuralCanaryGateResult(
    val gate: WorldEquationPackStructuralCanaryGate,
    val status: WorldEquationPackStructuralCanaryGateStatus,
    val detail: String,
) {
    init {
        require(detail.isNotBlank())
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "world-equation-pack-structural-canary-gate-result/v1",
        gate.name,
        status.name,
        detail,
    )
}

data class WorldEquationPackStructuralCanaryAssessment(
    val decision: WorldEquationPackStructuralCanaryDecision,
    val gates: List<WorldEquationPackStructuralCanaryGateResult>,
    val evidenceFingerprint: String,
    val id: String,
) {
    init {
        require(gates.map { it.gate }.distinct().size == gates.size)
        require(evidenceFingerprint.isNotBlank())
        require(id == expectedId())
    }

    val productiveActivationAllowed: Boolean
        get() = false

    val productiveWorldMutationAllowed: Boolean
        get() = false

    val promotionAdmissionAllowed: Boolean
        get() = false

    private fun expectedId(): String =
        "world-equation-pack-structural-canary-assessment:" +
            StableFieldIds.fingerprint(
                "world-equation-pack-structural-canary-assessment/v1",
                decision.name,
                evidenceFingerprint,
                *gates.sortedBy { it.gate.name }
                    .map { it.fingerprint() }
                    .toTypedArray(),
            )

    companion object {
        fun create(
            decision: WorldEquationPackStructuralCanaryDecision,
            gates: List<WorldEquationPackStructuralCanaryGateResult>,
            evidenceFingerprint: String,
        ): WorldEquationPackStructuralCanaryAssessment {
            val canonical = gates.sortedBy { it.gate.name }
            val id = "world-equation-pack-structural-canary-assessment:" +
                StableFieldIds.fingerprint(
                    "world-equation-pack-structural-canary-assessment/v1",
                    decision.name,
                    evidenceFingerprint,
                    *canonical.map { it.fingerprint() }.toTypedArray(),
                )
            return WorldEquationPackStructuralCanaryAssessment(
                decision = decision,
                gates = canonical,
                evidenceFingerprint = evidenceFingerprint,
                id = id,
            )
        }
    }
}

/**
 * Evaluates only reproducibility and validity of the non-productive canary sandbox.
 *
 * CANARY_SUPPORTED means the candidate replayed deterministically across isolated canary scope.
 * It is deliberately not a promotion or activation decision.
 */
class WorldEquationPackStructuralCanaryEvidenceEvaluator {
    fun evaluate(
        evidence: WorldEquationPackStructuralCanaryEvidenceSet,
    ): WorldEquationPackStructuralCanaryAssessment {
        val protocol = evidence.protocol
        val replays = evidence.replays
        val shadowRefs = replays.count {
            it.reference.partition == WorldEquationPackEvidencePartition.SHADOW
        }
        val holdoutRefs = replays.count {
            it.reference.partition == WorldEquationPackEvidencePartition.HOLDOUT
        }
        val mismatches = replays.count { !it.deterministicMatch }
        val invalid = replays.count {
            it.canary.metrics.status == WorldFormulaStatus.INVALID_EQUATION
        }

        val independent = gate(
            WorldEquationPackStructuralCanaryGate.INDEPENDENT_CASES,
            if (replays.size >= protocol.minimumIndependentCases) {
                WorldEquationPackStructuralCanaryGateStatus.PASS
            } else {
                WorldEquationPackStructuralCanaryGateStatus.INCONCLUSIVE
            },
            "cases=" + replays.size + "/" + protocol.minimumIndependentCases,
        )
        val partitions = gate(
            WorldEquationPackStructuralCanaryGate.PARTITION_COVERAGE,
            if (
                shadowRefs >= protocol.minimumShadowReferences &&
                holdoutRefs >= protocol.minimumHoldoutReferences
            ) {
                WorldEquationPackStructuralCanaryGateStatus.PASS
            } else {
                WorldEquationPackStructuralCanaryGateStatus.INCONCLUSIVE
            },
            "shadow=" + shadowRefs + "/" + protocol.minimumShadowReferences +
                ";holdout=" + holdoutRefs + "/" + protocol.minimumHoldoutReferences,
        )
        val replay = gate(
            WorldEquationPackStructuralCanaryGate.DETERMINISTIC_REPLAY,
            when {
                mismatches > 0 -> WorldEquationPackStructuralCanaryGateStatus.FAIL
                replays.size >= protocol.minimumIndependentCases ->
                    WorldEquationPackStructuralCanaryGateStatus.PASS
                else -> WorldEquationPackStructuralCanaryGateStatus.INCONCLUSIVE
            },
            "mismatches=" + mismatches,
        )
        val validity = gate(
            WorldEquationPackStructuralCanaryGate.CANDIDATE_VALIDITY,
            if (invalid == 0) {
                WorldEquationPackStructuralCanaryGateStatus.PASS
            } else {
                WorldEquationPackStructuralCanaryGateStatus.FAIL
            },
            "invalid-candidate-runs=" + invalid,
        )

        val gates = listOf(independent, partitions, replay, validity)
        val decision = when {
            gates.any { it.status == WorldEquationPackStructuralCanaryGateStatus.FAIL } ->
                WorldEquationPackStructuralCanaryDecision.REJECTED
            gates.any { it.status == WorldEquationPackStructuralCanaryGateStatus.INCONCLUSIVE } ->
                WorldEquationPackStructuralCanaryDecision.INSUFFICIENT_EVIDENCE
            else -> WorldEquationPackStructuralCanaryDecision.CANARY_SUPPORTED
        }
        return WorldEquationPackStructuralCanaryAssessment.create(
            decision = decision,
            gates = gates,
            evidenceFingerprint = evidence.fingerprint(),
        )
    }

    private fun gate(
        gate: WorldEquationPackStructuralCanaryGate,
        status: WorldEquationPackStructuralCanaryGateStatus,
        detail: String,
    ): WorldEquationPackStructuralCanaryGateResult =
        WorldEquationPackStructuralCanaryGateResult(gate, status, detail)
}
