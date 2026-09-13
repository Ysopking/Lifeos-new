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
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ArtifactRetrySemanticsTest {
    private val requestedAt = Instant.parse("2026-09-13T02:00:00Z")
    private val finalizedAt = requestedAt.plusSeconds(10)

    @Test
    fun `same revision and same asset content reuses persisted locator after retry`() = runTest {
        val repository = RecordingPhotonRepository()
        val ingressed = mutableListOf<Photon>()
        val coordinator = ArtifactCoordinator(
            photons = repository,
            ingress = ArtifactPhotonIngress { photon ->
                ingressed += photon
                if (repository.load(photon.id) == null) repository.save(photon)
                ArtifactReentryReceipt(
                    accepted = true,
                    durableTaskId = "retry-task-${ingressed.size}",
                )
            },
        )
        val request = CollaborativeArtifactRequest(
            id = ArtifactId("retry-document"),
            kind = ArtifactKind.DOCUMENT,
            title = "Retry document",
            targetMimeType = "text/markdown",
            requestedAt = requestedAt,
        )
        val contributions = listOf(
            contribution("draft-module", "draft", requestedAt.plusSeconds(1)),
            contribution("validation-module", "validation", requestedAt.plusSeconds(2)),
        )
        val persistedAsset = assetRef("persisted-asset", 'a')
        val firstLifecycle = ArtifactLifecycle(
            output = ArtifactOutputDescriptor(
                asset = persistedAsset,
                profile = DocumentArtifactProfile(format = "markdown", style = "formal"),
            ),
        )

        val first = coordinator.finalize(
            request = request,
            contributions = contributions,
            finalizedAt = finalizedAt,
            lifecycle = firstLifecycle,
        )

        val retryAsset = assetRef("retry-temp-asset", 'a')
        val retry = coordinator.finalize(
            request = request,
            contributions = contributions.reversed(),
            finalizedAt = finalizedAt.plusSeconds(30),
            lifecycle = firstLifecycle.copy(
                output = firstLifecycle.output!!.copy(asset = retryAsset),
            ),
        )

        assertEquals(first.artifact.photon, retry.artifact.photon)
        assertEquals(finalizedAt, retry.artifact.finalizedAt)
        assertEquals(persistedAsset, retry.artifact.lifecycle.output!!.asset)
        assertEquals(2, ingressed.size)
        assertTrue(
            first.artifact.photon.tags.any {
                it.startsWith(ArtifactCoordinatorContract.DEFINITION_FINGERPRINT_TAG_PREFIX)
            }
        )
        assertTrue(
            "${ArtifactCoordinatorContract.ASSET_ID_TAG_PREFIX}${persistedAsset.id.value}" in
                first.artifact.photon.tags
        )
        assertTrue(first.artifact.photon.content.contains(persistedAsset.id.value))
        assertFalse(first.artifact.photon.content.contains(retryAsset.id.value))
    }

    private fun contribution(
        module: String,
        field: String,
        createdAt: Instant,
    ): ArtifactContribution = ArtifactContribution.create(
        module = module,
        field = field,
        source = "$module-source",
        provenance = Provenance(
            source = "$module-evidence",
            actor = module,
            createdAt = createdAt,
        ),
        confidence = 0.9,
        content = "$field-content",
        contributedAt = createdAt.plusSeconds(1),
    )

    private fun assetRef(id: String, hashChar: Char): AssetRef = AssetRef(
        id = AssetId(id),
        mediaType = "text/markdown",
        byteCount = 128,
        sha256 = hashChar.toString().repeat(64),
    )

    private class RecordingPhotonRepository : PhotonRepository {
        private val values = linkedMapOf<PhotonId, Photon>()

        override suspend fun save(photon: Photon) {
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
