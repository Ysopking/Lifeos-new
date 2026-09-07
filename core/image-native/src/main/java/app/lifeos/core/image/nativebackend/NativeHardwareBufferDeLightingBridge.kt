package app.lifeos.core.image.nativebackend

import android.hardware.HardwareBuffer
import app.lifeos.core.image.MmsiBufferLayout
import app.lifeos.core.image.SolarVector
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Phase 1 native backend that writes de-lit albedo directly into the RGBA32F HardwareBuffer
 * later imported by the Vulkan forward-synthesis path.
 */
class NativeHardwareBufferDeLightingBridge {
    fun inverseRadiometryToHardwareBuffer(
        rgb: ByteBuffer,
        normals: ByteBuffer,
        roughness: ByteBuffer,
        outputAlbedoRgba32f: HardwareBuffer,
        pixelCount: Int,
        sunDirection: SolarVector,
        sunIntensity: Float,
        ambientIntensity: Float,
        acquireFence: MmsiSyncFence? = null,
    ): MmsiSyncFence? {
        require(pixelCount >= 0)
        require(rgb.isDirect) { "RGB buffer must be direct" }
        require(normals.isDirect) { "Normal buffer must be direct" }
        require(roughness.isDirect) { "Roughness buffer must be direct" }
        require(rgb.capacity().toLong() >= pixelCount.toLong() * NativeMmsiBridge.RGB_BYTES_PER_PIXEL)
        require(normals.capacity().toLong() >= pixelCount.toLong() * NativeMmsiBridge.NORMAL_BYTES_PER_PIXEL)
        require(roughness.capacity().toLong() >= pixelCount.toLong() * NativeMmsiBridge.ROUGHNESS_BYTES_PER_PIXEL)
        require(outputAlbedoRgba32f.format == HardwareBuffer.BLOB) { "Albedo HardwareBuffer must be BLOB" }
        require(outputAlbedoRgba32f.height == 1) { "Albedo HardwareBuffer must have height 1" }
        val requiredBytes = MmsiBufferLayout.bytesForPixels(
            pixelCount.toLong(),
            MmsiBufferLayout.ALBEDO_RGBA32F_BYTES_PER_PIXEL,
        )
        require(outputAlbedoRgba32f.width.toLong() >= requiredBytes) { "Albedo HardwareBuffer is too small" }
        require(sunIntensity >= 0f && sunIntensity.isFinite())
        require(ambientIntensity >= 0f && ambientIntensity.isFinite())

        val sun = sunDirection.normalized()
        ensureLoaded()
        val acquireFd = acquireFence?.take() ?: -1
        val releaseFd = nativeInverseRadiometryToHardwareBuffer(
            rgb,
            normals.order(ByteOrder.nativeOrder()),
            roughness.order(ByteOrder.nativeOrder()),
            outputAlbedoRgba32f,
            pixelCount,
            sun.x.toFloat(),
            sun.y.toFloat(),
            sun.z.toFloat(),
            sunIntensity,
            ambientIntensity,
            acquireFd,
        )
        return MmsiSyncFence.fromNative(releaseFd)
    }

    private external fun nativeInverseRadiometryToHardwareBuffer(
        rgbBuffer: ByteBuffer,
        normalBuffer: ByteBuffer,
        roughnessBuffer: ByteBuffer,
        outputHardwareBuffer: HardwareBuffer,
        pixelCount: Int,
        sunX: Float,
        sunY: Float,
        sunZ: Float,
        sunIntensity: Float,
        ambientIntensity: Float,
        acquireFenceFd: Int,
    ): Int

    companion object {
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
