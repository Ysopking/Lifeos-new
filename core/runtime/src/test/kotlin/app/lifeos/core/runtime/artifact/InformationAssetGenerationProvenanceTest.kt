package app.lifeos.core.runtime.artifact

import app.lifeos.core.model.AssetId
import app.lifeos.core.model.AssetRef
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonLoadReport
import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.model.Provenance
import app.lifeos.core.runtime.informationasset.InformationAssetId
import app.lifeos.core.runtime.informationasset.InformationAssetRevisionId
import app.lifeos.core.runtime.informationasset.InformationAssetRevisionRef
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class InformationAssetGenerationProvenanceTest {
    private val now = Instant.parse("2026-09-15T16:00:00Z")

    @Test
    fun `generation Photon records exact semantic revision refs`() = runBlocking {
        val refA = semanticRef("asset-a", 'a', "semantic-a")
        val refB = semanticRef("asset-b", 'b', "semantic-b")
        val contributions = listOf(
            contribution("module-a", "field-a", refA, "A"),
            contribution("module-b", "field-b", refB, "B"),
        )
        val repository = InMemoryPhotonRepository()
        val ingress = ArtifactPhotonIngress { photon ->
            repository.save(photon)
            ArtifactReentryReceipt(accepted = true, durableTaskId = "test")
        }
        val artifacts = ArtifactCoordinator(repository, ingress)
        val generation = ArtifactGenerationCoordinator(artifacts, ingress)

        val result = generation.finalize(
            ArtifactGenerationRequest(
                request = request(),
                profile = DocumentArtifactProfile(format = "txt"),
                contributions = contributions,
                finalizedAt = now.plusSeconds(1),
                materializedAsset = outputAsset(),
                semanticInputRevisions = listOf(refB, refA),
            )
        )

        assertTrue(result.generationPhoton.provenance.parentIds.containsAll(setOf(refA.photonId, refB.photonId)))
        assertTrue(result.generationPhoton.content.contains("\"informationAssetInputs\""))
        assertTrue(result.generationPhoton.content.contains(refA.revisionId.value))
        assertTrue(result.generationPhoton.content.contains(refB.revisionId.value))
        assertTrue(result.generationPhoton.tags.any { it.contains(refA.revisionId.value) })
        assertTrue(result.generationPhoton.tags.any { it.contains(refB.revisionId.value) })
    }

    @Test
    fun `generation request rejects semantic ref absent from contribution provenance`() {
        val represented = semanticRef("asset-a", 'a', "semantic-a")
        val missing = semanticRef("asset-b", 'b', "semantic-b")

        assertFailsWith<IllegalArgumentException> {
            ArtifactGenerationRequest(
                request = request(),
                profile = DocumentArtifactProfile(format = "txt"),
                contributions = listOf(
                    contribution("module-a", "field-a", represented, "A"),
                    contribution("module-b", "field-b", represented, "B"),
                ),
                finalizedAt = now.plusSeconds(1),
                materializedAsset = outputAsset(),
                semanticInputRevisions = listOf(represented, missing),
            )
        }
    }

    private fun semanticRef(
        assetId: String,
        revisionChar: Char,
        photonId: String,
    ) = InformationAssetRevisionRef(
        assetId = InformationAssetId(assetId),
        revisionId = InformationAssetRevisionId(revisionChar.toString().repeat(64)),
        photonId = PhotonId(photonId),
    )

    private fun contribution(
        module: String,
        field: String,
        ref: InformationAssetRevisionRef,
        content: String,
    ) = ArtifactContribution.create(
        module = module,
        field = field,
        source = "test:${ref.assetId.value}:${ref.revisionId.value}",
        provenance = Provenance(
            source = "test",
            actor = "test",
            createdAt = now,
            parentIds = setOf(ref.photonId),
        ),
        confidence = 0.9,
        content = content,
        contributedAt = now,
    )

    private fun request() = CollaborativeArtifactRequest(
        id = ArtifactId("generation-semantic-test"),
        kind = ArtifactKind.DOCUMENT,
        title = "Generation semantic test",
        targetMimeType = "text/plain",
        requestedAt = now,
        requiredFields = setOf("field-a", "field-b"),
    )

    private fun outputAsset() = AssetRef(
        id = AssetId("generation-output"),
        mediaType = "text/plain",
        byteCount = 2,
        sha256 = "1".repeat(64),
    )

    private class InMemoryPhotonRepository : PhotonRepository {
        private val photons = linkedMapOf<PhotonId, Photon>()
        override suspend fun save(photon: Photon) { photons[photon.id] = photon }
        override suspend fun load(id: PhotonId): Photon? = photons[id]
        override suspend fun loadAll(): List<Photon> = photons.values.toList()
        override suspend fun loadReport(): PhotonLoadReport = PhotonLoadReport(loadAll(), emptyList())
        override suspend fun delete(id: PhotonId) { photons.remove(id) }
    }
}
