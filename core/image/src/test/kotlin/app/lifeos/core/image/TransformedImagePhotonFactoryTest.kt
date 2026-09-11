package app.lifeos.core.image

import app.lifeos.core.model.AssetId
import app.lifeos.core.model.AssetRef
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.RelationType
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TransformedImagePhotonFactoryTest {
    @Test
    fun `transformation creates a new image photon bound to source and goal`() {
        val sourceId = PhotonId("image-source")
        val goalId = PhotonId("goal-transform")
        val operations = listOf(
            LocalImageTransformOperation.BRIGHTER,
            LocalImageTransformOperation.SHARPER,
        )

        val photon = TransformedImagePhotonFactory().create(
            descriptor = descriptor(),
            sourceImageId = sourceId,
            goalPhotonId = goalId,
            operations = operations,
            confidence = 0.88,
            createdAt = Instant.parse("2026-09-10T12:00:00Z"),
        )

        assertEquals(ImagePhotonFactory.IMAGE_REFERENCE_MIME, photon.mimeType)
        assertEquals(setOf(sourceId, goalId), photon.provenance.parentIds)
        assertEquals("offline-image-transform", photon.provenance.source)
        assertTrue(photon.relations.any { it.target == sourceId && it.type == RelationType.TRANSFORMS })
        assertTrue(photon.relations.any { it.target == sourceId && it.type == RelationType.DERIVED_FROM })
        assertTrue(photon.relations.any { it.target == goalId && it.type == RelationType.REFERENCES })
        assertTrue("transformed" in photon.tags)
        assertTrue("result" in photon.tags)
        assertTrue("transform:brighter" in photon.tags)
        assertTrue("transform:sharper" in photon.tags)
    }

    private fun descriptor() = ImageAssetDescriptor(
        asset = AssetRef(
            id = AssetId("asset-transform"),
            mediaType = "image/png",
            byteCount = 4321,
            sha256 = "a".repeat(64),
        ),
        width = 64,
        height = 32,
        pixelSha256 = "b".repeat(64),
        sceneId = "transform-scene",
        rendererId = "local-image-transform-v1",
    )
}
