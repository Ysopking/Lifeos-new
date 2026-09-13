package app.lifeos.core.runtime.artifact

import app.lifeos.core.model.AssetId
import app.lifeos.core.model.AssetRef
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonLoadReport
import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.RelationType
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ArtifactCoordinatorTest {
    private val requestedAt = Instant.parse("2026-09-11T18:00:00Z")
    private val finalizedAt = Instant.parse("2026-09-11T18:00:10Z")

    @Test
    fun `finalized collaborative artifact uses one canonical ingress and is replay deterministic`() = runTest {
        val firstParent = PhotonId("source-a")
        val secondParent = PhotonId("source-b")
        val analysis = contribution(
            module = "analysis-module",
            field = "analysis",
            source = "analysis-source",
            provenanceSource = "analysis-evidence",
            parent = firstParent,
            confidence = 0.92,
            content = "analysis-content",
            offset = 1,
        )
        val validation = contribution(
            module = "validation-module",
            field = "validation",
            source = "validation-source",
            provenanceSource = "validation-evidence",
            parent = secondParent,
            confidence = 0.74,
            content = "validation-content",
            offset = 3,
        )
        val request = request(ArtifactId("artifact-1"))
        val repository = RecordingPhotonRepository()
        val ingressed = mutableListOf<Photon>()
        val coordinator = coordinator(repository, ingressed)

        val first = coordinator.finalize(request, listOf(validation, analysis), finalizedAt)
        val replay = coordinator.finalize(
            request,
            listOf(analysis, validation),
            finalizedAt.plusSeconds(60),
        )
        val photon = first.artifact.photon
        val revision = assertNotNull(first.artifact.revision)

        assertEquals(first.artifact, replay.artifact)
        assertEquals(finalizedAt, replay.artifact.finalizedAt)
        assertEquals(1, repository.saveCount)
        assertEquals(2, ingressed.size)
        assertEquals(photon, ingressed[0])
        assertEquals(photon, ingressed[1])
        assertEquals(0.74, photon.confidence)
        assertEquals(setOf(firstParent, secondParent), photon.provenance.parentIds)
        assertEquals(setOf(firstParent, secondParent), revision.inputPhotonIds)
        assertEquals(setOf("analysis-module", "validation-module"), revision.participatingModules)
        assertEquals(setOf(firstParent, secondParent), photon.relations.map { it.target }.toSet())
        assertTrue(photon.relations.all { it.type == RelationType.DERIVED_FROM })
        assertTrue(photon.id.value.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        assertEquals(ArtifactCoordinatorContract.ENVELOPE_MIME_TYPE, photon.mimeType)
        assertTrue("artifact" in photon.tags)
        assertTrue("artifact-kind:report" in photon.tags)
        assertTrue("artifact-revision:${revision.id.value}" in photon.tags)
        assertTrue("artifact-state-hash:${revision.stateHash}" in photon.tags)
        assertTrue(photon.content.contains("\"schema\":\"lifeos.collaborative-artifact.v2\""))
        assertTrue(photon.content.contains("\"module\":\"analysis-module\""))
        assertTrue(photon.content.contains("\"field\":\"validation\""))
        assertTrue(photon.content.contains("analysis-content"))
        assertTrue(photon.content.contains("validation-content"))
        assertTrue(photon.content.contains("\"valid\":true"))
        assertEquals("analysis", first.artifact.contributions.first().field)
        assertTrue(first.reentry.accepted)
        assertEquals("task-1", first.reentry.durableTaskId)
        assertEquals("task-2", replay.reentry.durableTaskId)
    }

    @Test
    fun `changed artifact creates immutable child revision with materialized asset lineage`() = runTest {
        val firstParent = PhotonId("source-a")
        val secondParent = PhotonId("source-b")
        val analysis = contribution(
            module = "analysis-module",
            field = "analysis",
            source = "analysis-source",
            provenanceSource = "analysis-evidence",
            parent = firstParent,
            confidence = 0.92,
            content = "analysis-content",
            offset = 1,
        )
        val validation = contribution(
            module = "validation-module",
            field = "validation",
            source = "validation-source",
            provenanceSource = "validation-evidence",
            parent = secondParent,
            confidence = 0.80,
            content = "validation-content",
            offset = 3,
        )
        val request = request(ArtifactId("artifact-revisioned"))
        val repository = RecordingPhotonRepository()
        val ingressed = mutableListOf<Photon>()
        val coordinator = coordinator(repository, ingressed)
        val root = coordinator.finalize(request, listOf(analysis, validation), finalizedAt)
        val changedAnalysis = ArtifactContribution.create(
            module = analysis.module,
            field = analysis.field,
            source = analysis.source,
            provenance = analysis.provenance,
            confidence = analysis.confidence,
            content = "analysis-content-v2",
            contributedAt = analysis.contributedAt,
        )
        val materialized = AssetRef(
            id = AssetId("rendered_v2"),
            mediaType = "text/markdown",
            byteCount = 128,
            sha256 = "a".repeat(64),
        )

        val revised = coordinator.finalize(
            request = request,
            contributions = listOf(changedAnalysis, validation),
            finalizedAt = finalizedAt.plusSeconds(20),
            parentRevision = root.artifact.revisionRef(),
            materializedAsset = materialized,
        )
        val replay = coordinator.finalize(
            request = request,
            contributions = listOf(validation, changedAnalysis),
            finalizedAt = finalizedAt.plusSeconds(120),
            parentRevision = root.artifact.revisionRef(),
            materializedAsset = materialized,
        )
        val rootRevision = assertNotNull(root.artifact.revision)
        val childRevision = assertNotNull(revised.artifact.revision)
        val childPhoton = revised.artifact.photon

        assertNotEquals(rootRevision.id, childRevision.id)
        assertNotEquals(root.artifact.photon.id, childPhoton.id)
        assertEquals(root.artifact.revisionRef(), childRevision.parent)
        assertEquals(materialized, childRevision.materializedAsset)
        assertEquals(setOf(firstParent, secondParent), childRevision.inputPhotonIds)
        assertEquals(
            setOf(firstParent, secondParent, root.artifact.photon.id),
            childPhoton.provenance.parentIds,
        )
        assertTrue(
            childPhoton.relations.any {
                it.target == root.artifact.photon.id && it.type == RelationType.TRANSFORMS
            }
        )
        assertTrue(
            childPhoton.relations.any {
                it.target == firstParent && it.type == RelationType.DERIVED_FROM
            }
        )
        assertTrue("artifact-parent-revision:${rootRevision.id.value}" in childPhoton.tags)
        assertTrue("artifact-output-sha256:${materialized.sha256}" in childPhoton.tags)
        assertTrue(childPhoton.content.contains("\"id\":\"rendered_v2\""))
        assertTrue(childPhoton.content.contains("\"sha256\":\"${materialized.sha256}\""))
        assertEquals(revised.artifact, replay.artifact)
        assertEquals(2, repository.saveCount)
        assertEquals(3, ingressed.size)
    }

    @Test
    fun `revision parent must belong to the same logical artifact`() = runTest {
        val analysis = contribution(
            module = "analysis-module",
            field = "analysis",
            source = "analysis-source",
            provenanceSource = "analysis-evidence",
            parent = PhotonId("source-a"),
            confidence = 0.92,
            content = "analysis-content",
            offset = 1,
        )
        val validation = contribution(
            module = "validation-module",
            field = "validation",
            source = "validation-source",
            provenanceSource = "validation-evidence",
            parent = PhotonId("source-b"),
            confidence = 0.80,
            content = "validation-content",
            offset = 3,
        )
        val repository = RecordingPhotonRepository()
        val ingressed = mutableListOf<Photon>()
        val coordinator = coordinator(repository, ingressed)
        val first = coordinator.finalize(
            request = request(ArtifactId("artifact-a")),
            contributions = listOf(analysis, validation),
            finalizedAt = finalizedAt,
        )

        val failure = assertFailsWith<IllegalArgumentException> {
            coordinator.finalize(
                request = request(ArtifactId("artifact-b")),
                contributions = listOf(analysis, validation),
                finalizedAt = finalizedAt.plusSeconds(10),
                parentRevision = first.artifact.revisionRef(),
            )
        }

        assertTrue(failure.message.orEmpty().contains("belongs to artifact-a"))
        assertEquals(1, repository.saveCount)
        assertEquals(1, ingressed.size)
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
    fun `contribution identity is deterministic and case sensitive for payload content`() {
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
            content = "Content",
        )
        val second = ArtifactContribution.create(
            module = "module-a",
            field = "draft",
            source = "source",
            provenance = provenance,
            confidence = 0.8,
            content = "Content",
        )
        val changedModule = ArtifactContribution.create(
            module = "module-b",
            field = "draft",
            source = "source",
            provenance = provenance,
            confidence = 0.8,
            content = "Content",
        )
        val changedCase = ArtifactContribution.create(
            module = "module-a",
            field = "draft",
            source = "source",
            provenance = provenance,
            confidence = 0.8,
            content = "content",
        )

        assertEquals(first, second)
        assertEquals(first.contentFingerprint(), second.contentFingerprint())
        assertNotEquals(first.id, changedModule.id)
        assertNotEquals(first.id, changedCase.id)
        assertNotEquals(first.contentFingerprint(), changedCase.contentFingerprint())
    }

    private fun request(id: ArtifactId): CollaborativeArtifactRequest = CollaborativeArtifactRequest(
        id = id,
        kind = ArtifactKind.REPORT,
        title = "Runtime report",
        targetMimeType = "text/markdown",
        requestedAt = requestedAt,
        requiredFields = setOf("analysis", "validation"),
    )

    private fun contribution(
        module: String,
        field: String,
        source: String,
        provenanceSource: String,
        parent: PhotonId,
        confidence: Double,
        content: String,
        offset: Long,
    ): ArtifactContribution = ArtifactContribution.create(
        module = module,
        field = field,
        source = source,
        provenance = Provenance(
            source = provenanceSource,
            actor = module,
            createdAt = requestedAt.plusSeconds(offset),
            parentIds = setOf(parent),
        ),
        confidence = confidence,
        content = content,
        contributedAt = requestedAt.plusSeconds(offset + 1),
    )

    private fun coordinator(
        repository: RecordingPhotonRepository,
        ingressed: MutableList<Photon>,
    ): ArtifactCoordinator = ArtifactCoordinator(
        photons = repository,
        ingress = ArtifactPhotonIngress { photon ->
            ingressed += photon
            if (repository.load(photon.id) == null) repository.save(photon)
            ArtifactReentryReceipt(
                accepted = true,
                durableTaskId = "task-${ingressed.size}",
            )
        },
    )

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
