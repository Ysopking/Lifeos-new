package app.lifeos.core.image

/** Byte layout shared by Vulkan staging and Android HardwareBuffer storage paths. */
object MmsiBufferLayout {
    const val ALBEDO_RGBA32F_BYTES_PER_PIXEL = 16
    const val NORMAL_DEPTH_RGBA32F_BYTES_PER_PIXEL = 16
    const val ROUGHNESS_R32F_BYTES_PER_PIXEL = 4
    const val OUTPUT_RGBA32F_BYTES_PER_PIXEL = 16
    const val BYTES_PER_PIXEL =
        ALBEDO_RGBA32F_BYTES_PER_PIXEL +
            NORMAL_DEPTH_RGBA32F_BYTES_PER_PIXEL +
            ROUGHNESS_R32F_BYTES_PER_PIXEL +
            OUTPUT_RGBA32F_BYTES_PER_PIXEL

    fun bytesForPixels(pixelCount: Long, bytesPerPixel: Int): Long {
        require(pixelCount >= 0)
        require(bytesPerPixel > 0)
        return Math.multiplyExact(pixelCount, bytesPerPixel.toLong())
    }
}
