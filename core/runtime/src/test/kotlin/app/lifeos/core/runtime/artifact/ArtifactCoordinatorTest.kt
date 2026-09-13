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
            parentIds = setOf(firstParent),
            confidence = 0.92,
            content = "analysis-content",
            offsetSeconds = 1,
        )
        val validation = contribution(
            module = "validation-module",
            field = "validation",
            parentIds = setOf(secondParent),
            confidence = 0.74,
            content = "validation-content",
            offsetSeconds = 3,
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
        val ingressed = mutableListOf<Photon>()
        val coordinator = coordinator(repository, ingressed)

        val first = coordinator.finalize(request, listOf(validation, analysis), finalizedAt)
        val replay = coordinator.finalize(
            request,
            listOf(analysis, validation),
            finalizedAt.plusSeconds(60),
        )
        val photon = first.artifact.photon

        assertEquals(first.artifact, replay.artifact)
        assertEquals(finalizedAt, replay.artifact.finalizedAt)
        assertEquals(1, repository.saveCount)
        assertEquals(2, ingressed.size)
        assertEquals(photon, ingressed[0])
        assertEquals(photon, ingressed[1])
        assertEquals(0.74, photon.confidence)
        assertEquals(setOf(firstParent, secondParent), photon.provenance.parentIds)
        assertEquals(setOf(firstParent, secondParent), photon.relations.map { it.target }.toSet())
        assertTrue(photon.relations.all { it.type == RelationType.DERIVED_FROM })
        assertEquals(ArtifactCoordinatorContract.ENVELOPE_MIME_TYPE, photon.mimeType)
        assertEquals(1L, photon.revision)
        assertTrue("artifact" in photon.tags)
        assertTrue("artifact-kind:report" in photon.tags)
        assertTrue(photon.content.contains("\"schema\":\"lifeos.collaborative-artifact.v1\""))
        assertTrue(photon.content.contains("\"module\":\"analysis-module\""))
        assertTrue(photon.content.contains("\"field\":\"validation\""))
        assertTrue(photon.content.contains("analysis-content"))
        assertTrue(photon.content.contains("validation-content"))
        assertEquals("analysis", first.artifact.contributions.first().field)
        assertTrue(first.reentry.accepted)
        assertEquals("task-1", first.reentry.durableTaskId)
        assertEquals("task-2", replay.reentry.durableTaskId)
    }

    @Test
    fun `asset revisions are append only and retain parent input and validation lineage`() = runTest {
        val repository = RecordingPhotonRepository()
        val input = sourcePhoton("input-photon")
        val evidence = sourcePhoton("validation-photon")
        repository.save(input)
        repository.save(evidence)
        val ingressed = mutableListOf<Photon>()
        val coordinator = coordinator(repository, ingressed)
        val request = documentRequest("revisioned-document")
        val contributions = validContributions()
        val firstAsset = assetRef("asset-v1", "text/markdown", 120, 'a')
        val evidenceRecord = ArtifactValidationEvidence.create(
            validator = "document-validator",
            check = "structure-and-sources",
            passed = true,
            detail = "Document structure and sources verified",
            observedAt = finalizedAt.minusSeconds(1),
            evidencePhotonIds = setOf(evidence.id),
        )
        val firstLifecycle = ArtifactLifecycle(
            revision = ArtifactRevisionContext(
                revision = 1,
                inputPhotonIds = setOf(input.id),
            ),
            output = ArtifactOutputDescriptor(
                asset = firstAsset,
                profile = DocumentArtifactProfile(format = "markdown", style = "clinical-report"),
                validationEvidence = listOf(evidenceRecord),
            ),
        )

        val first = coordinator.finalize(request, contributions, finalizedAt, firstLifecycle)
        val firstPhoton = first.artifact.photon
        val secondAsset = assetRef("asset-v2", "text/markdown", 144, 'b')
        val secondLifecycle = ArtifactLifecycle(
            revision = ArtifactRevisionContext(
                revision = 2,
                parentPhotonId = firstPhoton.id,
                inputPhotonIds = setOf(input.id),
            ),
            output = ArtifactOutputDescriptor(
                asset = secondAsset,
                profile = DocumentArtifactProfile(format = "markdown", style = "clinical-report-v2"),
                validationEvidence = listOf(
                    evidenceRecord.copy(
                        id = "${evidenceRecord.id}:v2",
                        detail = "Revision two structure and sources verified",
                        observedAt = finalizedAt.plusSeconds(4),
                    )
                ),
            ),
        )
        val second = coordinator.finalize(
            request,
            contributions,
            finalizedAt.plusSeconds(5),
            secondLifecycle,
        )
        val replay = coordinator.finalize(
            request,
            contributions.reversed(),
            finalizedAt.plusSeconds(60),
            secondLifecycle,
        )
        val secondPhoton = second.artifact.photon

        assertNotEquals(firstPhoton.id, secondPhoton.id)
        assertEquals(1L, firstPhoton.revision)
        assertEquals(2L, secondPhoton.revision)
        assertEquals(firstPhoton, repository.load(firstPhoton.id))
        assertEquals(secondPhoton, repository.load(secondPhoton.id))
        assertEquals(second.artifact, replay.artifact)
        assertTrue(firstPhoton.id in secondPhoton.provenance.parentIds)
        assertTrue(input.id in secondPhoton.provenance.parentIds)
        assertTrue(evidence.id in secondPhoton.provenance.parentIds)
        assertTrue(secondPhoton.relations.any {
            it.target == firstPhoton.id && it.type == RelationType.DERIVED_FROM
        })
        assertTrue(secondPhoton.relations.any {
            it.target == evidence.id && it.type == RelationType.SUPPORTS
        })
        assertTrue("artifact-revision:2" in secondPhoton.tags)
        assertTrue("artifact-asset-sha256:${secondAsset.sha256}" in secondPhoton.tags)
        assertTrue(secondPhoton.content.contains("\"schema\":\"lifeos.collaborative-artifact.v2\""))
        assertTrue(secondPhoton.content.contains("\"revision\":2"))
        assertTrue(secondPhoton.content.contains("\"sha256\":\"${secondAsset.sha256}\""))
        assertEquals(3, ingressed.size)
    }

    @Test
    fun `same logical artifact revision cannot fork to different persisted content`() = runTest {
        val repository = RecordingPhotonRepository()
        val coordinator = coordinator(repository)
        val request = documentRequest("fork-guard")
        val contributions = validContributions()
        val firstLifecycle = ArtifactLifecycle(
            output = ArtifactOutputDescriptor(
                asset = assetRef("asset-a", "text/markdown", 10, 'a'),
                profile = DocumentArtifactProfile("markdown"),
            )
        )
        coordinator.finalize(request, contributions, finalizedAt, firstLifecycle)

        val conflicting = firstLifecycle.copy(
            output = ArtifactOutputDescriptor(
                asset = assetRef("asset-b", "text/markdown", 11, 'b'),
                profile = DocumentArtifactProfile("markdown"),
            )
        )

        assertFailsWith<IllegalArgumentException> {
            coordinator.finalize(request, contributions, finalizedAt.plusSeconds(1), conflicting)
        }
    }

    @Test
    fun `revision lineage rejects a parent that is not the immediately preceding revision`() = runTest {
        val repository = RecordingPhotonRepository()
        val coordinator = coordinator(repository)
        val request = documentRequest("revision-gap")
        val contributions = validContributions()
        val first = coordinator.finalize(
            request,
            contributions,
            finalizedAt,
            ArtifactLifecycle(
                output = ArtifactOutputDescriptor(
                    asset = assetRef("gap-v1", "text/markdown", 10, 'a'),
                    profile = DocumentArtifactProfile("markdown"),
                )
            ),
        )

        val invalidThirdRevision = ArtifactLifecycle(
            revision = ArtifactRevisionContext(
                revision = 3,
                parentPhotonId = first.artifact.photon.id,
            ),
            output = ArtifactOutputDescriptor(
                asset = assetRef("gap-v3", "text/markdown", 12, 'c'),
                profile = DocumentArtifactProfile("markdown"),
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            coordinator.finalize(
                request,
                contributions,
                finalizedAt.plusSeconds(2),
                invalidThirdRevision,
            )
        }
    }

    @Test
    fun `document code and image outputs encode specialized deterministic profiles`() = runTest {
        val repository = RecordingPhotonRepository()
        val coordinator = coordinator(repository)
        val contributions = validContributions()
        val cases = listOf(
            Triple(
                CollaborativeArtifactRequest(
                    ArtifactId("document-output"),
                    ArtifactKind.DOCUMENT,
                    "Document",
                    "application/pdf",
                    requestedAt,
                ),
                ArtifactOutputDescriptor(
                    assetRef("document-asset", "application/pdf", 100, 'd'),
                    DocumentArtifactProfile(format = "pdf", style = "formal"),
                ),
                "\"type\":\"document\"",
            ),
            Triple(
                CollaborativeArtifactRequest(
                    ArtifactId("code-output"),
                    ArtifactKind.CODE,
                    "Code",
                    "text/x-kotlin",
                    requestedAt,
                ),
                ArtifactOutputDescriptor(
                    assetRef("code-asset", "text/x-kotlin", 200, 'e'),
                    CodeArtifactProfile(
                        language = "kotlin",
                        entrypoint = "Main.kt",
                        files = setOf("Main.kt", "Runtime.kt"),
                    ),
                ),
                "\"type\":\"code\"",
            ),
            Triple(
                CollaborativeArtifactRequest(
                    ArtifactId("image-output"),
                    ArtifactKind.IMAGE,
                    "Image",
                    "image/png",
                    requestedAt,
                ),
                ArtifactOutputDescriptor(
                    assetRef("image-asset", "image/png", 300, 'f'),
                    ImageArtifactProfile(
                        width = 1024,
                        height = 1024,
                        promptFingerprint = "1".repeat(64),
                        model = "image-runtime",
                    ),
                ),
                "\"type\":\"image\"",
            ),
        )

        cases.forEach { (request, output, expectedType) ->
            val result = coordinator.finalize(
                request,
                contributions,
                finalizedAt,
                ArtifactLifecycle(output = output),
            )
            assertTrue(result.artifact.photon.content.contains(expectedType))
            assertTrue(result.artifact.photon.content.contains(output.asset.sha256))
            assertEquals(output, result.artifact.lifecycle.output)
        }
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

    private fun contribution(
        module: String,
        field: String,
        parentIds: Set<PhotonId> = emptySet(),
        confidence: Double = 0.9,
        content: String = "$field-content",
        offsetSeconds: Long = 1,
    ): ArtifactContribution = ArtifactContribution.create(
        module = module,
        field = field,
        source = "$module-source",
        provenance = Provenance(
            source = "$module-evidence",
            actor = module,
            createdAt = requestedAt.plusSeconds(offsetSeconds),
            parentIds = parentIds,
        ),
        confidence = confidence,
        content = content,
        contributedAt = requestedAt.plusSeconds(offsetSeconds + 1),
    )

    private fun validContributions(): List<ArtifactContribution> = listOf(
        contribution("analysis-module", "analysis", content = "analysis"),
        contribution("validation-module", "validation", content = "validated", offsetSeconds = 3),
    )

    private fun documentRequest(id: String): CollaborativeArtifactRequest = CollaborativeArtifactRequest(
        id = ArtifactId(id),
        kind = ArtifactKind.DOCUMENT,
        title = "Revisioned document",
        targetMimeType = "text/markdown",
        requestedAt = requestedAt,
        requiredFields = setOf("analysis", "validation"),
    )

    private fun assetRef(
        id: String,
        mediaType: String,
        byteCount: Long,
        hashChar: Char,
    ): AssetRef = AssetRef(
        id = AssetId(id),
        mediaType = mediaType,
        byteCount = byteCount,
        sha256 = hashChar.toString().repeat(64),
    )

    private fun sourcePhoton(id: String): Photon = Photon(
        id = PhotonId(id),
        content = "source:$id",
        provenance = Provenance(
            source = "test-source",
            actor = "test",
            createdAt = requestedAt.minusSeconds(1),
        ),
    )

    private fun coordinator(
        repository: RecordingPhotonRepository,
        ingressed: MutableList<Photon> = mutableListOf(),
    ): ArtifactCoordinator = ArtifactCoordinator(
        photons = repository,
        ingress = ArtifactPhotonIngress { photon ->
            ingressed += photon
            if (repository.load(photon.id) == null) repository.save(photon)
            ArtifactReentryReceipt(accepted = true, durableTaskId = "task-${ingressed.size}")
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
