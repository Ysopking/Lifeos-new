package app.lifeos.core.image.nativebackend

import android.hardware.HardwareBuffer
import app.lifeos.core.image.MmsiBufferLayout
import app.lifeos.core.image.MmsiTile
import java.io.Closeable

/**
 * Android HardwareBuffer storage path for MMSI Vulkan tiles.
 * BLOB + GPU_DATA_BUFFER keeps the memory compatible with the existing storage-buffer shader contract.
 */
class HardwareBufferMmsiInterop {
    data class StorageSet(
        val albedoRgba32f: HardwareBuffer,
        val normalDepthRgba32f: HardwareBuffer,
        val roughnessR32f: HardwareBuffer,
        val outputRgba32f: HardwareBuffer,
        val pixelCount: Int,
    ) : Closeable {
        override fun close() {
            albedoRgba32f.close()
            normalDepthRgba32f.close()
            roughnessR32f.close()
            outputRgba32f.close()
        }
    }

    fun isSupported(): Boolean = runCatching {
        allocateStorage(4096).use { canImport(it) }
    }.getOrDefault(false)

    fun canImport(buffer: HardwareBuffer): Boolean {
        ensureLoaded()
        return nativeCanImportHardwareBuffer(buffer)
    }

    fun allocateForTile(tile: MmsiTile): StorageSet {
        require(tile.width > 0 && tile.height > 0)
        val pixelsLong = Math.multiplyExact(tile.width.toLong(), tile.height.toLong())
        require(pixelsLong <= Int.MAX_VALUE.toLong()) { "Tile pixel count exceeds supported range" }
        val allocated = mutableListOf<HardwareBuffer>()
        try {
            fun allocate(bytesPerPixel: Int): HardwareBuffer {
                val bytes = MmsiBufferLayout.bytesForPixels(pixelsLong, bytesPerPixel)
                return allocateStorage(bytes).also(allocated::add)
            }
            return StorageSet(
                albedoRgba32f = allocate(MmsiBufferLayout.ALBEDO_RGBA32F_BYTES_PER_PIXEL),
                normalDepthRgba32f = allocate(MmsiBufferLayout.NORMAL_DEPTH_RGBA32F_BYTES_PER_PIXEL),
                roughnessR32f = allocate(MmsiBufferLayout.ROUGHNESS_R32F_BYTES_PER_PIXEL),
                outputRgba32f = allocate(MmsiBufferLayout.OUTPUT_RGBA32F_BYTES_PER_PIXEL),
                pixelCount = pixelsLong.toInt(),
            )
        } catch (error: Throwable) {
            allocated.forEach { runCatching { it.close() } }
            throw error
        }
    }

    fun allocateStorage(byteSize: Long): HardwareBuffer {
        require(byteSize in 1..Int.MAX_VALUE.toLong()) { "HardwareBuffer byte size is out of range" }
        return HardwareBuffer.create(
            byteSize.toInt(),
            1,
            HardwareBuffer.BLOB,
            1,
            HardwareBuffer.USAGE_GPU_DATA_BUFFER or
                HardwareBuffer.USAGE_CPU_READ_RARELY or
                HardwareBuffer.USAGE_CPU_WRITE_RARELY,
        )
    }

    private external fun nativeCanImportHardwareBuffer(buffer: HardwareBuffer): Boolean

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
