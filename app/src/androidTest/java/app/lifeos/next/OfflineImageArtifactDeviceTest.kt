package app.lifeos.next

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lifeos.core.model.Photon
import app.lifeos.core.model.Provenance
import app.lifeos.core.runtime.artifact.ArtifactKind
import app.lifeos.next.kernel.ImageGenerationResult
import app.lifeos.next.kernel.KernelBootstrapState
import java.security.MessageDigest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real Android proof for the productive offline image path:
 * prompt -> GoalFrame -> renderer -> PNG -> encrypted BinaryAssetStore -> reload/decode -> IMAGE
 * artifact revision -> DERIVED generation Photon.
 */
@RunWith(AndroidJUnit4::class)
class OfflineImageArtifactDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app: LifeOsApplication
        get() = instrumentation.targetContext.applicationContext as LifeOsApplication

    @Test
    fun promptRendersPngReloadsAndEntersArtifactLifecycle() = runBlocking {
        assertTrue("Kernel must complete boot before image E2E", awaitBoot().ready)

        val prompt = "Erzeuge ein Bild von zwei Leuten, die Fußball spielen."
        val user = Photon(
            content = prompt,
            provenance = Provenance(
                source = "offline-image-artifact-device-test",
                actor = "user",
            ),
            tags = setOf(
                "chat",
                "chat:user",
                "conversation:default",
                "turn:offline-image-artifact-e2e",
            ),
        )

        val submission = app.kernel.persistUserUtterance(user)
        assertNull("Image prompt must not fail language/action execution", submission.languageFailure)
        val result = submission.imageGeneration
        assertTrue("Prompt must execute the productive image action", result is ImageGenerationResult.Generated)
        val generated = (result as ImageGenerationResult.Generated).value

        val descriptor = generated.descriptor
        assertEquals("image/png", descriptor.asset.mediaType)
        assertTrue(descriptor.width > 0)
        assertTrue(descriptor.height > 0)
        assertEquals(generated.rendererId, descriptor.rendererId)

        val firstReload = app.kernel.loadImageAsset(generated.image.photon)
        assertNotNull("Generated Image-Photon must reload its encrypted asset", firstReload)
        val png = firstReload!!
        assertTrue("PNG must contain payload bytes", png.size > PNG_SIGNATURE.size)
        assertArrayEquals(PNG_SIGNATURE, png.copyOfRange(0, PNG_SIGNATURE.size))
        assertEquals(descriptor.asset.byteCount, png.size.toLong())
        assertEquals(descriptor.asset.sha256, sha256(png))

        val bitmap = BitmapFactory.decodeByteArray(png, 0, png.size)
        assertNotNull("Android BitmapFactory must decode the generated PNG", bitmap)
        bitmap!!
        try {
            assertEquals(descriptor.width, bitmap.width)
            assertEquals(descriptor.height, bitmap.height)
        } finally {
            bitmap.recycle()
        }

        val secondReload = app.kernel.loadImageAsset(generated.image.photon)
        assertNotNull("Repeated encrypted-vault reload must remain readable", secondReload)
        assertArrayEquals(png, secondReload!!)

        val artifactGeneration = generated.artifactGeneration
        assertNotNull("Productive image generation must attach the IMAGE artifact lifecycle", artifactGeneration)
        artifactGeneration!!

        val artifact = artifactGeneration.finalization.artifact
        val revision = artifact.revision
        assertNotNull("Generated IMAGE artifact requires an immutable revision manifest", revision)
        revision!!
        assertEquals(ArtifactKind.IMAGE, artifact.request.kind)
        assertEquals("image/png", artifact.request.targetMimeType)
        assertEquals(descriptor.asset, revision.materializedAsset)
        assertTrue(revision.validation.result.isValid)
        assertTrue("Source Photon must remain in IMAGE artifact lineage", user.id in revision.inputPhotonIds)
        assertTrue(
            "Scene Photon must remain in IMAGE artifact lineage",
            generated.scene.photon.id in revision.inputPhotonIds,
        )
        assertTrue(
            "Image reference Photon must remain in IMAGE artifact lineage",
            generated.image.photon.id in revision.inputPhotonIds,
        )
        assertTrue("Goal plus source/scene/image lineage must be retained", revision.inputPhotonIds.size >= 4)
        assertEquals(setOf("image-renderer", "scene-compiler"), revision.participatingModules)
        assertTrue(artifactGeneration.finalization.reentry.accepted)
        assertTrue(artifactGeneration.generationReentry.accepted)

        val generationPhoton = artifactGeneration.generationPhoton
        assertTrue("artifact-generation" in generationPhoton.tags)
        assertTrue("artifact-generation-profile:image" in generationPhoton.tags)
        assertTrue("artifact-output-sha256:${descriptor.asset.sha256}" in generationPhoton.tags)
        assertTrue(
            "artifact-image-size:${descriptor.width}x${descriptor.height}" in generationPhoton.tags,
        )
        assertTrue(
            artifact.photon.id in generationPhoton.provenance.parentIds,
        )
        assertTrue(
            revision.inputPhotonIds.all { it in generationPhoton.provenance.parentIds },
        )
        assertTrue(
            generationPhoton.content.contains("\"promptFingerprint\":\"${sha256(prompt.toByteArray())}\""),
        )
        assertTrue(
            generationPhoton.content.contains("\"model\":\"${generated.rendererId}\""),
        )
    }

    private suspend fun awaitBoot(): KernelBootstrapState = withTimeout(BOOT_TIMEOUT_MS) {
        app.kernel.bootstrapState.first { state -> state.ready || state.failureMessage != null }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }

    companion object {
        private const val BOOT_TIMEOUT_MS = 20_000L
        private val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(),
            0x50,
            0x4e,
            0x47,
            0x0d,
            0x0a,
            0x1a,
            0x0a,
        )
    }
}
