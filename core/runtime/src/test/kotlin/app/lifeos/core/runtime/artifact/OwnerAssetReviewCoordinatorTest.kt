package app.lifeos.core.runtime.artifact

import app.lifeos.core.model.AssetId
import app.lifeos.core.model.AssetRef
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonLoadReport
import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.model.Provenance
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class OwnerAssetReviewCoordinatorTest {
    private val now = Instant.parse("2026-09-13T19:00:00Z")

    @Test
    fun `approval publishes decision then staged parents before children exactly once`() = runTest {
        val reviews = InMemoryReviews()
        val photons = RecordingPhotons()
        val ingressOrder = mutableListOf<PhotonId>()
        val coordinator = OwnerAssetReviewCoordinator(
            reviews = reviews,
            photons = photons,
            ingress = ArtifactPhotonIngress { photon ->
                ingressOrder += photon.id
                photons.save(photon)
                ArtifactReentryReceipt(accepted = true, durableTaskId = "task-${ingressOrder.size}")
            },
            authorizedOwnerActorId = OWNER,
        )
        val parent = stagedPhoton("parent")
        val child = stagedPhoton("child", parents = setOf(parent.id))
        val candidate = candidate(staged = listOf(child, parent))
        coordinator.stage(candidate)

        val first = coordinator.decide(
            candidateId = candidate.id,
            decision = OwnerAssetReviewDecision.APPROVED,
            ownerActorId = OWNER,
            feedback = "passt",
            decidedAt = now.plusSeconds(5),
        )

        assertEquals(
            listOf(first.decisionPhoton.id, parent.id, child.id),
            ingressOrder,
        )
        assertEquals(listOf(parent.id, child.id), first.newlyPublishedPhotonIds)
        assertEquals(now.plusSeconds(5), first.record.publishedAt)

        val second = coordinator.decide(
            candidateId = candidate.id,
            decision = OwnerAssetReviewDecision.APPROVED,
            ownerActorId = OWNER,
            feedback = "passt",
            decidedAt = now.plusSeconds(999),
        )

        assertEquals(3, ingressOrder.size)
        assertTrue(second.newlyPublishedPhotonIds.isEmpty())
        assertEquals(first.record, second.record)
        assertEquals(first.decisionPhoton, second.decisionPhoton)
    }

    @Test
    fun `unauthorized actor cannot create a review decision or publish anything`() = runTest {
        val reviews = InMemoryReviews()
        val photons = RecordingPhotons()
        val ingressOrder = mutableListOf<PhotonId>()
        val coordinator = OwnerAssetReviewCoordinator(
            reviews = reviews,
            photons = photons,
            ingress = ArtifactPhotonIngress { photon ->
                ingressOrder += photon.id
                photons.save(photon)
                ArtifactReentryReceipt(true)
            },
            authorizedOwnerActorId = OWNER,
        )
        val staged = stagedPhoton("asset")
        val candidate = candidate(staged = listOf(staged))
        coordinator.stage(candidate)

        assertFailsWith<IllegalArgumentException> {
            coordinator.decide(
                candidateId = candidate.id,
                decision = OwnerAssetReviewDecision.APPROVED,
                ownerActorId = "module-image-renderer",
                feedback = null,
                decidedAt = now.plusSeconds(1),
            )
        }

        assertNull(reviews.load(candidate.id)?.decision)
        assertNull(reviews.load(candidate.id)?.publishedAt)
        assertTrue(ingressOrder.isEmpty())
        assertNull(photons.load(staged.id))
    }

    @Test
    fun `decision timestamp cannot predate generated candidate`() = runTest {
        val reviews = InMemoryReviews()
        val photons = RecordingPhotons()
        val coordinator = OwnerAssetReviewCoordinator(
            reviews = reviews,
            photons = photons,
            ingress = ArtifactPhotonIngress { photon ->
                photons.save(photon)
                ArtifactReentryReceipt(true)
            },
            authorizedOwnerActorId = OWNER,
        )
        val candidate = candidate(staged = listOf(stagedPhoton("asset")))
        coordinator.stage(candidate)

        assertFailsWith<IllegalArgumentException> {
            coordinator.decide(
                candidateId = candidate.id,
                decision = OwnerAssetReviewDecision.REJECTED,
                ownerActorId = OWNER,
                feedback = null,
                decidedAt = now.minusNanos(1),
            )
        }
        assertNull(reviews.load(candidate.id)?.decision)
    }

    @Test
    fun `changes requested records feedback but does not publish staged asset`() = runTest {
        val reviews = InMemoryReviews()
        val photons = RecordingPhotons()
        val ingressOrder = mutableListOf<PhotonId>()
        val coordinator = OwnerAssetReviewCoordinator(
            reviews = reviews,
            photons = photons,
            ingress = ArtifactPhotonIngress { photon ->
                ingressOrder += photon.id
                photons.save(photon)
                ArtifactReentryReceipt(true)
            },
            authorizedOwnerActorId = OWNER,
        )
        val staged = stagedPhoton("asset")
        val candidate = candidate(staged = listOf(staged))
        coordinator.stage(candidate)

        val result = coordinator.decide(
            candidate.id,
            OwnerAssetReviewDecision.CHANGES_REQUESTED,
            OWNER,
            "Bitte den Kontrast reduzieren",
            now.plusSeconds(2),
        )

        assertEquals(listOf(result.decisionPhoton.id), ingressOrder)
        assertNull(photons.load(staged.id))
        assertNull(result.record.publishedAt)
        assertEquals("Bitte den Kontrast reduzieren", result.record.decision?.feedback)
    }

    @Test
    fun `reconcile completes interrupted approved publication without duplicate ingress`() = runTest {
        val reviews = InMemoryReviews()
        val photons = RecordingPhotons()
        val ingressOrder = mutableListOf<PhotonId>()
        val candidate = candidate(staged = listOf(stagedPhoton("asset")))
        reviews.stage(candidate)
        val decision = decision(candidate, OwnerAssetReviewDecision.APPROVED, now.plusSeconds(1))
        reviews.recordDecision(decision)
        val coordinator = OwnerAssetReviewCoordinator(
            reviews = reviews,
            photons = photons,
            ingress = ArtifactPhotonIngress { photon ->
                ingressOrder += photon.id
                photons.save(photon)
                ArtifactReentryReceipt(true)
            },
            authorizedOwnerActorId = OWNER,
        )

        val first = coordinator.reconcileApproved()
        val second = coordinator.reconcileApproved()

        assertEquals(1, first.size)
        assertEquals(1, second.size)
        assertEquals(2, ingressOrder.size)
        assertEquals(candidate.stagedPhotons.single(), photons.load(candidate.stagedPhotons.single().id))
        assertEquals(decision.decidedAt, reviews.load(candidate.id)?.publishedAt)
    }

    @Test
    fun `reconcile rejects persisted approval from unauthorized actor`() = runTest {
        val reviews = InMemoryReviews()
        val photons = RecordingPhotons()
        val candidate = candidate(staged = listOf(stagedPhoton("asset")))
        reviews.stage(candidate)
        reviews.recordDecision(
            OwnerAssetReviewDecisionRecord(
                candidateId = candidate.id,
                decision = OwnerAssetReviewDecision.APPROVED,
                ownerActorId = "module-image-renderer",
                decidedAt = now.plusSeconds(1),
                decisionPhotonId = PhotonId("unauthorized_owner_review"),
            )
        )
        val coordinator = OwnerAssetReviewCoordinator(
            reviews = reviews,
            photons = photons,
            ingress = ArtifactPhotonIngress { photon ->
                photons.save(photon)
                ArtifactReentryReceipt(true)
            },
            authorizedOwnerActorId = OWNER,
        )

        assertFailsWith<IllegalArgumentException> { coordinator.reconcileApproved() }
        assertNull(reviews.load(candidate.id)?.publishedAt)
        assertTrue(photons.loadAll().isEmpty())
    }

    @Test
    fun `canonical identity collision fails closed`() = runTest {
        val reviews = InMemoryReviews()
        val photons = RecordingPhotons()
        val staged = stagedPhoton("asset")
        val candidate = candidate(staged = listOf(staged))
        reviews.stage(candidate)
        photons.save(staged.copy(content = "different canonical content"))
        val coordinator = OwnerAssetReviewCoordinator(
            reviews = reviews,
            photons = photons,
            ingress = ArtifactPhotonIngress { photon ->
                photons.save(photon)
                ArtifactReentryReceipt(true)
            },
            authorizedOwnerActorId = OWNER,
        )

        assertFailsWith<IllegalArgumentException> {
            coordinator.decide(
                candidate.id,
                OwnerAssetReviewDecision.APPROVED,
                OWNER,
                null,
                now.plusSeconds(1),
            )
        }
        assertNull(reviews.load(candidate.id)?.publishedAt)
    }

    @Test
    fun `cyclic staged dependencies fail closed before asset publication`() = runTest {
        val reviews = InMemoryReviews()
        val photons = RecordingPhotons()
        val aId = PhotonId("staged_a")
        val bId = PhotonId("staged_b")
        val a = stagedPhoton("a", id = aId, parents = setOf(bId))
        val b = stagedPhoton("b", id = bId, parents = setOf(aId))
        val candidate = candidate(staged = listOf(a, b))
        reviews.stage(candidate)
        val coordinator = OwnerAssetReviewCoordinator(
            reviews = reviews,
            photons = photons,
            ingress = ArtifactPhotonIngress { photon ->
                photons.save(photon)
                ArtifactReentryReceipt(true)
            },
            authorizedOwnerActorId = OWNER,
        )

        assertFailsWith<IllegalArgumentException> {
            coordinator.decide(
                candidate.id,
                OwnerAssetReviewDecision.APPROVED,
                OWNER,
                null,
                now.plusSeconds(1),
            )
        }
        assertNull(photons.load(a.id))
        assertNull(photons.load(b.id))
    }

    private fun candidate(staged: List<Photon>): OwnerAssetReviewCandidate =
        OwnerAssetReviewCandidate.create(
            subjectType = OwnerAssetReviewSubjectType.COLLABORATIVE_ARTIFACT,
            subjectId = "artifact-1",
            revisionKey = "revision-1",
            kind = ArtifactKind.IMAGE,
            title = "Generated image",
            targetMimeType = "image/png",
            createdAt = now,
            participatingModules = setOf("scene-compiler", "image-renderer"),
            inputPhotonIds = setOf(PhotonId("source"), PhotonId("goal")),
            materializedAsset = AssetRef(
                AssetId("asset_owner_review"),
                "image/png",
                12,
                "a".repeat(64),
            ),
            stagedPhotons = staged,
        )

    private fun stagedPhoton(
        suffix: String,
        id: PhotonId = PhotonId("staged_$suffix"),
        parents: Set<PhotonId> = emptySet(),
    ): Photon = Photon(
        id = id,
        content = "payload-$suffix",
        provenance = Provenance(
            source = "test",
            actor = "module-$suffix",
            createdAt = now,
            parentIds = parents,
        ),
        tags = setOf("artifact"),
    )

    private fun decision(
        candidate: OwnerAssetReviewCandidate,
        value: OwnerAssetReviewDecision,
        decidedAt: Instant,
    ): OwnerAssetReviewDecisionRecord = OwnerAssetReviewDecisionRecord(
        candidateId = candidate.id,
        decision = value,
        ownerActorId = OWNER,
        feedback = null,
        decidedAt = decidedAt,
        decisionPhotonId = PhotonId("owner_asset_review_fixture"),
    )

    private class InMemoryReviews : OwnerAssetReviewRepository {
        private val records = linkedMapOf<OwnerAssetReviewCandidateId, OwnerAssetReviewRecord>()

        override suspend fun stage(candidate: OwnerAssetReviewCandidate): OwnerAssetReviewRecord {
            val existing = records[candidate.id]
            if (existing != null) {
                require(existing.candidate == candidate)
                return existing
            }
            return OwnerAssetReviewRecord(candidate).also { records[candidate.id] = it }
        }

        override suspend fun load(candidateId: OwnerAssetReviewCandidateId): OwnerAssetReviewRecord? =
            records[candidateId]

        override suspend fun loadAll(): List<OwnerAssetReviewRecord> =
            records.values.sortedBy { it.candidate.id.value }

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

    private class RecordingPhotons : PhotonRepository {
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

    private companion object {
        const val OWNER = "private-owner"
    }
}
