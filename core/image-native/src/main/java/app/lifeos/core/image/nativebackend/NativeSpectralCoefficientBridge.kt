package app.lifeos.core.image.nativebackend

import android.hardware.HardwareBuffer
import app.lifeos.core.image.MmsiBufferLayout
import app.lifeos.core.image.SpectralReconstructor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Phase 2 compact hyperspectral expansion.
 *
 * The regularized six-coefficient projection is applied directly from the Phase-1 albedo AHB
 * into a padded two-vec4-per-pixel coefficient AHB. The returned fence can be passed to the
 * asynchronous Vulkan spectral renderer without a CPU wait.
 */
class NativeSpectralCoefficientBridge {
    fun expandToHardwareBuffer(
        albedoRgba32f: HardwareBuffer,
        spectralCoefficientsVec4x2: HardwareBuffer,
        pixelCount: Int,
        projection: SpectralReconstructor.CoefficientProjection,
        acquireFence: MmsiSyncFence? = null,
    ): MmsiSyncFence? {
        require(pixelCount >= 0)
        require(projection.componentCount == COMPONENT_COUNT) {
            "Native mobile spectral path requires exactly $COMPONENT_COUNT basis coefficients"
        }
        require(albedoRgba32f.format == HardwareBuffer.BLOB)
        require(spectralCoefficientsVec4x2.format == HardwareBuffer.BLOB)
        require(albedoRgba32f.height == 1 && spectralCoefficientsVec4x2.height == 1)
        require(
            albedoRgba32f.width.toLong() >= MmsiBufferLayout.bytesForPixels(
                pixelCount.toLong(),
                MmsiBufferLayout.ALBEDO_RGBA32F_BYTES_PER_PIXEL,
            ),
        )
        require(
            spectralCoefficientsVec4x2.width.toLong() >= MmsiBufferLayout.bytesForPixels(
                pixelCount.toLong(),
                MmsiBufferLayout.SPECTRAL_COEFFICIENTS_VEC4X2_BYTES_PER_PIXEL,
            ),
        )

        val packedProjection = ByteBuffer.allocateDirect(PROJECTION_FLOAT_COUNT * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
        projection.bias.forEach { packedProjection.putFloat(it.toFloat()) }
        projection.rgbToCoefficients.forEach { row ->
            row.forEach { packedProjection.putFloat(it.toFloat()) }
        }
        packedProjection.flip()

        ensureLoaded()
        val acquireFd = acquireFence?.take() ?: -1
        val releaseFd = nativeExpandToHardwareBuffer(
            albedoRgba32f,
            spectralCoefficientsVec4x2,
            pixelCount,
            packedProjection,
            acquireFd,
        )
        return MmsiSyncFence.fromNative(releaseFd)
    }

    private external fun nativeExpandToHardwareBuffer(
        albedoHardwareBuffer: HardwareBuffer,
        coefficientHardwareBuffer: HardwareBuffer,
        pixelCount: Int,
        projectionBuffer: ByteBuffer,
        acquireFenceFd: Int,
    ): Int

    companion object {
        const val COMPONENT_COUNT = 6
        private const val PROJECTION_FLOAT_COUNT = COMPONENT_COUNT + COMPONENT_COUNT * 3

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
