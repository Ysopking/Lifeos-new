package app.lifeos.core.runtime.life

import app.lifeos.core.model.CognitiveStateHash
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonRepository
import java.time.Instant

/**
 * Stable schema for persisted structured domain evidence.
 *
 * Schema v2 carries enough source identity to reconstruct an assertion after process death without
 * consulting mutable current source state. Older branch-local domain-fact payloads remain readable
 * as Photons but are deliberately not promoted into convergence because their source revision was
 * not encoded losslessly.
 */
object DomainEvidencePhotonCodec {
    const val MIME_TYPE = "application/vnd.lifeos.domain-fact+text"
    private const val SCHEMA_VERSION = "2"

    fun encode(assertion: DomainEvidenceAssertion): String = buildString {
        appendLine("schema=$SCHEMA_VERSION")
        appendLine("fact_id=${assertion.factId}")
        appendLine("interpretation_id=${assertion.interpretationId}")
        appendLine("evidence_fingerprint=${assertion.evidenceFingerprint}")
        appendLine("source_photon_id=${assertion.sourcePhotonId.value}")
        appendLine("source_revision=${assertion.sourceRevision}")
        appendLine("source_state_hash=${assertion.sourceStateHash.value}")
        appendLine("producer_module=${assertion.producerModuleId}")
        appendLine("producer_version=${assertion.producerModuleVersion}")
        appendLine("producer_fingerprint=${assertion.producerModuleFingerprint}")
        appendLine("kind=${assertion.kind.name}")
        appendLine("value=${assertion.normalizedValue}")
        appendLine("stance=${assertion.stance.name}")
        appendLine("confidence=${assertion.confidence}")
        appendLine("observed_at=${assertion.observedAt}")
        append("evidence_span=${assertion.evidenceSpan}")
    }

    fun decode(photon: Photon): DomainEvidenceAssertion? {
        if (photon.mimeType != MIME_TYPE || "structured-domain-evidence" !in photon.tags) return null

        val fields = linkedMapOf<String, String>()
        photon.content.lineSequence().filter { it.isNotBlank() }.forEach { line ->
            val separator = line.indexOf('=')
            require(separator > 0) { "Malformed structured domain evidence line" }
            val key = line.substring(0, separator)
            val value = line.substring(separator + 1)
            require(fields.put(key, value) == null) { "Duplicate structured domain evidence field: $key" }
        }

        // Pre-v2 branch-local payloads did not encode source revision and cannot be reconstructed
        // losslessly. Preserve them as evidence Photons but do not invent missing identity.
        if (fields["schema"] != SCHEMA_VERSION) return null

        fun required(key: String): String = fields[key]?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Missing structured domain evidence field: $key")

        val assertion = DomainEvidenceAssertion(
            factId = required("fact_id"),
            interpretationId = required("interpretation_id"),
            evidenceFingerprint = required("evidence_fingerprint"),
            kind = DomainFactKind.valueOf(required("kind")),
            normalizedValue = required("value"),
            stance = DomainEvidenceStance.valueOf(required("stance")),
            confidence = required("confidence").toDouble(),
            sourcePhotonId = PhotonId(required("source_photon_id")),
            sourceRevision = required("source_revision").toLong(),
            sourceStateHash = CognitiveStateHash(required("source_state_hash")),
            producerModuleId = required("producer_module"),
            producerModuleVersion = required("producer_version"),
            producerModuleFingerprint = required("producer_fingerprint"),
            observedAt = Instant.parse(required("observed_at")),
            evidenceSpan = required("evidence_span"),
        )

        require(assertion.sourcePhotonId in photon.provenance.parentIds) {
            "Structured domain evidence lost its source parent"
        }
        require("fact-id:${assertion.factId}" in photon.tags) { "Structured domain fact tag mismatch" }
        require("interpretation-id:${assertion.interpretationId}" in photon.tags) {
            "Structured domain interpretation tag mismatch"
        }
        require("evidence:${assertion.evidenceFingerprint}" in photon.tags) {
            "Structured domain evidence fingerprint tag mismatch"
        }
        require("stance:${assertion.stance.name.lowercase()}" in photon.tags) {
            "Structured domain stance tag mismatch"
        }
        return assertion
    }
}

/**
 * Productive fan-in seam for durable domain evidence.
 *
 * The just-persisted evidence Photon is converged together with every durable v2 assertion for the
 * same proposition. The coordinator never mutates or deletes source evidence. A convergence Photon
 * is returned to the caller for normal encrypted persistence; an already persisted identical result
 * is treated as an idempotent replay, while same-id/different-content fails closed.
 */
class DomainEvidenceConvergenceCoordinator(
    private val photons: PhotonRepository,
    private val engine: DomainEvidenceConvergenceEngine = DomainEvidenceConvergenceEngine(),
) {
    suspend fun convergePersisted(photon: Photon): Photon? {
        val current = DomainEvidencePhotonCodec.decode(photon) ?: return null
        val assertions = buildList {
            photons.loadAll().forEach { persisted ->
                val decoded = DomainEvidencePhotonCodec.decode(persisted) ?: return@forEach
                if (decoded.factId == current.factId) add(decoded)
            }
            if (none { it.interpretationId == current.interpretationId }) add(current)
        }
        require(assertions.isNotEmpty()) { "Persisted domain evidence disappeared before convergence" }

        validateEvidenceIdentity(assertions)
        val result = engine.converge(assertions)
        val convergence = DomainEvidenceConvergencePhotonFactory.create(result, assertions)
        val existing = photons.load(convergence.id)
        if (existing != null) {
            check(existing == convergence) {
                "Conflicting persisted domain convergence identity: ${convergence.id.value}"
            }
            return null
        }
        return convergence
    }

    private fun validateEvidenceIdentity(assertions: Collection<DomainEvidenceAssertion>) {
        assertions.groupBy { it.interpretationId }.forEach { (id, group) ->
            require(group.distinct().size == 1) { "Conflicting domain interpretation identity: $id" }
        }
        assertions.groupBy { it.evidenceFingerprint }.forEach { (fingerprint, group) ->
            require(group.map { it.observedAt }.distinct().size == 1) {
                "Conflicting domain evidence observation time: $fingerprint"
            }
            require(group.map { it.kind }.distinct().size == 1) {
                "Conflicting domain evidence kind: $fingerprint"
            }
        }
    }
}
