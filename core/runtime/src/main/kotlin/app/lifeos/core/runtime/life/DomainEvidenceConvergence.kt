package app.lifeos.core.runtime.life

import app.lifeos.core.model.CanonicalPhotonState
import app.lifeos.core.model.CognitiveStateHash
import app.lifeos.core.model.ModuleIdentity
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonPhase
import app.lifeos.core.model.PhotonRelation
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.RelationType
import app.lifeos.core.model.StableCognitiveIds
import java.time.Instant
import kotlin.math.abs

enum class DomainEvidenceStance {
    SUPPORTS,
    CONTRADICTS,
    UNCERTAIN,
}

data class DomainEvidenceAssertion(
    /** Stable proposition identity: same normalized proposition can be interpreted repeatedly. */
    val factId: String,
    /** Stable interpretation identity: changes with source state or producer implementation. */
    val interpretationId: String,
    /** Stable source-evidence identity, independent of the module that interpreted it. */
    val evidenceFingerprint: String,
    val kind: DomainFactKind,
    val normalizedValue: String,
    val stance: DomainEvidenceStance,
    val confidence: Double,
    val sourcePhotonId: PhotonId,
    val sourceRevision: Long,
    val sourceStateHash: CognitiveStateHash,
    val producerModuleId: String,
    val producerModuleVersion: String,
    val producerModuleFingerprint: String,
    val observedAt: Instant,
    val evidenceSpan: String,
) {
    init {
        require(factId.startsWith("fact:")) { "factId must use the fact: namespace" }
        require(interpretationId.startsWith("fact-interpretation:")) {
            "interpretationId must use the fact-interpretation: namespace"
        }
        require(evidenceFingerprint.matches(Regex("[0-9a-f]{64}"))) {
            "evidenceFingerprint must be a SHA-256 hex digest"
        }
        require(normalizedValue.isNotBlank())
        require(confidence.isFinite() && confidence in 0.0..1.0)
        require(sourceRevision > 0)
        require(producerModuleId.isNotBlank())
        require(producerModuleVersion.isNotBlank())
        require(producerModuleFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(evidenceSpan.isNotBlank())
    }
}

object DomainEvidenceIdentity {
    fun assertion(
        source: Photon,
        fact: DomainFact,
        producer: ModuleIdentity,
    ): DomainEvidenceAssertion {
        val normalizedValue = normalizeValue(fact.value)
        val factId = "fact:" + StableCognitiveIds.fingerprint(
            "domain-fact/v2",
            fact.kind.name,
            normalizedValue,
        )
        val evidenceSpan = compact(fact.evidence)
        val sourceStateHash = CanonicalPhotonState.inputHash(source)
        val evidenceFingerprint = StableCognitiveIds.fingerprint(
            "domain-evidence/v2",
            source.id.value,
            source.revision.toString(),
            sourceStateHash.value,
            fact.kind.name,
            normalizedValue,
            evidenceSpan,
        )
        val interpretationId = "fact-interpretation:" + StableCognitiveIds.fingerprint(
            "domain-fact-interpretation/v1",
            factId,
            sourceStateHash.value,
            producer.stableFingerprint,
            evidenceFingerprint,
        )
        return DomainEvidenceAssertion(
            factId = factId,
            interpretationId = interpretationId,
            evidenceFingerprint = evidenceFingerprint,
            kind = fact.kind,
            normalizedValue = normalizedValue,
            stance = stance(source),
            confidence = fact.confidence,
            sourcePhotonId = source.id,
            sourceRevision = source.revision,
            sourceStateHash = sourceStateHash,
            producerModuleId = producer.moduleId,
            producerModuleVersion = producer.version,
            producerModuleFingerprint = producer.stableFingerprint,
            observedAt = source.provenance.createdAt,
            evidenceSpan = evidenceSpan,
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
    val interpretationIds: List<String>,
    val canonicalFingerprint: String,
    val boundedOut: Boolean,
    val totalEvidenceCount: Int,
    val totalInterpretationCount: Int,
) {
    init {
        require(factId.startsWith("fact:"))
        require(supportConfidence in 0.0..1.0)
        require(contradictionConfidence in 0.0..1.0)
        require(uncertaintyConfidence in 0.0..1.0)
        require(evidenceFingerprints == evidenceFingerprints.distinct().sorted())
        require(interpretationIds == interpretationIds.distinct().sorted())
        require(canonicalFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(totalEvidenceCount >= evidenceFingerprints.size)
        require(totalInterpretationCount >= interpretationIds.size)
    }
}

/**
 * Deterministic, bounded convergence for parallel domain evidence.
 *
 * Multiple module versions may reinterpret the same immutable evidence. They retain distinct
 * interpretation identities but the source evidence votes only once. Conflicting interpretations
 * of the exact same evidence fingerprint fail closed rather than becoming caller-order dependent.
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

        val interpretations = assertions
            .groupBy { it.interpretationId }
            .map { (id, group) ->
                require(group.distinct().size == 1) {
                    "Conflicting domain interpretation identity: $id"
                }
                group.single()
            }
            .sortedBy { it.interpretationId }

        val byEvidence = interpretations.groupBy { it.evidenceFingerprint }
        val evidenceVotes = byEvidence.map { (fingerprint, group) ->
            require(group.map { it.stance }.distinct().size == 1) {
                "Conflicting domain evidence stance: $fingerprint"
            }
            require(group.map { it.sourcePhotonId to it.sourceRevision }.distinct().size == 1) {
                "Conflicting domain evidence source: $fingerprint"
            }
            require(group.map { it.sourceStateHash }.distinct().size == 1) {
                "Conflicting domain evidence state: $fingerprint"
            }
            require(group.map { it.normalizedValue }.distinct().size == 1) {
                "Conflicting domain evidence value: $fingerprint"
            }
            require(group.map { it.evidenceSpan }.distinct().size == 1) {
                "Conflicting domain evidence span: $fingerprint"
            }
            group.sortedWith(
                compareByDescending<DomainEvidenceAssertion> { it.confidence }
                    .thenBy { it.interpretationId },
            ).first()
        }.sortedBy { it.evidenceFingerprint }

        val boundedOut = evidenceVotes.size > maxEvidence
        val canonical = evidenceVotes.take(maxEvidence)
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
        val interpretationIds = interpretations.map { it.interpretationId }.distinct().sorted()
        val canonicalFingerprint = StableCognitiveIds.fingerprint(
            "domain-evidence-convergence/v2",
            factIds.single(),
            status.name,
            java.lang.Double.toHexString(support),
            java.lang.Double.toHexString(contradiction),
            java.lang.Double.toHexString(uncertainty),
            boundedOut.toString(),
            evidenceVotes.size.toString(),
            interpretations.size.toString(),
            *fingerprints.toTypedArray(),
            *interpretationIds.toTypedArray(),
        )
        return DomainEvidenceConvergenceResult(
            factId = factIds.single(),
            status = status,
            supportConfidence = support,
            contradictionConfidence = contradiction,
            uncertaintyConfidence = uncertainty,
            evidenceFingerprints = fingerprints,
            interpretationIds = interpretationIds,
            canonicalFingerprint = canonicalFingerprint,
            boundedOut = boundedOut,
            totalEvidenceCount = evidenceVotes.size,
            totalInterpretationCount = interpretations.size,
        )
    }
}

/** Creates the new canonical information Photon while retaining every bounded evidence ancestor. */
object DomainEvidenceConvergencePhotonFactory {
    fun create(
        result: DomainEvidenceConvergenceResult,
        assertions: Collection<DomainEvidenceAssertion>,
    ): Photon {
        val byFingerprint = assertions.groupBy { it.evidenceFingerprint }
        val contributing = result.evidenceFingerprints.flatMap { fingerprint ->
            requireNotNull(byFingerprint[fingerprint]) { "Missing evidence for convergence fingerprint" }
        }.distinctBy { it.interpretationId }
        require(contributing.isNotEmpty())
        require(contributing.all { it.factId == result.factId })

        val parentIds = contributing.mapTo(linkedSetOf()) { it.sourcePhotonId }
        val confidence = when (result.status) {
            DomainEvidenceConvergenceStatus.CONFIRMED -> result.supportConfidence
            DomainEvidenceConvergenceStatus.REJECTED -> result.contradictionConfidence
            DomainEvidenceConvergenceStatus.UNRESOLVED -> 0.5
        }
        val phase = if (result.status == DomainEvidenceConvergenceStatus.UNRESOLVED) {
            PhotonPhase.REFLECTING
        } else {
            PhotonPhase.CONVERGED
        }
        return Photon(
            id = PhotonId("domain-convergence-${result.canonicalFingerprint}"),
            revision = 1,
            content = buildString {
                appendLine("fact_id=${result.factId}")
                appendLine("status=${result.status.name}")
                appendLine("support_confidence=${result.supportConfidence}")
                appendLine("contradiction_confidence=${result.contradictionConfidence}")
                appendLine("uncertainty_confidence=${result.uncertaintyConfidence}")
                appendLine("bounded_out=${result.boundedOut}")
                appendLine("total_evidence=${result.totalEvidenceCount}")
                appendLine("total_interpretations=${result.totalInterpretationCount}")
                appendLine("evidence=${result.evidenceFingerprints.joinToString(",")}")
                append("interpretations=${result.interpretationIds.joinToString(",")}")
            },
            mimeType = "application/vnd.lifeos.domain-convergence+text",
            phase = phase,
            semanticMass = maxOf(
                result.supportConfidence,
                result.contradictionConfidence,
                result.uncertaintyConfidence,
            ),
            energy = 1.0,
            confidence = confidence,
            provenance = Provenance(
                source = "domain-evidence-convergence",
                actor = "lifeos",
                createdAt = contributing.maxOf { it.observedAt },
                parentIds = parentIds,
            ),
            relations = parentIds.mapTo(linkedSetOf()) { PhotonRelation(it, RelationType.REFERENCES) },
            tags = setOf(
                "domain-evidence-convergence",
                "fact-id:${result.factId}",
                "convergence-status:${result.status.name.lowercase()}",
                if (result.boundedOut) "evidence-budget:exceeded" else "evidence-budget:within",
            ),
        )
    }
}
