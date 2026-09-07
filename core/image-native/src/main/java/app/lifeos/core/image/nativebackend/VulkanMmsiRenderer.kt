package app.lifeos.core.image.nativebackend

import android.content.Context
import app.lifeos.core.image.RgbSample
import app.lifeos.core.image.SolarVector
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Host-visible staging implementation of the MMSI Vulkan forward-synthesis backend.
 * Inputs and outputs are direct byte buffers; a future AHardwareBuffer backend may replace
 * the staging allocations without changing the forward-render contract.
 */
class VulkanMmsiRenderer private constructor(
    private var nativeHandle: Long,
) : Closeable {
    data class Parameters(
        val sunDirection: SolarVector,
        val sunSolidAngleRad: Float,
        val sunColorLinear: RgbSample,
        val skyAmbientLinear: RgbSample,
        val shadowFloor: Float = 0.05f,
    ) {
        init {
            require(sunSolidAngleRad >= 0.0f)
            require(shadowFloor in 0.0f..1.0f)
        }
    }

    @Synchronized
    fun dispatch(
        albedoRgba32f: ByteBuffer,
        normalDepthRgba32f: ByteBuffer,
        roughnessR32f: ByteBuffer,
        outputRgba32f: ByteBuffer,
        width: Int,
        height: Int,
        parameters: Parameters,
    ): Boolean {
        check(nativeHandle != 0L) { "Vulkan renderer is closed" }
        require(width > 0 && height > 0)
        val pixels = Math.multiplyExact(width.toLong(), height.toLong())
        requireDirectCapacity(albedoRgba32f, Math.multiplyExact(pixels, 16L), "albedoRgba32f")
        requireDirectCapacity(normalDepthRgba32f, Math.multiplyExact(pixels, 16L), "normalDepthRgba32f")
        requireDirectCapacity(roughnessR32f, Math.multiplyExact(pixels, 4L), "roughnessR32f")
        requireDirectCapacity(outputRgba32f, Math.multiplyExact(pixels, 16L), "outputRgba32f")

        val sun = parameters.sunDirection.normalized()
        return nativeDispatch(
            nativeHandle,
            albedoRgba32f,
            normalDepthRgba32f,
            roughnessR32f,
            outputRgba32f,
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
        albedoBuffer: ByteBuffer,
        normalDepthBuffer: ByteBuffer,
        roughnessBuffer: ByteBuffer,
        outputBuffer: ByteBuffer,
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
        fun create(context: Context): VulkanMmsiRenderer? {
            ensureLoaded()
            if (!VulkanMmsiBridge().isReady(context)) return null
            val shaderBytes = runCatching {
                context.assets.open(VulkanMmsiBridge.FORWARD_SHADER_ASSET).use { it.readBytes() }
            }.getOrNull() ?: return null
            if (shaderBytes.isEmpty() || shaderBytes.size % 4 != 0) return null

            val shaderBuffer = ByteBuffer.allocateDirect(shaderBytes.size)
                .order(ByteOrder.nativeOrder())
            shaderBuffer.put(shaderBytes)
            shaderBuffer.flip()

            val renderer = VulkanMmsiRenderer(0L)
            val handle = renderer.nativeCreate(shaderBuffer, shaderBytes.size)
            if (handle == 0L) return null
            renderer.nativeHandle = handle
            return renderer
        }

        private fun requireDirectCapacity(buffer: ByteBuffer, requiredBytes: Long, name: String) {
            require(buffer.isDirect) { "$name must be a direct ByteBuffer" }
            require(requiredBytes <= Int.MAX_VALUE.toLong()) { "$name exceeds direct-buffer addressable size" }
            require(buffer.capacity().toLong() >= requiredBytes) {
                "$name capacity ${buffer.capacity()} < required $requiredBytes bytes"
            }
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
