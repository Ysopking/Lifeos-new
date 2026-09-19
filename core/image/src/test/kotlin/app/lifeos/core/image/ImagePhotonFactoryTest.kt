package app.lifeos.core.image

import app.lifeos.core.model.AssetId
import app.lifeos.core.model.AssetRef
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.RelationType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImagePhotonFactoryTest {
    @Test
    fun `descriptor round trips without embedding image bytes`() {
        val descriptor = descriptor()
        val encoded = descriptor.encode()

        assertEquals(descriptor, ImageAssetDescriptor.decode(encoded))
        assertTrue(encoded.length < 1024)
        assertTrue(!encoded.contains("iVBOR"))
    }

    @Test
    fun `generated image photon retains all source parents`() {
        val parents = setOf(PhotonId("goal-1"), PhotonId("utterance-1"))
        val photon = ImagePhotonFactory().create(descriptor(), parents, confidence = 0.91)

        assertEquals(ImagePhotonFactory.IMAGE_REFERENCE_MIME, photon.mimeType)
        assertEquals(parents, photon.provenance.parentIds)
        assertEquals(parents, photon.relations.map { it.target }.toSet())
        assertTrue(photon.relations.all { it.type == RelationType.DERIVED_FROM })
        assertTrue("offline" in photon.tags)
        assertTrue("asset-ref" in photon.tags)
    }

    @Test
    fun `generated image inherits only explicit conversation and turn routing tags`() {
        val photon = ImagePhotonFactory().create(
            descriptor = descriptor(),
            parentIds = setOf(PhotonId("source")),
            confidence = 0.9,
            inheritedTags = setOf(
                "conversation:default",
                "turn:turn-7",
                "chat:user",
                "private-unrelated",
            ),
        )

        assertTrue("conversation:default" in photon.tags)
        assertTrue("turn:turn-7" in photon.tags)
        assertTrue("chat:user" !in photon.tags)
        assertTrue("private-unrelated" !in photon.tags)
    }

    private fun descriptor() = ImageAssetDescriptor(
        asset = AssetRef(
            id = AssetId("asset-test"),
            mediaType = "image/png",
            byteCount = 1234,
            sha256 = "a".repeat(64),
        ),
        width = 320,
        height = 180,
        pixelSha256 = "b".repeat(64),
        sceneId = "scene-test",
        rendererId = "mmsi-cpu-reference-v1",
    )
}
