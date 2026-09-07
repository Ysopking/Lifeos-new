package app.lifeos.core.image.nativebackend

import android.content.Context
import android.hardware.HardwareBuffer
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * MMSI phase-3 renderer importing Android HardwareBuffers directly as Vulkan storage buffers.
 *
 * Synchronization contract: all input buffers must be idle and exclusively available to this
 * renderer for the duration of dispatch. The call waits for the Vulkan fence before returning,
 * so the output buffer is complete on return. External acquire/release fence-FD interop is a
 * separate capability and is not implied by this class.
 */
class VulkanHardwareBufferMmsiRenderer private constructor(
    private var nativeHandle: Long,
) : Closeable {
    @Synchronized
    fun dispatch(
        storage: HardwareBufferMmsiInterop.StorageSet,
        width: Int,
        height: Int,
        parameters: VulkanMmsiRenderer.Parameters,
    ): Boolean {
        check(nativeHandle != 0L) { "Vulkan HardwareBuffer renderer is closed" }
        require(width > 0 && height > 0)
        val pixels = Math.multiplyExact(width.toLong(), height.toLong())
        require(pixels == storage.pixelCount.toLong()) {
            "StorageSet pixel count ${storage.pixelCount} does not match ${width}x$height"
        }
        val sun = parameters.sunDirection.normalized()
        return nativeDispatch(
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
        )
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
    ): Boolean

    private external fun nativeDestroy(handle: Long)

    companion object {
        fun create(context: Context): VulkanHardwareBufferMmsiRenderer? {
            ensureLoaded()
            val interop = HardwareBufferMmsiInterop()
            if (!VulkanMmsiBridge().isReady(context) || !interop.isSupported()) return null
            val shaderBytes = runCatching {
                context.assets.open(VulkanMmsiBridge.FORWARD_SHADER_ASSET).use { it.readBytes() }
            }.getOrNull() ?: return null
            if (shaderBytes.isEmpty() || shaderBytes.size % 4 != 0) return null

            val shaderBuffer = ByteBuffer.allocateDirect(shaderBytes.size)
                .order(ByteOrder.nativeOrder())
                .put(shaderBytes)
            shaderBuffer.flip()

            val renderer = VulkanHardwareBufferMmsiRenderer(0L)
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
