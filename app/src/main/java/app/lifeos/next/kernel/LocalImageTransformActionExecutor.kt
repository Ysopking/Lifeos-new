package app.lifeos.next.kernel

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.lifeos.core.image.DeterministicPngEncoder
import app.lifeos.core.image.ImageAssetDescriptor
import app.lifeos.core.image.LocalImageTransformEngine
import app.lifeos.core.image.Rgba8Image
import app.lifeos.core.image.TransformedImagePhotonFactory
import app.lifeos.core.model.BinaryAssetStore
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.runtime.goal.LocalImageTransformGoalEngine
import app.lifeos.core.runtime.goal.LocalImageTransformPlanResult
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Android asset bridge for the pure deterministic image transform engine. */
class LocalImageTransformActionExecutor(
    private val photons: PhotonRepository,
    private val assets: BinaryAssetStore,
    @Suppress("UNUSED_PARAMETER")
    persistAndIngest: suspend (Photon) -> PhotonSubmissionResult,
    private val planner: LocalImageTransformGoalEngine = LocalImageTransformGoalEngine(),
    private val transformer: LocalImageTransformEngine = LocalImageTransformEngine(),
    private val pngEncoder: DeterministicPngEncoder = DeterministicPngEncoder(),
    private val photonFactory: TransformedImagePhotonFactory = TransformedImagePhotonFactory(),
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    suspend fun execute(context: GoalActionContext): LocalImageTransformExecutionResult {
        return try {
            when (val plan = planner.plan(context.goal, photons.loadAll())) {
                is LocalImageTransformPlanResult.Blocked ->
                    LocalImageTransformExecutionResult.Blocked(plan.reason)
                is LocalImageTransformPlanResult.Unsupported ->
                    LocalImageTransformExecutionResult.Failed(
                        "Local image transform executor does not support ${plan.intent.name}"
                    )
                is LocalImageTransformPlanResult.Prepared -> executePrepared(context, plan)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            LocalImageTransformExecutionResult.Failed(
                error.message ?: error::class.simpleName ?: "local image transformation failed"
            )
        }
    }

    private suspend fun executePrepared(
        context: GoalActionContext,
        plan: LocalImageTransformPlanResult.Prepared,
    ): LocalImageTransformExecutionResult {
        val descriptor = runCatching { ImageAssetDescriptor.decode(plan.sourcePhoton.content) }
            .getOrElse {
                return LocalImageTransformExecutionResult.Failed("image-transform-source-reference-invalid")
            }
        val pixelCount = descriptor.width.toLong() * descriptor.height.toLong()
        if (pixelCount <= 0L || pixelCount > MAX_TRANSFORM_PIXELS) {
            return LocalImageTransformExecutionResult.Blocked("image-transform-pixel-budget-exceeded")
        }
        val encodedSource = assets.load(descriptor.asset)
            ?: return LocalImageTransformExecutionResult.Failed("image-transform-source-asset-missing")

        val transformed = withContext(computeDispatcher) {
            decodeVerifiedRgba(encodedSource, descriptor)?.let { sourceRgba ->
                transformer.transform(sourceRgba, plan.operations)
            }
        } ?: return LocalImageTransformExecutionResult.Failed("image-transform-source-decode-failed")

        val rawPixels = transformed.copyRgba()
        val pixelSha = sha256(rawPixels)
        val pngBytes = withContext(computeDispatcher) { pngEncoder.encode(transformed) }
        val asset = assets.save(pngBytes, "image/png")
        return try {
            val outputDescriptor = ImageAssetDescriptor(
                asset = asset,
                width = transformed.width,
                height = transformed.height,
                pixelSha256 = pixelSha,
                sceneId = "transform-${pixelSha.take(24)}",
                rendererId = RENDERER_ID,
            )
            val outputPhoton = photonFactory.create(
                descriptor = outputDescriptor,
                sourceImageId = plan.sourcePhoton.id,
                goalPhotonId = context.goalPhotonId,
                operations = plan.operations,
                confidence = minOf(plan.sourcePhoton.confidence, context.goal.confidence),
                createdAt = Instant.now(),
            )
            LocalImageTransformExecutionResult.Transformed(
                sourcePhotonId = plan.sourcePhoton.id,
                operations = plan.operations,
                output = PhotonSubmissionResult(
                    photon = outputPhoton,
                    processingQueued = false,
                    processingFailure = AWAITING_OWNER_REVIEW,
                ),
            )
        } catch (cancelled: CancellationException) {
            runCatching { assets.delete(asset.id) }
            throw cancelled
        } catch (error: Exception) {
            runCatching { assets.delete(asset.id) }
            LocalImageTransformExecutionResult.Failed(
                error.message ?: error::class.simpleName ?: "image transform commit failed"
            )
        }
    }

    private fun decodeVerifiedRgba(
        pngBytes: ByteArray,
        descriptor: ImageAssetDescriptor,
    ): Rgba8Image? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size, bounds)
        if (bounds.outWidth != descriptor.width || bounds.outHeight != descriptor.height) return null

        val bitmap = BitmapFactory.decodeByteArray(
            pngBytes,
            0,
            pngBytes.size,
            BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 },
        ) ?: return null
        return try {
            if (bitmap.width != descriptor.width || bitmap.height != descriptor.height) return null
            val argb = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(argb, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val rgba = ByteArray(argb.size * 4)
            argb.forEachIndexed { index, color ->
                val base = index * 4
                rgba[base] = ((color ushr 16) and 0xff).toByte()
                rgba[base + 1] = ((color ushr 8) and 0xff).toByte()
                rgba[base + 2] = (color and 0xff).toByte()
                rgba[base + 3] = ((color ushr 24) and 0xff).toByte()
            }
            Rgba8Image(bitmap.width, bitmap.height, rgba)
        } finally {
            bitmap.recycle()
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val MAX_TRANSFORM_PIXELS = 8_000_000L
        const val RENDERER_ID = "local-image-transform-v1"
        const val AWAITING_OWNER_REVIEW = "awaiting-owner-review"
    }
}
