package app.lifeos.core.runtime.field

import app.lifeos.core.field.DomainContext
import app.lifeos.core.field.EvidenceKind
import app.lifeos.core.field.EvidencePayload
import app.lifeos.core.field.EvidenceRelationType
import app.lifeos.core.field.EvidenceReliability
import app.lifeos.core.field.FieldContext
import app.lifeos.core.field.FieldEvidence
import app.lifeos.core.field.FieldGraph
import app.lifeos.core.field.FieldHypothesis
import app.lifeos.core.field.FieldNode
import app.lifeos.core.field.FieldNodeKind
import app.lifeos.core.field.HypothesisEvidenceLink
import app.lifeos.core.field.HypothesisScope
import app.lifeos.core.field.SourceAuthority
import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.field.TemporalContext
import app.lifeos.core.model.Photon

/**
 * Neutral deterministic projection used only for runtime shadow validation.
 *
 * The source Photon remains immutable. Provenance.source/actor are not translated into authority;
 * until a domain-specific adapter can prove authority, the generic shadow evidence is UNVERIFIED.
 */
class DefaultPhotonFieldRequestFactory : PhotonFieldRequestFactory {
    override fun create(photon: Photon): app.lifeos.core.field.FieldConvergenceRequest {
        val domainId = DOMAIN_ID
        val semanticKey = semanticKey(photon)
        val evidence = FieldEvidence.create(
            domainId = domainId,
            sourcePhotonId = photon.id,
            sourceRevision = photon.revision,
            kind = EvidenceKind.ASSERTION,
            semanticKey = semanticKey,
            confidence = photon.confidence,
            reliability = EvidenceReliability(
                score = photon.confidence,
                reason = "source-photon-confidence",
            ),
            authority = SourceAuthority.UNVERIFIED,
            observedAt = photon.provenance.createdAt,
            payload = EvidencePayload.text(photon.content),
            explanation = "Neutral shadow evidence projected from immutable source Photon",
        )
        val node = FieldNode.create(
            domainId = domainId,
            kind = FieldNodeKind.HYPOTHESIS,
            semanticKey = semanticKey,
            semanticMass = photon.semanticMass,
            baseEnergy = photon.energy,
            evidenceIds = setOf(evidence.id),
            attributes = mapOf(
                "mimeType" to photon.mimeType,
                "sourceRevision" to photon.revision.toString(),
            ),
        )
        val hypothesis = FieldHypothesis.create(
            domainId = domainId,
            semanticKey = semanticKey,
            scope = HypothesisScope.MESSAGE,
            nodeIds = setOf(node.id),
            evidenceLinks = listOf(
                HypothesisEvidenceLink(
                    evidenceId = evidence.id,
                    relation = EvidenceRelationType.SUPPORTS,
                    weight = 1.0,
                ),
            ),
            explanation = "Generic runtime shadow hypothesis for source Photon",
        )
        return app.lifeos.core.field.FieldConvergenceRequest(
            domainId = domainId,
            graph = FieldGraph(domainId = domainId, nodes = listOf(node)),
            evidence = listOf(evidence),
            hypotheses = listOf(hypothesis),
            context = FieldContext(
                temporal = TemporalContext(
                    now = photon.provenance.createdAt,
                    eventTime = photon.provenance.createdAt,
                    queryTime = photon.provenance.createdAt,
                ),
                domain = DomainContext(
                    domainId = domainId,
                    attributes = mapOf("projection" to "runtime-shadow-v1"),
                ),
            ),
        )
    }

    private fun semanticKey(photon: Photon): String {
        val normalizedTag = photon.tags
            .asSequence()
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .sorted()
            .firstOrNull()
        return if (normalizedTag != null) {
            "tag:$normalizedTag"
        } else {
            "mime:${photon.mimeType.trim().lowercase()}"
        }
    }

    companion object {
        val DOMAIN_ID = StableFieldIds.domain("lifeos.runtime.photon.shadow")
    }
}
