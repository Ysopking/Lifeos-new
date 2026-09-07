package app.lifeos.core.image.nativebackend

import android.content.Context
import android.hardware.HardwareBuffer
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Asynchronous MMSI phase-3 renderer.
 *
 * The optional acquire fence is consumed by Vulkan as a temporary SYNC_FD semaphore payload.
 * On success the returned release fence represents completion of the output HardwareBuffer.
 * A null release fence means the native backend completed synchronously and the output is ready.
 */
class VulkanSyncFdHardwareBufferMmsiRenderer private constructor(
    private var nativeHandle: Long,
) : Closeable {
    data class DispatchResult(
        val accepted: Boolean,
        val releaseFence: MmsiSyncFence?,
    )

    @Synchronized
    fun dispatch(
        storage: HardwareBufferMmsiInterop.StorageSet,
        width: Int,
        height: Int,
        parameters: VulkanMmsiRenderer.Parameters,
        acquireFence: MmsiSyncFence? = null,
    ): DispatchResult {
        check(nativeHandle != 0L) { "Vulkan sync-fd renderer is closed" }
        require(width > 0 && height > 0)
        val pixels = Math.multiplyExact(width.toLong(), height.toLong())
        require(pixels == storage.pixelCount.toLong()) {
            "StorageSet pixel count ${storage.pixelCount} does not match ${width}x$height"
        }
        val sun = parameters.sunDirection.normalized()
        val acquireFd = acquireFence?.take() ?: -1
        val nativeResult = nativeDispatch(
            nativeHandle,
            storage.albedoRgba32f,
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
        albedoBuffer: HardwareBuffer,
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
        acquireFenceFd: Int,
    ): Int

    private external fun nativeDestroy(handle: Long)

    companion object {
        private const val COMPLETED_SYNCHRONOUSLY = -1

        fun create(context: Context): VulkanSyncFdHardwareBufferMmsiRenderer? {
            ensureLoaded()
            val shaderBytes = runCatching {
                context.assets.open(VulkanMmsiBridge.FORWARD_SHADER_ASSET).use { it.readBytes() }
            }.getOrNull() ?: return null
            if (shaderBytes.isEmpty() || shaderBytes.size % 4 != 0) return null

            val shaderBuffer = ByteBuffer.allocateDirect(shaderBytes.size)
                .order(ByteOrder.nativeOrder())
                .put(shaderBytes)
            shaderBuffer.flip()

            val renderer = VulkanSyncFdHardwareBufferMmsiRenderer(0L)
            val handle = renderer.nativeCreate(shaderBuffer, shaderBytes.size)
            if (handle == 0L) return null
            renderer.nativeHandle = handle
            return renderer
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
