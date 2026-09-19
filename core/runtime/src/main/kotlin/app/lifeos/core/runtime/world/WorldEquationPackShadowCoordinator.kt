package app.lifeos.core.runtime.world

import app.lifeos.core.field.StableFieldIds

data class WorldEquationPackShadowRunReport(
    val baselinePackFingerprint: String,
    val candidatePackFingerprint: String,
    val preflight: WorldEquationPackStructuralEvidence,
    val evidenceRecord: WorldEquationPackEvidenceRecord,
    val evaluatedCaseFingerprints: List<String>,
    val id: String,
) {
    init {
        require(baselinePackFingerprint.isNotBlank())
        require(candidatePackFingerprint.isNotBlank())
        require(baselinePackFingerprint != candidatePackFingerprint)
        require(preflight.baselinePackFingerprint == baselinePackFingerprint)
        require(preflight.candidatePackFingerprint == candidatePackFingerprint)
        require(evidenceRecord.evidence.baselinePackFingerprint == baselinePackFingerprint)
        require(evidenceRecord.evidence.candidatePackFingerprint == candidatePackFingerprint)
        require(evaluatedCaseFingerprints.distinct().size == evaluatedCaseFingerprints.size)
        require(id == expectedId())
    }

    val productiveActivationAllowed: Boolean
        get() = false

    val productiveWorldMutationAllowed: Boolean
        get() = false

    private fun expectedId(): String =
        "world-equation-pack-shadow-run:" + StableFieldIds.fingerprint(
            "world-equation-pack-shadow-run/v1",
            baselinePackFingerprint,
            candidatePackFingerprint,
            preflight.id,
            evidenceRecord.fingerprint,
            *evaluatedCaseFingerprints.sorted().toTypedArray(),
        )

    companion object {
        fun create(
            baselinePackFingerprint: String,
            candidatePackFingerprint: String,
            preflight: WorldEquationPackStructuralEvidence,
            evidenceRecord: WorldEquationPackEvidenceRecord,
            evaluatedCaseFingerprints: Collection<String>,
        ): WorldEquationPackShadowRunReport {
            val canonicalCases = evaluatedCaseFingerprints.distinct().sorted()
            val id = "world-equation-pack-shadow-run:" + StableFieldIds.fingerprint(
                "world-equation-pack-shadow-run/v1",
                baselinePackFingerprint,
                candidatePackFingerprint,
                preflight.id,
                evidenceRecord.fingerprint,
                *canonicalCases.toTypedArray(),
            )
            return WorldEquationPackShadowRunReport(
                baselinePackFingerprint = baselinePackFingerprint,
                candidatePackFingerprint = candidatePackFingerprint,
                preflight = preflight,
                evidenceRecord = evidenceRecord,
                evaluatedCaseFingerprints = canonicalCases,
                id = id,
            )
        }
    }
}

/**
 * Recovery-safe orchestration for the non-productive structural WorldEquationPack lane.
 *
 * The coordinator persists both immutable packs, freezes preflight/protocol identity, evaluates
 * deterministic cases only under WorldFormula SHADOW policy, and appends evidence through CAS.
 * There is deliberately no admission, ProductiveWorldHead, promote, activate or rollback API here.
 */
class WorldEquationPackShadowCoordinator(
    private val packs: WorldEquationPackRepository,
    private val registry: WorldProjectionRegistrySnapshot,
    private val evidence: WorldEquationPackEvidenceCoordinator,
    private val structuralEvaluator: WorldEquationPackStructuralEvaluator =
        WorldEquationPackStructuralEvaluator(),
    private val shadowRunner: WorldEquationPackShadowRunner =
        WorldEquationPackShadowEvaluator(),
) {
    suspend fun evaluate(
        baseline: WorldEquationPack,
        candidatePack: WorldEquationPack,
        protocol: WorldEquationPackEvaluationProtocol,
        cases: Collection<WorldEquationPackShadowCase>,
    ): WorldEquationPackShadowRunReport {
        require(cases.isNotEmpty()) {
            "Structural shadow evaluation requires at least one frozen case"
        }
        require(cases.map { it.runId }.distinct().size == cases.size) {
            "Structural shadow cases require unique run ids"
        }
        require(cases.map { it.fingerprint() }.distinct().size == cases.size) {
            "Structural shadow cases must be deterministically distinct"
        }

        persistExact(baseline)
        persistExact(candidatePack)

        val durableBaseline = requireNotNull(packs.load(baseline.version)) {
            "Structural baseline pack disappeared after persistence"
        }
        val durableCandidate = requireNotNull(packs.load(candidatePack.version)) {
            "Structural candidate pack disappeared after persistence"
        }
        require(durableBaseline.fingerprint() == baseline.fingerprint()) {
            "Durable structural baseline fingerprint mismatch"
        }
        require(durableCandidate.fingerprint() == candidatePack.fingerprint()) {
            "Durable structural candidate fingerprint mismatch"
        }

        val candidate = WorldEquationPackCandidate.create(
            baseline = durableBaseline,
            candidate = durableCandidate,
        )
        require(candidate.changeKind == WorldEquationPackChangeKind.STRUCTURAL) {
            "Structural shadow coordinator rejects parameter-only candidates"
        }
        val preflight = structuralEvaluator.evaluate(
            baseline = durableBaseline,
            candidate = candidate,
            registry = registry,
        )
        var record = evidence.register(
            baseline = durableBaseline,
            candidate = candidate,
            preflight = preflight,
            protocol = protocol,
        )
        if (preflight.status == WorldEquationPackStructuralStatus.BLOCKED) {
            return WorldEquationPackShadowRunReport.create(
                baselinePackFingerprint = durableBaseline.fingerprint(),
                candidatePackFingerprint = durableCandidate.fingerprint(),
                preflight = preflight,
                evidenceRecord = record,
                evaluatedCaseFingerprints = emptyList(),
            )
        }

        record = evidence.beginShadow(
            baseline = durableBaseline,
            candidate = candidate,
            preflight = preflight,
            protocol = protocol,
        )
        val evaluated = mutableListOf<String>()
        val orderedCases = cases.sortedBy { it.fingerprint() }
        for (case in orderedCases) {
            if (record.state == WorldEquationPackLifecycleState.REJECTED) break
            val caseFingerprint = case.fingerprint()
            if (record.evidence.observations.any { it.caseFingerprint == caseFingerprint }) {
                continue
            }
            val observation = shadowRunner.evaluate(
                baseline = durableBaseline,
                candidate = candidate,
                case = case,
            )
            require(observation.caseFingerprint == caseFingerprint) {
                "Structural shadow runner returned evidence for another case"
            }
            record = evidence.recordObservation(
                baseline = durableBaseline,
                candidate = candidate,
                observation = observation,
            )
            evaluated += caseFingerprint
        }

        return WorldEquationPackShadowRunReport.create(
            baselinePackFingerprint = durableBaseline.fingerprint(),
            candidatePackFingerprint = durableCandidate.fingerprint(),
            preflight = preflight,
            evidenceRecord = record,
            evaluatedCaseFingerprints = evaluated,
        )
    }

    private suspend fun persistExact(pack: WorldEquationPack) {
        packs.putIfAbsent(pack)
        val durable = requireNotNull(packs.load(pack.version)) {
            "WorldEquationPack disappeared after persistence"
        }
        require(durable.fingerprint() == pack.fingerprint()) {
            "Durable WorldEquationPack fingerprint mismatch"
        }
    }
}
