package app.lifeos.core.runtime.field

import app.lifeos.core.field.DomainContext
import app.lifeos.core.field.EvidenceKind
import app.lifeos.core.field.EvidencePayload
import app.lifeos.core.field.EvidenceRelationType
import app.lifeos.core.field.EvidenceReliability
import app.lifeos.core.field.FieldContext
import app.lifeos.core.field.FieldConvergenceRequest
import app.lifeos.core.field.FieldEvidence
import app.lifeos.core.field.FieldGraph
import app.lifeos.core.field.FieldHypothesis
import app.lifeos.core.field.FieldNode
import app.lifeos.core.field.FieldNodeKind
import app.lifeos.core.field.HypothesisEvidenceLink
import app.lifeos.core.field.HypothesisScope
import app.lifeos.core.field.SourceAuthority
import app.lifeos.core.field.TemporalContext
import app.lifeos.core.model.Photon
import app.lifeos.core.runtime.ThoughtMatrix

/**
 * Domain-specific universal projection for the first productive cutover candidate: ThoughtMatrix.
 * It preserves the source Photon as immutable evidence and keeps source confidence explicit.
 */
class ThoughtMatrixFieldRequestFactory : PhotonFieldRequestFactory {
    override fun create(photon: Photon): FieldConvergenceRequest {
        val domainId = ThoughtMatrix.FIELD_DOMAIN_ID
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
            explanation = "ThoughtMatrix cutover evidence projected from immutable source Photon",
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
                "projection" to "thought-matrix-cutover-v1",
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
                )
            ),
            explanation = "ThoughtMatrix universal-field indexing hypothesis",
        )
        return FieldConvergenceRequest(
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
                    attributes = mapOf("projection" to "thought-matrix-cutover-v1"),
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
            "tag:" + normalizedTag
        } else {
            "mime:" + photon.mimeType.trim().lowercase()
        }
    }
}
