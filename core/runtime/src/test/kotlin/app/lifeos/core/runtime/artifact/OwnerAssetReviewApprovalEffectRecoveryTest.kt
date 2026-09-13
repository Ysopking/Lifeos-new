package app.lifeos.core.runtime.artifact

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonLoadReport
import app.lifeos.core.model.PhotonRepository
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class OwnerAssetReviewApprovalEffectRecoveryTest {
    private val decidedAt = Instant.parse("2026-09-13T20:00:00Z")

    @Test
    fun `approved subject effect must complete before published seal and is replayed after failure`() = runTest {
        val reviews = Reviews()
        val photons = Photons()
        val candidate = OwnerAssetReviewCandidate.create(
            subjectType = OwnerAssetReviewSubjectType.GENERATED_TOOL,
            subjectId = "tool-review",
            revisionKey = "a".repeat(64),
            kind = ArtifactKind.CODE,
            title = "Generated tool tool-review",
            targetMimeType = "application/vnd.lifeos.generated-tool+text",
            createdAt = decidedAt.minusSeconds(10),
            participatingModules = setOf("tool-workshop"),
            inputPhotonIds = emptySet(),
            previewText = "tool/v1\nid=tool-review",
        )
        reviews.stage(candidate)

        var attempts = 0
        val failing = coordinator(reviews, photons) {
            attempts += 1
            error("simulated crash after durable approval")
        }

        assertFailsWith<IllegalStateException> {
            failing.decide(
                candidateId = candidate.id,
                decision = OwnerAssetReviewDecision.APPROVED,
                ownerActorId = "private-owner",
                feedback = null,
                decidedAt = decidedAt,
            )
        }
        assertEquals(1, attempts)
        assertEquals(OwnerAssetReviewDecision.APPROVED, reviews.load(candidate.id)?.decision?.decision)
        assertNull(reviews.load(candidate.id)?.publishedAt)

        var recovered = 0
        val recovery = coordinator(reviews, photons) { approved ->
            assertEquals(candidate, approved)
            recovered += 1
        }
        val records = recovery.reconcileApproved()

        assertEquals(1, recovered)
        assertEquals(1, records.size)
        assertEquals(decidedAt, reviews.load(candidate.id)?.publishedAt)
        assertEquals(1, photons.loadAll().count { "owner-asset-review" in it.tags })
    }

    private fun coordinator(
        reviews: Reviews,
        photons: Photons,
        onApproved: suspend (OwnerAssetReviewCandidate) -> Unit,
    ) = OwnerAssetReviewCoordinator(
        reviews = reviews,
        photons = photons,
        ingress = ArtifactPhotonIngress { photon ->
            photons.save(photon)
            ArtifactReentryReceipt(accepted = true)
        },
        authorizedOwnerActorId = "private-owner",
        onApproved = onApproved,
    )

    private class Reviews : OwnerAssetReviewRepository {
        private val records = linkedMapOf<OwnerAssetReviewCandidateId, OwnerAssetReviewRecord>()

        override suspend fun stage(candidate: OwnerAssetReviewCandidate): OwnerAssetReviewRecord {
            val current = records[candidate.id]
            if (current != null) {
                require(current.candidate == candidate)
                return current
            }
            return OwnerAssetReviewRecord(candidate).also { records[candidate.id] = it }
        }

        override suspend fun load(candidateId: OwnerAssetReviewCandidateId): OwnerAssetReviewRecord? =
            records[candidateId]

        override suspend fun loadAll(): List<OwnerAssetReviewRecord> = records.values.toList()

        override suspend fun recordDecision(decision: OwnerAssetReviewDecisionRecord): OwnerAssetReviewRecord {
            val current = requireNotNull(records[decision.candidateId])
            current.decision?.let {
                require(it == decision)
                return current
            }
            return current.copy(decision = decision).also { records[decision.candidateId] = it }
        }

        override suspend fun markPublished(
            candidateId: OwnerAssetReviewCandidateId,
            publishedAt: Instant,
        ): OwnerAssetReviewRecord {
            val current = requireNotNull(records[candidateId])
            current.publishedAt?.let { return current }
            return current.copy(publishedAt = publishedAt).also { records[candidateId] = it }
        }
    }

    private class Photons : PhotonRepository {
        private val values = linkedMapOf<PhotonId, Photon>()

        override suspend fun save(photon: Photon) {
            values[photon.id] = photon
        }

        override suspend fun load(id: PhotonId): Photon? = values[id]
        override suspend fun loadAll(): List<Photon> = values.values.toList()
        override suspend fun loadReport(): PhotonLoadReport = PhotonLoadReport(values.values.toList(), emptyList())
        override suspend fun delete(id: PhotonId) {
            values.remove(id)
        }
    }
}
