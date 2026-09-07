package app.lifeos.core.image

/** Byte layout shared by Vulkan staging and Android HardwareBuffer storage paths. */
object MmsiBufferLayout {
    const val ALBEDO_RGBA32F_BYTES_PER_PIXEL = 16
    const val SPECTRAL_COEFFICIENTS_VEC4X2_BYTES_PER_PIXEL = 32
    const val NORMAL_DEPTH_RGBA32F_BYTES_PER_PIXEL = 16
    const val ROUGHNESS_R32F_BYTES_PER_PIXEL = 4
    const val OUTPUT_RGBA32F_BYTES_PER_PIXEL = 16

    /** Existing RGB-projected forward-render working set. */
    const val BYTES_PER_PIXEL =
        ALBEDO_RGBA32F_BYTES_PER_PIXEL +
            NORMAL_DEPTH_RGBA32F_BYTES_PER_PIXEL +
            ROUGHNESS_R32F_BYTES_PER_PIXEL +
            OUTPUT_RGBA32F_BYTES_PER_PIXEL

    /** Phase-3 compact spectral working set after albedo has been expanded to six coefficients. */
    const val SPECTRAL_RENDER_BYTES_PER_PIXEL =
        SPECTRAL_COEFFICIENTS_VEC4X2_BYTES_PER_PIXEL +
            NORMAL_DEPTH_RGBA32F_BYTES_PER_PIXEL +
            ROUGHNESS_R32F_BYTES_PER_PIXEL +
            OUTPUT_RGBA32F_BYTES_PER_PIXEL

    /** Peak tile working set while Phase 2 still retains the Phase-1 albedo input. */
    const val SPECTRAL_PHASE2_PEAK_BYTES_PER_PIXEL =
        ALBEDO_RGBA32F_BYTES_PER_PIXEL + SPECTRAL_RENDER_BYTES_PER_PIXEL

    fun bytesForPixels(pixelCount: Long, bytesPerPixel: Int): Long {
        require(pixelCount >= 0)
        require(bytesPerPixel > 0)
        return Math.multiplyExact(pixelCount, bytesPerPixel.toLong())
    }
}
