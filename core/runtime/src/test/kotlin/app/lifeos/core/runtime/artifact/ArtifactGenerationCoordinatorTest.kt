package app.lifeos.core.runtime.artifact

import app.lifeos.core.model.AssetId
import app.lifeos.core.model.AssetRef
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonLoadReport
import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.SemanticArtifactPlan
import app.lifeos.core.model.SemanticArtifactKind
import app.lifeos.core.model.SemanticArtifactClaim
import app.lifeos.core.model.PhotonRevisionRef
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ArtifactGenerationCoordinatorTest {
    private val requestedAt = Instant.parse("2026-09-13T04:00:00Z")
    private val finalizedAt = requestedAt.plusSeconds(10)

    @Test
    fun `document generation provenance is deterministic and replay safe`() = runTest {
        val fixture = fixture(ArtifactKind.DOCUMENT, "text/markdown")
        val profile = DocumentArtifactProfile(format = "markdown", style = "clinical-report")
        val generation = fixture.generation(profile)

        val first = fixture.coordinator.finalize(generation)
        val replay = fixture.coordinator.finalize(generation.copy(finalizedAt = finalizedAt.plusSeconds(60)))

        assertEquals(first.finalization.artifact.photon, replay.finalization.artifact.photon)
        assertEquals(first.generationPhoton, replay.generationPhoton)
        assertEquals(2, fixture.repository.saveCount)
        assertEquals(4, fixture.ingressed.size)
        assertTrue("artifact-generation-profile:document" in first.generationPhoton.tags)
        assertTrue("artifact-document-format:markdown" in first.generationPhoton.tags)
        assertTrue(first.generationPhoton.content.contains("\"style\":\"clinical-report\""))
        assertTrue(first.generationPhoton.provenance.parentIds.contains(first.finalization.artifact.photon.id))
    }

    @Test
    fun `same code artifact keeps content revision but records distinct generation profiles`() = runTest {
        val fixture = fixture(ArtifactKind.CODE, "text/plain")
        val kotlin = fixture.coordinator.finalize(
            fixture.generation(
                CodeArtifactProfile(
                    language = "kotlin",
                    entrypoint = "Main.kt",
                    files = setOf("Main.kt", "Runtime.kt"),
                )
            )
        )
        val java = fixture.coordinator.finalize(
            fixture.generation(
                CodeArtifactProfile(
                    language = "java",
                    entrypoint = "Main.java",
                    files = setOf("Main.java"),
                )
            )
        )

        assertEquals(kotlin.finalization.artifact.photon.id, java.finalization.artifact.photon.id)
        assertNotEquals(kotlin.generationPhoton.id, java.generationPhoton.id)
        assertTrue("artifact-code-language:kotlin" in kotlin.generationPhoton.tags)
        assertTrue("artifact-code-language:java" in java.generationPhoton.tags)
        assertTrue(kotlin.generationPhoton.content.contains("\"entrypoint\":\"Main.kt\""))
        assertEquals(3, fixture.repository.saveCount)
    }

    @Test
    fun `image profile records render geometry prompt and model without embedding binary bytes`() = runTest {
        val fixture = fixture(ArtifactKind.IMAGE, "image/png")
        val promptFingerprint = "b".repeat(64)
        val result = fixture.coordinator.finalize(
            fixture.generation(
                ImageArtifactProfile(
                    width = 1536,
                    height = 1024,
                    promptFingerprint = promptFingerprint,
                    model = "local-render-v2",
                )
            )
        )

        val photon = result.generationPhoton
        assertTrue("artifact-image-size:1536x1024" in photon.tags)
        assertTrue("artifact-image-prompt:$promptFingerprint" in photon.tags)
        assertTrue(photon.content.contains("\"model\":\"local-render-v2\""))
        assertTrue(photon.content.contains("\"sha256\":\"${fixture.asset.sha256}\""))
        assertTrue(!photon.content.contains("PNG_BINARY_BYTES"))
    }

    @Test
    fun `generation profile kind mismatch fails closed before persistence`() {
        val fixture = fixture(ArtifactKind.IMAGE, "image/png")

        assertFailsWith<IllegalArgumentException> {
            fixture.generation(DocumentArtifactProfile(format = "markdown"))
        }
        assertEquals(0, fixture.repository.saveCount)
    }

    private fun fixture(kind: ArtifactKind, mimeType: String): Fixture {
        val repository = RecordingPhotonRepository()
        val ingressed = mutableListOf<Photon>()
        val ingress = ArtifactPhotonIngress { photon ->
            ingressed += photon
            if (repository.load(photon.id) == null) repository.save(photon)
            ArtifactReentryReceipt(true, "task-${ingressed.size}")
        }
        val artifacts = ArtifactCoordinator(repository, ingress)
        return Fixture(
            repository = repository,
            ingressed = ingressed,
            coordinator = ArtifactGenerationCoordinator(artifacts, ingress),
            request = CollaborativeArtifactRequest(
                id = ArtifactId("generated-${kind.name.lowercase()}"),
                kind = kind,
                title = "Generated ${kind.name.lowercase()}",
                targetMimeType = mimeType,
                requestedAt = requestedAt,
                requiredFields = setOf("draft", "validation"),
            ),
            contributions = listOf(
                contribution("draft-module", "draft", PhotonId("input-draft"), 0.91),
                contribution("validation-module", "validation", PhotonId("input-validation"), 0.96),
            ),
            asset = AssetRef(
                id = AssetId("asset-${kind.name.lowercase()}"),
                mediaType = mimeType,
                byteCount = 128,
                sha256 = "a".repeat(64),
            ),
        )
    }

    private fun contribution(
        module: String,
        field: String,
        parent: PhotonId,
        confidence: Double,
    ): ArtifactContribution = ArtifactContribution.create(
        module = module,
        field = field,
        source = "test",
        provenance = Provenance(
            source = "test",
            actor = module,
            createdAt = requestedAt.plusSeconds(1),
            parentIds = setOf(parent),
        ),
        confidence = confidence,
        content = "$field-content",
        contributedAt = requestedAt.plusSeconds(2),
        claimIds = setOf(field),
    )

    private inner class Fixture(
        val repository: RecordingPhotonRepository,
        val ingressed: MutableList<Photon>,
        val coordinator: ArtifactGenerationCoordinator,
        val request: CollaborativeArtifactRequest,
        val contributions: List<ArtifactContribution>,
        val asset: AssetRef,
    ) {
        fun generation(profile: ArtifactGenerationProfile): ArtifactGenerationRequest {
            val semanticKind = when (request.kind) {
                ArtifactKind.IMAGE -> SemanticArtifactKind.IMAGE
                ArtifactKind.CODE -> SemanticArtifactKind.TASK
                ArtifactKind.DOCUMENT,
                ArtifactKind.REPORT -> if (request.targetMimeType == "application/pdf") {
                    SemanticArtifactKind.PDF
                } else {
                    SemanticArtifactKind.TEXT
                }
                ArtifactKind.OTHER -> SemanticArtifactKind.TEXT
            }
            val semanticPlan = SemanticArtifactPlan(
                kind = semanticKind,
                claims = contributions.map { contribution ->
                    SemanticArtifactClaim(
                        claimId = contribution.claimIds.single(),
                        evidence = contribution.provenance.parentIds.mapTo(linkedSetOf()) {
                            PhotonRevisionRef(it, 1L)
                        },
                        confidenceMicros = (contribution.confidence * 1_000_000.0).toLong(),
                        canonicalContent = contribution.content,
                    )
                },
                sourceWorldRevision = 1L,
            )
            return ArtifactGenerationRequest(
                request = request,
                profile = profile,
                contributions = contributions,
                semanticPlan = semanticPlan,
                finalizedAt = finalizedAt,
                materializedAsset = asset,
            )
        }
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
        override suspend fun loadReport(): PhotonLoadReport = PhotonLoadReport(values.values.toList(), emptyList())
        override suspend fun delete(id: PhotonId) {
            values.remove(id)
        }
    }
}
