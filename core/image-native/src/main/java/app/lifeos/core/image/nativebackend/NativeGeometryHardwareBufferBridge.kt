package app.lifeos.core.image.nativebackend

import android.hardware.HardwareBuffer
import app.lifeos.core.image.MmsiBufferLayout
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Uploads normal/depth and roughness fields directly into GPU-importable AHardwareBuffers. */
class NativeGeometryHardwareBufferBridge {
    fun upload(
        normals: ByteBuffer,
        depth: ByteBuffer,
        roughness: ByteBuffer,
        normalDepthRgba32f: HardwareBuffer,
        roughnessR32f: HardwareBuffer,
        pixelCount: Int,
    ): MmsiSyncFence? {
        require(pixelCount >= 0)
        requireDirect(normals, pixelCount.toLong() * NORMAL_BYTES_PER_PIXEL, "normals")
        requireDirect(depth, pixelCount.toLong() * Float.SIZE_BYTES, "depth")
        requireDirect(roughness, pixelCount.toLong() * Float.SIZE_BYTES, "roughness")
        require(normalDepthRgba32f.format == HardwareBuffer.BLOB)
        require(roughnessR32f.format == HardwareBuffer.BLOB)
        require(normalDepthRgba32f.height == 1 && roughnessR32f.height == 1)
        require(
            normalDepthRgba32f.width.toLong() >= MmsiBufferLayout.bytesForPixels(
                pixelCount.toLong(),
                MmsiBufferLayout.NORMAL_DEPTH_RGBA32F_BYTES_PER_PIXEL,
            ),
        )
        require(
            roughnessR32f.width.toLong() >= MmsiBufferLayout.bytesForPixels(
                pixelCount.toLong(),
                MmsiBufferLayout.ROUGHNESS_R32F_BYTES_PER_PIXEL,
            ),
        )

        ensureLoaded()
        val releaseFd = nativeUploadGeometry(
            normals.order(ByteOrder.nativeOrder()),
            depth.order(ByteOrder.nativeOrder()),
            roughness.order(ByteOrder.nativeOrder()),
            normalDepthRgba32f,
            roughnessR32f,
            pixelCount,
        )
        return MmsiSyncFence.fromNative(releaseFd)
    }

    private external fun nativeUploadGeometry(
        normalBuffer: ByteBuffer,
        depthBuffer: ByteBuffer,
        roughnessBuffer: ByteBuffer,
        normalDepthHardwareBuffer: HardwareBuffer,
        roughnessHardwareBuffer: HardwareBuffer,
        pixelCount: Int,
    ): Int

    companion object {
        private const val NORMAL_BYTES_PER_PIXEL = 3L * Float.SIZE_BYTES

        @Volatile private var loaded = false

        private fun requireDirect(buffer: ByteBuffer, required: Long, name: String) {
            require(buffer.isDirect) { "$name must be a direct ByteBuffer" }
            require(required <= Int.MAX_VALUE.toLong()) { "$name exceeds direct-buffer addressable size" }
            require(buffer.capacity().toLong() >= required) { "$name buffer is too small" }
        }

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
