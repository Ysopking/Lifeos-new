package app.lifeos.core.runtime.life

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.StableCognitiveIds
import kotlin.math.abs

enum class DomainEvidenceStance {
    SUPPORTS,
    CONTRADICTS,
    UNCERTAIN,
}

data class DomainEvidenceAssertion(
    val factId: String,
    val evidenceFingerprint: String,
    val kind: DomainFactKind,
    val normalizedValue: String,
    val stance: DomainEvidenceStance,
    val confidence: Double,
    val sourcePhotonId: PhotonId,
    val sourceRevision: Long,
    val evidence: String,
) {
    init {
        require(factId.startsWith("fact:")) { "factId must use the fact: namespace" }
        require(evidenceFingerprint.matches(Regex("[0-9a-f]{64}"))) {
            "evidenceFingerprint must be a SHA-256 hex digest"
        }
        require(normalizedValue.isNotBlank())
        require(confidence.isFinite() && confidence in 0.0..1.0)
        require(sourceRevision > 0)
        require(evidence.isNotBlank())
    }
}

object DomainEvidenceIdentity {
    fun assertion(source: Photon, fact: DomainFact): DomainEvidenceAssertion {
        val normalizedValue = normalizeValue(fact.value)
        val factId = "fact:" + StableCognitiveIds.fingerprint(
            "domain-fact/v1",
            fact.kind.name,
            normalizedValue,
        )
        val evidence = compact(fact.evidence)
        val evidenceFingerprint = StableCognitiveIds.fingerprint(
            "domain-evidence/v1",
            source.id.value,
            source.revision.toString(),
            fact.kind.name,
            normalizedValue,
            evidence,
        )
        return DomainEvidenceAssertion(
            factId = factId,
            evidenceFingerprint = evidenceFingerprint,
            kind = fact.kind,
            normalizedValue = normalizedValue,
            stance = stance(source),
            confidence = fact.confidence,
            sourcePhotonId = source.id,
            sourceRevision = source.revision,
            evidence = evidence,
        )
    }

    private fun stance(source: Photon): DomainEvidenceStance = when {
        source.tags.any { it == "evidence:contradicts" || it == "stance:contradicts" } ->
            DomainEvidenceStance.CONTRADICTS
        source.tags.any { it == "evidence:uncertain" || it == "stance:uncertain" } ->
            DomainEvidenceStance.UNCERTAIN
        source.tags.any { it == "evidence:supports" || it == "stance:supports" } ->
            DomainEvidenceStance.SUPPORTS
        containsAny(source.content, UNCERTAINTY_CUES) -> DomainEvidenceStance.UNCERTAIN
        containsAny(source.content, CONTRADICTION_CUES) -> DomainEvidenceStance.CONTRADICTS
        else -> DomainEvidenceStance.SUPPORTS
    }

    private fun normalizeValue(value: String): String = value
        .lowercase()
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(1024)

    private fun compact(value: String): String = value
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(1024)

    private fun containsAny(value: String, terms: Set<String>): Boolean {
        val lower = value.lowercase()
        return terms.any(lower::contains)
    }

    private val CONTRADICTION_CUES = setOf(
        "widerspricht",
        "widerlegt",
        "ist falsch",
        "trifft nicht zu",
        "not true",
        "contradicts",
        "refutes",
    )
    private val UNCERTAINTY_CUES = setOf(
        "unklar",
        "unbestätigt",
        "unsicher",
        "vermutlich",
        "möglicherweise",
        "unknown",
        "uncertain",
        "possibly",
    )
}

enum class DomainEvidenceConvergenceStatus {
    CONFIRMED,
    REJECTED,
    UNRESOLVED,
}

