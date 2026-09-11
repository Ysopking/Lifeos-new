package app.lifeos.core.runtime.artifact

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonLoadReport
import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.RelationType
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ArtifactCoordinatorTest {
    private val requestedAt = Instant.parse("2026-09-11T18:00:00Z")
    private val finalizedAt = Instant.parse("2026-09-11T18:00:10Z")

    @Test
    fun `finalized collaborative artifact preserves contribution provenance and is replay deterministic`() = runTest {
        val firstParent = PhotonId("source-a")
        val secondParent = PhotonId("source-b")
        val analysis = ArtifactContribution.create(
            module = "analysis-module",
            field = "analysis",
            source = "analysis-source",
            provenance = Provenance(
                source = "analysis-evidence",
                actor = "analysis-module",
                createdAt = requestedAt.plusSeconds(1),
                parentIds = setOf(firstParent),
            ),
            confidence = 0.92,
            content = "analysis-content",
            contributedAt = requestedAt.plusSeconds(2),
        )
        val validation = ArtifactContribution.create(
            module = "validation-module",
            field = "validation",
            source = "validation-source",
            provenance = Provenance(
                source = "validation-evidence",
                actor = "validation-module",
                createdAt = requestedAt.plusSeconds(3),
                parentIds = setOf(secondParent),
            ),
            confidence = 0.74,
            content = "validation-content",
            contributedAt = requestedAt.plusSeconds(4),
        )
        val request = CollaborativeArtifactRequest(
            id = ArtifactId("artifact-1"),
            kind = ArtifactKind.REPORT,
            title = "Runtime report",
            targetMimeType = "text/markdown",
            requestedAt = requestedAt,
            requiredFields = setOf("analysis", "validation"),
        )
        val repository = RecordingPhotonRepository()
        val reentered = mutableListOf<Photon>()
        val coordinator = ArtifactCoordinator(
            photons = repository,
            reentry = ArtifactPhotonReentry { photon ->
                reentered += photon
                ArtifactReentryReceipt(accepted = true, durableTaskId = "task-1")
            },
        )

        val first = coordinator.finalize(request, listOf(validation, analysis), finalizedAt)
        val replay = coordinator.finalize(request, listOf(analysis, validation), finalizedAt)
        val photon = first.artifact.photon

        assertEquals(first.artifact, replay.artifact)
        assertEquals(1, repository.saveCount)
        assertEquals(2, reentered.size)
        assertEquals(0.74, photon.confidence)
        assertEquals(setOf(firstParent, secondParent), photon.provenance.parentIds)
        assertEquals(setOf(firstParent, secondParent), photon.relations.map { it.target }.toSet())
        assertTrue(photon.relations.all { it.type == RelationType.DERIVED_FROM })
        assertEquals(ArtifactCoordinatorContract.ENVELOPE_MIME_TYPE, photon.mimeType)
        assertTrue("artifact" in photon.tags)
        assertTrue("artifact-kind:report" in photon.tags)
        assertTrue(photon.content.contains("\"module\":\"analysis-module\""))
        assertTrue(photon.content.contains("\"field\":\"validation\""))
        assertTrue(photon.content.contains("analysis-content"))
        assertTrue(photon.content.contains("validation-content"))
        assertEquals("analysis", first.artifact.contributions.first().field)
        assertTrue(first.reentry.accepted)
        assertEquals("task-1", first.reentry.durableTaskId)
    }

    @Test
    fun `validator rejects artifacts that are not cross module or omit a required field`() {
        val contribution = ArtifactContribution.create(
            module = "single-module",
            field = "analysis",
            source = "local",
            provenance = Provenance(
                source = "local",
                actor = "single-module",
                createdAt = requestedAt,
            ),
            confidence = 1.0,
            content = "only contribution",
        )
        val request = CollaborativeArtifactRequest(
            id = ArtifactId("artifact-invalid"),
            kind = ArtifactKind.DOCUMENT,
            title = "Invalid",
            targetMimeType = "text/plain",
            requestedAt = requestedAt,
            requiredFields = setOf("analysis", "validation"),
        )

        val result = ArtifactValidator().validate(
            request = request,
            contributions = listOf(contribution),
            finalizedAt = finalizedAt,
        )

        assertFalse(result.isValid)
        assertTrue(result.issues.any { it.code == ArtifactValidationIssueCode.INSUFFICIENT_MODULE_DIVERSITY })
        assertTrue(result.issues.any { it.code == ArtifactValidationIssueCode.MISSING_REQUIRED_FIELD })
    }

    @Test
    fun `contribution identity is deterministic and includes collaboration metadata`() {
        val provenance = Provenance(
            source = "source",
            actor = "module-a",
            createdAt = requestedAt,
            parentIds = setOf(PhotonId("parent")),
        )

        val first = ArtifactContribution.create(
            module = "module-a",
            field = "draft",
            source = "source",
            provenance = provenance,
            confidence = 0.8,
            content = "content",
        )
        val second = ArtifactContribution.create(
            module = "module-a",
            field = "draft",
            source = "source",
            provenance = provenance,
            confidence = 0.8,
            content = "content",
        )
        val changed = ArtifactContribution.create(
            module = "module-b",
            field = "draft",
            source = "source",
            provenance = provenance,
            confidence = 0.8,
            content = "content",
        )

        assertEquals(first, second)
        assertEquals(first.contentFingerprint(), second.contentFingerprint())
        assertTrue(first.id != changed.id)
    }

    private class RecordingPhotonRepository : PhotonRepository {
        private val values = linkedMapOf<PhotonId, Photon>()
        var saveCount: Int = 0
            private set

        override suspend fun save(photon: Photon) {
            saveCount += 1
            values[photon.id] = photon
        }

        override suspend fun load(id: PhotonId): Photon? = values[id]

        override suspend fun loadAll(): List<Photon> = values.values.toList()

        override suspend fun loadReport(): PhotonLoadReport = PhotonLoadReport(
            photons = values.values.toList(),
            unreadableFiles = emptyList(),
        )

        override suspend fun delete(id: PhotonId) {
            values.remove(id)
        }
    }
}
