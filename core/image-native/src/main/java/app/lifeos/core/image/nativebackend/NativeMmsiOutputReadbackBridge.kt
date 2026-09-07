package app.lifeos.core.image.nativebackend

import android.hardware.HardwareBuffer
import app.lifeos.core.image.MmsiBufferLayout
import app.lifeos.core.image.Rgba8Image
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Converts fenced MMSI RGBA32F output into compact RGBA8 without a Java-side busy wait. */
class NativeMmsiOutputReadbackBridge {
    fun readRgba8(
        outputRgba32f: HardwareBuffer,
        width: Int,
        height: Int,
        acquireFence: MmsiSyncFence? = null,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    ): Rgba8Image {
        require(width > 0 && height > 0)
        require(timeoutMs > 0)
        val pixels = Math.multiplyExact(width.toLong(), height.toLong())
        require(pixels <= Int.MAX_VALUE.toLong()) { "Image pixel count exceeds supported range" }
        require(outputRgba32f.format == HardwareBuffer.BLOB)
        require(outputRgba32f.height == 1)
        require(
            outputRgba32f.width.toLong() >= MmsiBufferLayout.bytesForPixels(
                pixels,
                MmsiBufferLayout.OUTPUT_RGBA32F_BYTES_PER_PIXEL,
            ),
        )
        val rgbaBytes = Math.multiplyExact(pixels, 4L)
        require(rgbaBytes <= Int.MAX_VALUE.toLong()) { "RGBA8 readback exceeds direct-buffer limit" }
        val direct = ByteBuffer.allocateDirect(rgbaBytes.toInt()).order(ByteOrder.nativeOrder())

        ensureLoaded()
        val fenceFd = acquireFence?.take() ?: -1
        nativeReadRgba8(
            outputRgba32f,
            direct,
            pixels.toInt(),
            fenceFd,
            timeoutMs,
        )
        direct.position(0)
        val bytes = ByteArray(rgbaBytes.toInt())
        direct.get(bytes)
        return Rgba8Image(width, height, bytes)
    }

    private external fun nativeReadRgba8(
        outputHardwareBuffer: HardwareBuffer,
        rgba8Buffer: ByteBuffer,
        pixelCount: Int,
        acquireFenceFd: Int,
        timeoutMs: Int,
    )

    companion object {
        const val DEFAULT_TIMEOUT_MS = 5_000

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