data class DomainEvidenceConvergenceResult(
    val factId: String,
    val status: DomainEvidenceConvergenceStatus,
    val supportConfidence: Double,
    val contradictionConfidence: Double,
    val uncertaintyConfidence: Double,
    val evidenceFingerprints: List<String>,
    val canonicalFingerprint: String,
    val boundedOut: Boolean,
    val totalEvidenceCount: Int,
) {
    init {
        require(factId.startsWith("fact:"))
        require(supportConfidence in 0.0..1.0)
        require(contradictionConfidence in 0.0..1.0)
        require(uncertaintyConfidence in 0.0..1.0)
        require(evidenceFingerprints == evidenceFingerprints.distinct().sorted())
        require(canonicalFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(totalEvidenceCount >= evidenceFingerprints.size)
    }
}

/**
 * Deterministic, bounded convergence for parallel domain evidence.
 * Conflicting evidence is preserved and resolves to UNRESOLVED unless one side has a decisive
 * confidence margin. Inputs above the configured evidence budget never yield a positive/negative
 * assertion; they remain UNRESOLVED so truncation cannot silently create certainty.
 */
class DomainEvidenceConvergenceEngine(
    private val maxEvidence: Int = 64,
    private val minimumDecisiveConfidence: Double = 0.60,
    private val decisiveMargin: Double = 0.20,
) {
    init {
        require(maxEvidence in 1..4096)
        require(minimumDecisiveConfidence in 0.0..1.0)
        require(decisiveMargin.isFinite() && decisiveMargin in 0.0..1.0)
    }

    fun converge(assertions: Collection<DomainEvidenceAssertion>): DomainEvidenceConvergenceResult {
        require(assertions.isNotEmpty()) { "Domain evidence convergence requires evidence" }
        val factIds = assertions.map { it.factId }.distinct()
        require(factIds.size == 1) { "All evidence must refer to the same fact identity" }

        val canonicalAll = assertions
            .distinctBy { it.evidenceFingerprint }
            .sortedBy { it.evidenceFingerprint }
        val boundedOut = canonicalAll.size > maxEvidence
        val canonical = canonicalAll.take(maxEvidence)
        val support = canonical.filter { it.stance == DomainEvidenceStance.SUPPORTS }
            .maxOfOrNull { it.confidence } ?: 0.0
        val contradiction = canonical.filter { it.stance == DomainEvidenceStance.CONTRADICTS }
            .maxOfOrNull { it.confidence } ?: 0.0
        val uncertainty = canonical.filter { it.stance == DomainEvidenceStance.UNCERTAIN }
            .maxOfOrNull { it.confidence } ?: 0.0

        val status = when {
            boundedOut -> DomainEvidenceConvergenceStatus.UNRESOLVED
            support >= minimumDecisiveConfidence && support - contradiction >= decisiveMargin ->
                DomainEvidenceConvergenceStatus.CONFIRMED
            contradiction >= minimumDecisiveConfidence && contradiction - support >= decisiveMargin ->
                DomainEvidenceConvergenceStatus.REJECTED
            support > 0.0 && contradiction > 0.0 && abs(support - contradiction) < decisiveMargin ->
                DomainEvidenceConvergenceStatus.UNRESOLVED
            else -> DomainEvidenceConvergenceStatus.UNRESOLVED
        }

        val fingerprints = canonical.map { it.evidenceFingerprint }
        val canonicalFingerprint = StableCognitiveIds.fingerprint(
            "domain-evidence-convergence/v1",
            factIds.single(),
            status.name,
            java.lang.Double.toHexString(support),
            java.lang.Double.toHexString(contradiction),
            java.lang.Double.toHexString(uncertainty),
            boundedOut.toString(),
            canonicalAll.size.toString(),
            *fingerprints.toTypedArray(),
        )
        return DomainEvidenceConvergenceResult(
            factId = factIds.single(),
            status = status,
            supportConfidence = support,
            contradictionConfidence = contradiction,
            uncertaintyConfidence = uncertainty,
            evidenceFingerprints = fingerprints,
            canonicalFingerprint = canonicalFingerprint,
            boundedOut = boundedOut,
            totalEvidenceCount = canonicalAll.size,
        )
    }
}
