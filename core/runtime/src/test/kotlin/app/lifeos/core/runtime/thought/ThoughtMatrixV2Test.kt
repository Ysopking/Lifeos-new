package app.lifeos.core.runtime.thought

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.field.TemporalValidity
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonPhase
import app.lifeos.core.model.PhotonRelation
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.RelationType
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ThoughtMatrixV2Test {
    private val at = Instant.parse("2026-09-10T11:00:00Z")
    private val domain = StableFieldIds.domain("test.thought")

    @Test
    fun `same durable truth rebuilds to same fingerprint regardless input order`() = runTest {
        val a = input(photon("a", 1, "alpha", energy = 2.0))
        val b1 = input(photon("b", 1, "old beta", energy = 1.0))
        val b2 = input(photon("b", 2, "new beta", energy = 3.0))

        val left = ThoughtMatrixV2 { at }.rebuild(listOf(a, b1, b2), at).snapshot
        val right = ThoughtMatrixV2 { at.plusSeconds(5) }.rebuild(listOf(b2, a, b1), at.plusSeconds(5)).snapshot

        assertEquals(left.contentFingerprint, right.contentFingerprint)
        assertEquals(left.snapshotId, right.snapshotId)
        assertEquals(listOf("a", "b"), left.nodes.map { it.photonId.value })
        assertEquals(2L, left.nodes.single { it.photonId.value == "b" }.sourceRevision)
        assertEquals(5.0, left.totalEnergy)
    }

    @Test
    fun `higher revision replaces node relations and clears prior projection`() = runTest {
        val targetOne = PhotonId("target-one")
        val targetTwo = PhotonId("target-two")
        val v1 = photon(
            id = "source",
            revision = 1,
            content = "first",
            relations = setOf(PhotonRelation(targetOne, RelationType.SUPPORTS, 0.6)),
        )
        val v2 = photon(
            id = "source",
            revision = 2,
            content = "second",
            phase = PhotonPhase.CONVERGED,
            relations = setOf(PhotonRelation(targetTwo, RelationType.CONTRADICTS, 0.8)),
        )
        val matrix = ThoughtMatrixV2 { at }

        assertIs<ThoughtProjectionResult.Applied>(matrix.project(input(v1)))
        val updated = assertIs<ThoughtProjectionResult.Applied>(matrix.project(input(v2)))

        assertEquals(1L, updated.replacedRevision)
        assertEquals(2L, updated.snapshot.nodes.single().sourceRevision)
        assertEquals(ThoughtLifecycleStatus.CONVERGED, updated.snapshot.nodes.single().lifecycle)
        assertEquals(listOf(targetTwo), updated.snapshot.relations.map { it.targetPhotonId })
        assertEquals(ThoughtRelationType.CONTRADICTS, updated.snapshot.relations.single().type)
    }

    @Test
    fun `equal revision disagreement becomes unresolved and keeps no active variant`() = runTest {
        val matrix = ThoughtMatrixV2 { at }
        val first = input(photon("same", 1, "Value A"))
        val second = input(photon("same", 1, "Value B"))

        matrix.project(first)
        val conflict = assertIs<ThoughtProjectionResult.Conflict>(matrix.project(second))

        assertTrue(conflict.snapshot.nodes.none { it.photonId.value == "same" })
        assertTrue(conflict.snapshot.relations.none { it.sourcePhotonId.value == "same" })
        assertEquals(1, conflict.snapshot.conflicts.size)
        assertEquals(2, conflict.conflict.fingerprints.size)

        val repeated = assertIs<ThoughtProjectionResult.Conflict>(matrix.project(second))
        assertEquals(conflict.snapshot.contentFingerprint, repeated.snapshot.contentFingerprint)
        assertEquals(conflict.snapshot.revision, repeated.snapshot.revision)
    }

    @Test
    fun `higher revision resolves an equal revision conflict`() = runTest {
        val matrix = ThoughtMatrixV2 { at }
        matrix.project(input(photon("same", 1, "A")))
        matrix.project(input(photon("same", 1, "B")))

        val resolved = assertIs<ThoughtProjectionResult.Applied>(
            matrix.project(input(photon("same", 2, "resolved")))
        )

        assertEquals(1L, resolved.replacedRevision)
        assertEquals(2L, resolved.snapshot.nodes.single().sourceRevision)
        assertTrue(resolved.snapshot.conflicts.isEmpty())
    }

    @Test
    fun `rebuild exposes same revision conflicts without choosing input order`() = runTest {
        val first = input(photon("conflict", 3, "first"))
        val second = input(photon("conflict", 3, "second"))

        val left = ThoughtMatrixV2 { at }.rebuild(listOf(first, second), at).snapshot
        val right = ThoughtMatrixV2 { at }.rebuild(listOf(second, first), at).snapshot

        assertTrue(left.nodes.isEmpty())
        assertEquals(1, left.conflicts.size)
        assertEquals(left.contentFingerprint, right.contentFingerprint)
        assertEquals(left.conflicts, right.conflicts)
    }

    @Test
    fun `source fingerprint distinguishes full content beyond summary and letter case`() = runTest {
        val prefix = "x".repeat(240)
        val matrix = ThoughtMatrixV2 { at }
        val first = input(photon("full", 1, prefix + "A"))
        val second = input(photon("full", 1, prefix + "a"))

        val firstResult = assertIs<ThoughtProjectionResult.Applied>(matrix.project(first))
        val firstFingerprint = firstResult.snapshot.nodes.single().provenance.sourceFingerprint
        val conflict = assertIs<ThoughtProjectionResult.Conflict>(matrix.project(second))

        assertEquals(firstResult.snapshot.nodes.single().summary, second.photon.content.take(240))
        assertEquals(2, conflict.conflict.fingerprints.size)
        assertFalse(firstFingerprint.isBlank())
    }

    @Test
    fun `projection carries provenance temporal validity verification mass and confidence`() = runTest {
        val validFrom = at.minusSeconds(60)
        val validUntil = at.plusSeconds(60)
        val photon = photon(
            id = "rich",
            revision = 4,
            content = "rich source",
            semanticMass = 7.5,
            energy = 4.5,
            confidence = 0.72,
            phase = PhotonPhase.REFLECTING,
        )
        val matrix = ThoughtMatrixV2 { at }

        val result = assertIs<ThoughtProjectionResult.Applied>(
            matrix.project(
                ThoughtProjectionInput(
                    photon = photon,
                    fieldDomainId = domain,
                    semanticKey = "topic.rich",
                    validity = TemporalValidity(validFrom, validUntil),
                    verification = ThoughtVerificationStatus.VERIFIED,
                )
            )
        )
        val node = result.snapshot.nodes.single()

        assertEquals(photon.id, node.provenance.sourcePhotonId)
        assertEquals(4L, node.provenance.sourceRevision)
        assertEquals("test-source", node.provenance.source)
        assertEquals("test-actor", node.provenance.actor)
        assertEquals(domain, node.fieldDomainId)
        assertEquals("topic.rich", node.semanticKey)
        assertEquals(7.5, node.semanticMass)
        assertEquals(4.5, node.energy)
        assertEquals(0.72, node.confidence)
        assertEquals(validFrom, node.validity.validFrom)
        assertEquals(validUntil, node.validity.validUntilExclusive)
        assertEquals(ThoughtLifecycleStatus.REFLECTING, node.lifecycle)
        assertEquals(ThoughtVerificationStatus.VERIFIED, node.verification)
    }

    private fun input(photon: Photon) = ThoughtProjectionInput(
        photon = photon,
        fieldDomainId = domain,
        semanticKey = "topic.${photon.id.value}",
        verification = ThoughtVerificationStatus.OBSERVED,
    )

    private fun photon(
        id: String,
        revision: Long,
        content: String,
        semanticMass: Double = 1.0,
        energy: Double = 1.0,
        confidence: Double = 0.9,
        phase: PhotonPhase = PhotonPhase.ACTIVE,
        relations: Set<PhotonRelation> = emptySet(),
    ) = Photon(
        id = PhotonId(id),
        revision = revision,
        content = content,
        phase = phase,
        semanticMass = semanticMass,
        energy = energy,
        confidence = confidence,
        provenance = Provenance(
            source = "test-source",
            actor = "test-actor",
            createdAt = at,
        ),
        relations = relations,
        tags = setOf("thought", id),
    )
}
