package app.lifeos.next

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lifeos.core.model.Photon
import app.lifeos.core.model.Provenance
import app.lifeos.core.runtime.artifact.ArtifactKind
import app.lifeos.core.runtime.artifact.OwnerAssetReviewDecision
import app.lifeos.next.kernel.ImageGenerationResult
import app.lifeos.next.kernel.KernelBootstrapState
import app.lifeos.next.kernel.PrivateOwnerPolicyBaseline
import app.lifeos.next.ui.chat.ChatImagePreviewLoader
import app.lifeos.next.ui.chat.ChatImagePreviewState
import app.lifeos.next.ui.chat.ChatTimelineItem
import app.lifeos.next.ui.chat.ChatTimelineProjector
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real Android proof for the productive owner-reviewed offline image path:
 * prompt -> renderer -> encrypted PNG + staged exact Photons -> private-owner review -> canonical
 * publication -> reload/decode -> IMAGE artifact revision -> multimodal chat timeline/preview.
 */
@RunWith(AndroidJUnit4::class)
class OfflineImageArtifactDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app: LifeOsApplication
        get() = instrumentation.targetContext.applicationContext as LifeOsApplication

    @Test
    fun promptRendersPngButPublishesOnlyAfterExactOwnerApproval() = runBlocking {
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
        val routing = submission.effectiveRouting
        val blockingGaps = routing?.blockingGaps
            ?.joinToString(separator = ",") { gap ->
                "${gap.requirement.capabilityId.value}:${gap.type.name}"
            }
            .orEmpty()
        assertTrue(
            "Prompt must execute the productive image action; " +
                "intent=${submission.effectiveGoal?.intent}; " +
                "actionReady=${submission.actionReady}; " +
                "routingReady=${routing?.ready}; " +
                "blockingGaps=$blockingGaps; result=$result",
            result is ImageGenerationResult.Generated,
        )
        val generated = (result as ImageGenerationResult.Generated).value
        val candidateId = assertNotNull(
            "Generated image must expose the exact owner-review candidate",
            generated.ownerReviewCandidateId,
        )
        assertFalse("Scene Photon must wait for owner review", generated.scene.processingQueued)
        assertFalse("Image Photon must wait for owner review", generated.image.processingQueued)
        assertEquals("awaiting-owner-review", generated.scene.processingFailure)
        assertEquals("awaiting-owner-review", generated.image.processingFailure)

        val descriptor = generated.descriptor
        assertEquals("image/png", descriptor.asset.mediaType)
        assertTrue(descriptor.width > 0)
        assertTrue(descriptor.height > 0)
        assertEquals(generated.rendererId, descriptor.rendererId)

        // The encrypted binary may exist for private review even though no generated Photon is public.
        val firstReload = app.kernel.loadImageAsset(generated.image.photon)
        assertNotNull("Pending Image-Photon must resolve its encrypted review asset", firstReload)
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

        val artifactGeneration = assertNotNull(
            "Productive image generation must attach the staged IMAGE artifact lifecycle",
            generated.artifactGeneration,
        )
        val artifact = artifactGeneration.finalization.artifact
        val revision = assertNotNull(
            "Generated IMAGE artifact requires an immutable revision manifest",
            artifact.revision,
        )
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
        assertFalse("Artifact re-entry must remain staged before owner approval", artifactGeneration.finalization.reentry.accepted)
        assertFalse("Generation re-entry must remain staged before owner approval", artifactGeneration.generationReentry.accepted)

        val generationPhoton = artifactGeneration.generationPhoton
        assertTrue("artifact-generation" in generationPhoton.tags)
        assertTrue("artifact-generation-profile:image" in generationPhoton.tags)
        assertTrue("artifact-output-sha256:${descriptor.asset.sha256}" in generationPhoton.tags)
        assertTrue(
            "artifact-image-size:${descriptor.width}x${descriptor.height}" in generationPhoton.tags,
        )
        assertTrue(artifact.photon.id in generationPhoton.provenance.parentIds)
        assertTrue(revision.inputPhotonIds.all { it in generationPhoton.provenance.parentIds })
        assertTrue(
            generationPhoton.content.contains(
                "\"promptFingerprint\":\"${sha256(prompt.toByteArray(Charsets.UTF_8))}\""
            ),
        )
        assertTrue(generationPhoton.content.contains("\"model\":\"${generated.rendererId}\""))

        // Fail closed: none of the generated revision is visible to modules/chat before approval.
        val pendingStore = app.kernel.photonStore.loadAll()
        assertNull(app.kernel.photonStore.load(generated.scene.photon.id))
        assertNull(app.kernel.photonStore.load(generated.image.photon.id))
        assertNull(app.kernel.photonStore.load(artifact.photon.id))
        assertNull(app.kernel.photonStore.load(generationPhoton.id))
        assertNull(
            ChatTimelineProjector.project(pendingStore)
                .filterIsInstance<ChatTimelineItem.Image>()
                .singleOrNull { it.photon.id == generated.image.photon.id },
        )

        val reviews = assertNotNull(
            "Owner review coordinator must be installed in productive composition",
            app.photonIngress.ownerAssetReview,
        )
        val staged = assertNotNull(
            "Exact generated image candidate must be durably staged",
            reviews.snapshot().singleOrNull { it.candidate.id == candidateId },
        )
        assertNull(staged.decision)
        assertNull(staged.publishedAt)
        assertEquals(descriptor.asset, staged.candidate.materializedAsset)
        assertTrue(generated.scene.photon in staged.candidate.stagedPhotons)
        assertTrue(generated.image.photon in staged.candidate.stagedPhotons)
        assertTrue(artifact.photon in staged.candidate.stagedPhotons)
        assertTrue(generationPhoton in staged.candidate.stagedPhotons)

        val decisionTime = Instant.now().let { now ->
            if (now.isBefore(staged.candidate.createdAt)) staged.candidate.createdAt else now
        }
        val approval = reviews.decide(
            candidateId = candidateId,
            decision = OwnerAssetReviewDecision.APPROVED,
            ownerActorId = PrivateOwnerPolicyBaseline.ownerActorId.value,
            feedback = null,
            decidedAt = decisionTime,
        )
        assertEquals(OwnerAssetReviewDecision.APPROVED, approval.record.decision?.decision)
        assertNotNull("Approved image candidate must be publication-sealed", approval.record.publishedAt)

        assertEquals(generated.scene.photon, app.kernel.photonStore.load(generated.scene.photon.id))
        assertEquals(generated.image.photon, app.kernel.photonStore.load(generated.image.photon.id))
        assertEquals(artifact.photon, app.kernel.photonStore.load(artifact.photon.id))
        assertEquals(generationPhoton, app.kernel.photonStore.load(generationPhoton.id))

        val timeline = ChatTimelineProjector.project(app.kernel.photonStore.loadAll())
        val imageItem = timeline
            .filterIsInstance<ChatTimelineItem.Image>()
            .singleOrNull { it.photon.id == generated.image.photon.id }
        assertNotNull("Approved generated image must enter the multimodal timeline", imageItem)

        val previewLoader = ChatImagePreviewLoader(app.kernel)
        try {
            val previewState = previewLoader.load(generated.image.photon)
            assertTrue(
                "Approved image preview must load through encrypted kernel asset API: $previewState",
                previewState is ChatImagePreviewState.Ready,
            )
            val preview = (previewState as ChatImagePreviewState.Ready).preview
            assertEquals(descriptor.width, preview.width)
            assertEquals(descriptor.height, preview.height)
            assertEquals(descriptor.rendererId, preview.rendererId)
        } finally {
            previewLoader.clear()
        }

        val secondReload = app.kernel.loadImageAsset(generated.image.photon)
        assertNotNull("Repeated encrypted-vault reload must remain readable", secondReload)
        assertArrayEquals(png, secondReload!!)
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
