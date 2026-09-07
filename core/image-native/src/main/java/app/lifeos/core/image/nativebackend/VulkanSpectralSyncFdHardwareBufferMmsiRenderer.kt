package app.lifeos.core.image.nativebackend

import android.content.Context
import android.hardware.HardwareBuffer
import app.lifeos.core.image.SpectralReconstructor
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Asynchronous spectral MMSI phase-3 renderer.
 *
 * Phase 2 provides six regularized spectral-basis coefficients in one AHardwareBuffer. This
 * renderer consumes the Phase-2 SYNC_FD directly, reconstructs target-illuminant linear RGB in
 * the compute shader, applies PBR lighting, and returns a release fence for the output buffer.
 */
class VulkanSpectralSyncFdHardwareBufferMmsiRenderer private constructor(
    private var nativeHandle: Long,
) : Closeable {
    data class DispatchResult(
        val accepted: Boolean,
        val releaseFence: MmsiSyncFence?,
    )

    @Synchronized
    fun dispatch(
        storage: HardwareBufferMmsiInterop.SpectralStorageSet,
        width: Int,
        height: Int,
        parameters: VulkanMmsiRenderer.Parameters,
        rgbProjection: SpectralReconstructor.RgbProjection,
        acquireFence: MmsiSyncFence? = null,
    ): DispatchResult {
        check(nativeHandle != 0L) { "Vulkan spectral sync-fd renderer is closed" }
        require(width > 0 && height > 0)
        val pixels = Math.multiplyExact(width.toLong(), height.toLong())
        require(pixels == storage.pixelCount.toLong()) {
            "SpectralStorageSet pixel count ${storage.pixelCount} does not match ${width}x$height"
        }
        require(rgbProjection.componentCount == COMPONENT_COUNT) {
            "Spectral Vulkan path requires exactly $COMPONENT_COUNT basis coefficients"
        }

        val projectionBuffer = packProjection(rgbProjection)
        val sun = parameters.sunDirection.normalized()
        val acquireFd = acquireFence?.take() ?: -1
        val nativeResult = nativeDispatch(
            nativeHandle,
            storage.spectralCoefficientsVec4x2,
            storage.normalDepthRgba32f,
            storage.roughnessR32f,
            storage.outputRgba32f,
            width,
            height,
            sun.x.toFloat(),
            sun.y.toFloat(),
            sun.z.toFloat(),
            parameters.sunSolidAngleRad,
            parameters.sunColorLinear.r.toFloat(),
            parameters.sunColorLinear.g.toFloat(),
            parameters.sunColorLinear.b.toFloat(),
            parameters.skyAmbientLinear.r.toFloat(),
            parameters.skyAmbientLinear.g.toFloat(),
            parameters.skyAmbientLinear.b.toFloat(),
            parameters.shadowFloor,
            projectionBuffer,
            acquireFd,
        )
        return when {
            nativeResult >= 0 -> DispatchResult(
                accepted = true,
                releaseFence = MmsiSyncFence.fromNative(nativeResult),
            )
            nativeResult == COMPLETED_SYNCHRONOUSLY -> DispatchResult(
                accepted = true,
                releaseFence = null,
            )
            else -> DispatchResult(
                accepted = false,
                releaseFence = null,
            )
        }
    }

    @Synchronized
    override fun close() {
        val handle = nativeHandle
        if (handle == 0L) return
        nativeHandle = 0L
        nativeDestroy(handle)
    }

    private external fun nativeCreate(shaderBuffer: ByteBuffer, shaderBytes: Int): Long

    private external fun nativeDispatch(
        handle: Long,
        coefficientBuffer: HardwareBuffer,
        normalDepthBuffer: HardwareBuffer,
        roughnessBuffer: HardwareBuffer,
        outputBuffer: HardwareBuffer,
        width: Int,
        height: Int,
        sunX: Float,
        sunY: Float,
        sunZ: Float,
        sunSolidAngle: Float,
        sunR: Float,
        sunG: Float,
        sunB: Float,
        skyR: Float,
        skyG: Float,
        skyB: Float,
        shadowFloor: Float,
        rgbProjectionBuffer: ByteBuffer,
        acquireFenceFd: Int,
    ): Int

    private external fun nativeDestroy(handle: Long)

    companion object {
        private const val COMPONENT_COUNT = 6
        private const val PROJECTION_FLOAT_COUNT = 3 + 3 * COMPONENT_COUNT
        private const val COMPLETED_SYNCHRONOUSLY = -1

        fun create(context: Context): VulkanSpectralSyncFdHardwareBufferMmsiRenderer? {
            ensureLoaded()
            val bridge = VulkanMmsiBridge()
            if (!bridge.isSpectralReady(context)) return null
            val shaderBytes = runCatching {
                context.assets.open(VulkanMmsiBridge.SPECTRAL_FORWARD_SHADER_ASSET).use { it.readBytes() }
            }.getOrNull() ?: return null
            if (shaderBytes.isEmpty() || shaderBytes.size % 4 != 0) return null

            val shaderBuffer = ByteBuffer.allocateDirect(shaderBytes.size)
                .order(ByteOrder.nativeOrder())
                .put(shaderBytes)
            shaderBuffer.flip()

            val renderer = VulkanSpectralSyncFdHardwareBufferMmsiRenderer(0L)
            val handle = renderer.nativeCreate(shaderBuffer, shaderBytes.size)
            if (handle == 0L) return null
            renderer.nativeHandle = handle
            return renderer
        }

        internal fun packProjection(projection: SpectralReconstructor.RgbProjection): ByteBuffer {
            require(projection.componentCount == COMPONENT_COUNT)
            val packed = ByteBuffer.allocateDirect(PROJECTION_FLOAT_COUNT * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
            for (channel in 0..2) packed.putFloat(projection.meanRgb[channel].toFloat())
            for (component in 0 until COMPONENT_COUNT) {
                for (channel in 0..2) {
                    packed.putFloat(projection.coefficientsToRgb[channel][component].toFloat())
                }
            }
            packed.flip()
            return packed
        }

        @Volatile private var loaded = false

        private fun ensureLoaded() {
            if (loaded) return
            synchronized(this) {
                if (!loaded) {
                    System.loadLibrary("lifeos_mmsi_native")
                    loaded = true
                }
            }
        }
    }
}
