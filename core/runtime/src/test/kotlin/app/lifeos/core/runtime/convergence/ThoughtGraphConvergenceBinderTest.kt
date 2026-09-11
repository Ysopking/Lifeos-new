package app.lifeos.core.runtime.convergence

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
import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.field.TemporalContext
import app.lifeos.core.field.TemporalValidity
import app.lifeos.core.model.PhotonId
import app.lifeos.core.runtime.thought.ThoughtGraphAttentionEntry
import app.lifeos.core.runtime.thought.ThoughtGraphAttentionPolicy
import app.lifeos.core.runtime.thought.ThoughtGraphNodeKind
import app.lifeos.core.runtime.thought.ThoughtGraphNodeVersion
import app.lifeos.core.runtime.thought.ThoughtGraphProvenance
import app.lifeos.core.runtime.thought.ThoughtGraphSourceKind
import app.lifeos.core.runtime.thought.ThoughtGraphWorkingSet
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ThoughtGraphConvergenceBinderTest {
    private val at = Instant.parse("2026-09-11T08:00:00Z")

    @Test
    fun `complete field input binds to exact Gedankenmatrix evidence lineage`() {
        val fixture = fixture("bound")
        val workingSet = workingSet(fixture)
        val source = CrossDomainConvergenceRequest(listOf(ConvergenceDomainInput(fixture.request)))

        val bound = ThoughtGraphConvergenceBinder().bind(workingSet, source)

        assertEquals(workingSet.fingerprint, bound.workingSetFingerprint)
        assertEquals(source.id, bound.source.id)
    }

    @Test
    fun `missing Gedankenmatrix evidence fails closed instead of reconstructing payload`() {
        val fixture = fixture("missing")
        val complete = workingSet(fixture)
        val hypothesisOnly = complete.copy(
            entries = complete.entries.filter { entry ->
                complete.nodes.first { it.id == entry.nodeId }.kind == ThoughtGraphNodeKind.HYPOTHESIS
            },
            nodes = complete.nodes.filter { it.kind == ThoughtGraphNodeKind.HYPOTHESIS },
        )
        val source = CrossDomainConvergenceRequest(listOf(ConvergenceDomainInput(fixture.request)))

        assertFailsWith<IllegalArgumentException> {
            ThoughtGraphConvergenceBinder().bind(hypothesisOnly, source)
        }
    }

    private data class Fixture(
        val request: FieldConvergenceRequest,
        val evidence: FieldEvidence,
        val hypothesis: FieldHypothesis,
    )

    private fun fixture(name: String): Fixture {
        val domain = StableFieldIds.domain("binder.$name")
        val evidence = FieldEvidence.create(
            domainId = domain,
            sourcePhotonId = PhotonId("photon-$name"),
            sourceRevision = 1,
            kind = EvidenceKind.OBSERVATION,
            semanticKey = "evidence-$name",
            confidence = 0.9,
            reliability = EvidenceReliability(0.9, "binder-test"),
            authority = SourceAuthority.OFFICIAL,
            observedAt = at,
            payload = EvidencePayload.text("payload-$name"),
            explanation = "binder evidence",
        )
        val node = FieldNode.create(
            domainId = domain,
            kind = FieldNodeKind.CLAIM,
            semanticKey = "node-$name",
            baseEnergy = 1.0,
            evidenceIds = setOf(evidence.id),
        )
        val hypothesis = FieldHypothesis.create(
            domainId = domain,
            semanticKey = "hypothesis-$name",
            scope = HypothesisScope.DOMAIN,
            nodeIds = setOf(node.id),
            evidenceLinks = listOf(HypothesisEvidenceLink(evidence.id, EvidenceRelationType.SUPPORTS, 1.0)),
            explanation = "binder hypothesis",
        )
        return Fixture(
            request = FieldConvergenceRequest(
                domainId = domain,
                graph = FieldGraph(domain, listOf(node)),
                evidence = listOf(evidence),
                hypotheses = listOf(hypothesis),
                context = FieldContext(
                    temporal = TemporalContext(at),
                    domain = DomainContext(domain),
                ),
            ),
            evidence = evidence,
            hypothesis = hypothesis,
        )
    }

    private fun workingSet(fixture: Fixture): ThoughtGraphWorkingSet {
        val evidenceNode = ThoughtGraphNodeVersion.create(
            kind = ThoughtGraphNodeKind.EVIDENCE,
            semanticKey = fixture.evidence.semanticKey,
            summary = "evidence",
            confidence = fixture.evidence.confidence,
            authority = fixture.evidence.authority.defaultWeight,
            validity = fixture.evidence.validity,
            provenance = ThoughtGraphProvenance(
                sourceKind = ThoughtGraphSourceKind.EVIDENCE,
                sourceId = fixture.evidence.id.value,
                sourceRevision = fixture.evidence.sourceRevision,
                sourceFingerprint = fixture.evidence.sourceFingerprint,
                origin = "field:${fixture.request.domainId.value}",
                actor = "test",
                createdAt = fixture.evidence.observedAt,
            ),
            attributes = mapOf("domainId" to fixture.request.domainId.value),
        )
        val hypothesisNode = ThoughtGraphNodeVersion.create(
            kind = ThoughtGraphNodeKind.HYPOTHESIS,
            semanticKey = fixture.hypothesis.semanticKey,
            summary = "hypothesis",
            confidence = 0.8,
            authority = 0.8,
            validity = TemporalValidity.UNBOUNDED,
            provenance = ThoughtGraphProvenance(
                sourceKind = ThoughtGraphSourceKind.HYPOTHESIS,
                sourceId = fixture.hypothesis.id.value,
                sourceRevision = 1,
                sourceFingerprint = "hypothesis-fingerprint-${fixture.hypothesis.id.value}",
                origin = "field:${fixture.request.domainId.value}",
                actor = "test",
                createdAt = at,
            ),
            attributes = mapOf("domainId" to fixture.request.domainId.value),
        )
        val entries = listOf(
            ThoughtGraphAttentionEntry(hypothesisNode.id, 2.0, emptyList()),
            ThoughtGraphAttentionEntry(evidenceNode.id, 1.0, emptyList()),
        )
        return ThoughtGraphWorkingSet(
            sourceSnapshotId = "snapshot-$at",
            sourceRevision = 2,
            sourceHistoryFingerprint = "history-${fixture.request.domainId.value}",
            asOf = at,
            policy = ThoughtGraphAttentionPolicy(maxNodes = 8, maxEdges = 8, maxConflicts = 8),
            entries = entries,
            nodes = listOf(hypothesisNode, evidenceNode),
            edges = emptyList(),
            conflicts = emptyList(),
        )
    }
}
