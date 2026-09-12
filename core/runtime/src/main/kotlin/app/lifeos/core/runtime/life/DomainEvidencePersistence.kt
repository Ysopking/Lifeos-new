package app.lifeos.core.runtime.life

import app.lifeos.core.model.CanonicalPhotonState
import app.lifeos.core.model.CognitiveStateHash
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonRepository
import java.time.Instant

/** Stable schema and compatibility reader for persisted structured domain evidence. */
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

    suspend fun decode(photon: Photon, sources: PhotonRepository): DomainEvidenceAssertion? {
        val fields = parse(photon) ?: return null
        val assertion = if (fields["schema"] == SCHEMA_VERSION) {
            decodeV2(fields)
        } else {
            decodeLegacy(photon, fields, sources)
        }
        verifyPhotonMetadata(photon, assertion)
        return assertion
    }

    private fun parse(photon: Photon): Map<String, String>? {
        if (photon.mimeType != MIME_TYPE || "structured-domain-evidence" !in photon.tags) return null
        val fields = linkedMapOf<String, String>()
        photon.content.lineSequence().filter { it.isNotBlank() }.forEach { line ->
            val separator = line.indexOf('=')
            require(separator > 0) { "Malformed structured domain evidence line" }
            val key = line.substring(0, separator)
            val value = line.substring(separator + 1)
            require(fields.put(key, value) == null) { "Duplicate structured domain evidence field: $key" }
        }
        return fields
    }

    private fun decodeV2(fields: Map<String, String>): DomainEvidenceAssertion = DomainEvidenceAssertion(
        factId = required(fields, "fact_id"),
        interpretationId = required(fields, "interpretation_id"),
        evidenceFingerprint = required(fields, "evidence_fingerprint"),
        kind = DomainFactKind.valueOf(required(fields, "kind")),
        normalizedValue = required(fields, "value"),
        stance = DomainEvidenceStance.valueOf(required(fields, "stance")),
        confidence = required(fields, "confidence").toDouble(),
        sourcePhotonId = PhotonId(required(fields, "source_photon_id")),
        sourceRevision = required(fields, "source_revision").toLong(),
        sourceStateHash = CognitiveStateHash(required(fields, "source_state_hash")),
        producerModuleId = required(fields, "producer_module"),
        producerModuleVersion = required(fields, "producer_version"),
        producerModuleFingerprint = required(fields, "producer_fingerprint"),
        observedAt = Instant.parse(required(fields, "observed_at")),
        evidenceSpan = required(fields, "evidence_span"),
    )

    private suspend fun decodeLegacy(
        photon: Photon,
        fields: Map<String, String>,
        sources: PhotonRepository,
    ): DomainEvidenceAssertion {
        val sourceId = photon.provenance.parentIds.singleOrNull()
            ?: throw IllegalArgumentException("Legacy structured evidence requires one source parent")
        val source = sources.load(sourceId)
            ?: throw IllegalArgumentException("Structured evidence source is unavailable: ${sourceId.value}")
        val expectedState = CognitiveStateHash(required(fields, "source_state_hash"))
        require(CanonicalPhotonState.inputHash(source) == expectedState) {
            "Structured evidence source state mismatch"
        }
        return DomainEvidenceAssertion(
            factId = required(fields, "fact_id"),
            interpretationId = required(fields, "interpretation_id"),
            evidenceFingerprint = required(fields, "evidence_fingerprint"),
            kind = DomainFactKind.valueOf(required(fields, "kind")),
            normalizedValue = required(fields, "value"),
            stance = DomainEvidenceStance.valueOf(required(fields, "stance")),
            confidence = required(fields, "confidence").toDouble(),
            sourcePhotonId = source.id,
            sourceRevision = source.revision,
            sourceStateHash = expectedState,
            producerModuleId = required(fields, "producer_module"),
            producerModuleVersion = required(fields, "producer_version"),
            producerModuleFingerprint = required(fields, "producer_fingerprint"),
            observedAt = source.provenance.createdAt,
            evidenceSpan = required(fields, "evidence_span"),
        )
    }

    private fun required(fields: Map<String, String>, key: String): String =
        fields[key]?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Missing structured domain evidence field: $key")

    private fun verifyPhotonMetadata(photon: Photon, assertion: DomainEvidenceAssertion) {
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
    }
}

/** Durable, deterministic fan-in for all structured domain evidence belonging to one fact. */
class DomainEvidenceConvergenceCoordinator(
    private val photons: PhotonRepository,
    private val engine: DomainEvidenceConvergenceEngine = DomainEvidenceConvergenceEngine(),
) {
    suspend fun convergePersisted(photon: Photon): Photon? {
        val current = DomainEvidencePhotonCodec.decode(photon, photons) ?: return null
        val factTag = "fact-id:${current.factId}"
        val assertions = buildList {
            photons.loadAll().forEach { persisted ->
                if (factTag !in persisted.tags) return@forEach
                val decoded = DomainEvidencePhotonCodec.decode(persisted, photons) ?: return@forEach
                require(decoded.factId == current.factId) { "Domain evidence fact tag mismatch" }
                add(decoded)
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
