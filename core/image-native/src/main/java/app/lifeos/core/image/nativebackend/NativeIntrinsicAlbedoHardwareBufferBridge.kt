package app.lifeos.core.image.nativebackend

import android.hardware.HardwareBuffer
import app.lifeos.core.image.MmsiBufferLayout
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Uploads already-intrinsic linear RGBA albedo without running inverse radiometry again. */
class NativeIntrinsicAlbedoHardwareBufferBridge {
    fun upload(
        albedoLinearRgba32f: ByteBuffer,
        outputAlbedoRgba32f: HardwareBuffer,
        pixelCount: Int,
    ): MmsiSyncFence? {
        require(pixelCount >= 0)
        val requiredBytes = MmsiBufferLayout.bytesForPixels(
            pixelCount.toLong(),
            MmsiBufferLayout.ALBEDO_RGBA32F_BYTES_PER_PIXEL,
        )
        require(albedoLinearRgba32f.isDirect) { "albedoLinearRgba32f must be a direct ByteBuffer" }
        require(requiredBytes <= Int.MAX_VALUE.toLong()) { "Albedo input exceeds direct-buffer addressable size" }
        require(albedoLinearRgba32f.capacity().toLong() >= requiredBytes) { "Albedo input buffer is too small" }
        require(outputAlbedoRgba32f.format == HardwareBuffer.BLOB)
        require(outputAlbedoRgba32f.height == 1)
        require(outputAlbedoRgba32f.width.toLong() >= requiredBytes)

        ensureLoaded()
        val releaseFd = nativeUploadIntrinsicAlbedo(
            albedoLinearRgba32f.order(ByteOrder.nativeOrder()),
            outputAlbedoRgba32f,
            pixelCount,
        )
        return MmsiSyncFence.fromNative(releaseFd)
    }

    private external fun nativeUploadIntrinsicAlbedo(
        albedoBuffer: ByteBuffer,
        albedoHardwareBuffer: HardwareBuffer,
        pixelCount: Int,
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
