package app.lifeos.core.runtime.artifact

import app.lifeos.core.model.AssetId
import app.lifeos.core.model.AssetRef
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonPhase
import app.lifeos.core.model.PhotonRelation
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.RelationType
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OwnerAssetReviewCodecTest {
    private val createdAt = Instant.parse("2026-09-13T18:00:00Z")

    @Test
    fun `candidate identity binds complete staged Photon state`() {
        val baseline = photon(tags = setOf("artifact", "version:a"))
        val changedTags = photon(tags = setOf("artifact", "version:b"))
        val changedProvenance = baseline.copy(
            provenance = baseline.provenance.copy(actor = "different-module"),
        )
        val changedRelation = baseline.copy(
            relations = setOf(PhotonRelation(PhotonId("other-parent"), RelationType.REFERENCES)),
        )

        val first = candidate(staged = listOf(baseline))
        val second = candidate(staged = listOf(changedTags))
        val third = candidate(staged = listOf(changedProvenance))
        val fourth = candidate(staged = listOf(changedRelation))

        assertNotEquals(first.id, second.id)
        assertNotEquals(first.id, third.id)
        assertNotEquals(first.id, fourth.id)
    }

    @Test
    fun `codec round trips pending approved and published records deterministically`() {
        val pending = OwnerAssetReviewRecord(candidate())
        val approvedCandidate = candidate(revisionKey = "revision-approved")
        val decision = OwnerAssetReviewDecisionRecord(
            candidateId = approvedCandidate.id,
            decision = OwnerAssetReviewDecision.APPROVED,
            ownerActorId = "private-owner",
            feedback = "Freigegeben",
            decidedAt = createdAt.plusSeconds(30),
            decisionPhotonId = PhotonId("owner_asset_decision_approved"),
        )
        val published = OwnerAssetReviewRecord(
            candidate = approvedCandidate,
            decision = decision,
            publishedAt = createdAt.plusSeconds(31),
        )

        val first = OwnerAssetReviewCodec.encode(listOf(published, pending))
        val decoded = OwnerAssetReviewCodec.decode(first)
        val second = OwnerAssetReviewCodec.encode(decoded)

        assertTrue(first.contentEquals(second))
        assertEquals(listOf(pending, published).sortedBy { it.candidate.id.value }, decoded)
        assertNull(decoded.first { it.candidate.id == pending.candidate.id }.decision)
    }

    @Test
    fun `codec rejects trailing bytes instead of accepting ambiguous payload`() {
        val bytes = OwnerAssetReviewCodec.encode(listOf(OwnerAssetReviewRecord(candidate())))
        val tampered = bytes + byteArrayOf(0x01)

        assertFailsWith<IllegalArgumentException> {
            OwnerAssetReviewCodec.decode(tampered)
        }
    }

    @Test
    fun `changes requested requires feedback and only approval can be published`() {
        val candidate = candidate()

        assertFailsWith<IllegalArgumentException> {
            OwnerAssetReviewDecisionRecord(
                candidateId = candidate.id,
                decision = OwnerAssetReviewDecision.CHANGES_REQUESTED,
                ownerActorId = "private-owner",
                feedback = " ",
                decidedAt = createdAt.plusSeconds(10),
                decisionPhotonId = PhotonId("owner_asset_decision_changes"),
            )
        }

        val rejected = OwnerAssetReviewDecisionRecord(
            candidateId = candidate.id,
            decision = OwnerAssetReviewDecision.REJECTED,
            ownerActorId = "private-owner",
            feedback = "Nicht verwenden",
            decidedAt = createdAt.plusSeconds(10),
            decisionPhotonId = PhotonId("owner_asset_decision_rejected"),
        )
        assertFailsWith<IllegalArgumentException> {
            OwnerAssetReviewRecord(
                candidate = candidate,
                decision = rejected,
                publishedAt = createdAt.plusSeconds(11),
            )
        }
    }

    @Test
    fun `candidate id mismatch is rejected`() {
        val first = candidate()
        val other = candidate(revisionKey = "other-revision")

        assertFailsWith<IllegalArgumentException> {
            first.copy(id = other.id)
        }
    }

    private fun candidate(
        revisionKey: String = "revision-1",
        staged: List<Photon> = listOf(photon()),
    ): OwnerAssetReviewCandidate = OwnerAssetReviewCandidate.create(
        subjectType = OwnerAssetReviewSubjectType.COLLABORATIVE_ARTIFACT,
        subjectId = "generated-image:photon-1",
        revisionKey = revisionKey,
        kind = ArtifactKind.IMAGE,
        title = "Generated image scene-1",
        targetMimeType = "image/png",
        createdAt = createdAt,
        participatingModules = setOf("image-renderer", "scene-compiler"),
        inputPhotonIds = setOf(PhotonId("input-a"), PhotonId("input-b")),
        materializedAsset = AssetRef(
            id = AssetId("asset_review_test"),
            mediaType = "image/png",
            byteCount = 128,
            sha256 = "a".repeat(64),
        ),
        stagedPhotons = staged,
        previewText = "review preview",
        metadata = mapOf("renderer" to "offline-procedural-image"),
    )

    private fun photon(tags: Set<String> = setOf("artifact")): Photon = Photon(
        id = PhotonId("artifact_review_test_photon"),
        revision = 1,
        content = "artifact review payload",
        mimeType = "application/vnd.lifeos.test+json",
        phase = PhotonPhase.CONVERGED,
        semanticMass = 2.0,
        energy = 1.0,
        confidence = 0.95,
        provenance = Provenance(
            source = "lifeos.test",
            actor = "test-module",
            createdAt = createdAt,
            parentIds = setOf(PhotonId("input-a")),
        ),
        relations = setOf(
            PhotonRelation(PhotonId("input-a"), RelationType.DERIVED_FROM),
        ),
        tags = tags,
    )
}
