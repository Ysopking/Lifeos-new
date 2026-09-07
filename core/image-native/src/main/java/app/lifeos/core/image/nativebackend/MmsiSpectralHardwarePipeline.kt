package app.lifeos.core.image.nativebackend

import android.content.Context
import app.lifeos.core.image.DeLightingContext
import app.lifeos.core.image.SpectralReconstructor
import java.io.Closeable
import java.nio.ByteBuffer

/**
 * Complete mobile fast path for one MMSI tile.
 *
 * Geometry upload and inverse radiometry are independent CPU producers. Their release fences are
 * joined only before the spectral Vulkan consumer, while the Phase-1 -> Phase-2 albedo chain keeps
 * its own dependency. No user-space fence wait is performed by this orchestrator.
 */
class MmsiSpectralHardwarePipeline private constructor(
    private val renderer: VulkanSpectralSyncFdHardwareBufferMmsiRenderer,
    private val geometry: NativeGeometryHardwareBufferBridge = NativeGeometryHardwareBufferBridge(),
    private val deLighting: NativeHardwareBufferDeLightingBridge = NativeHardwareBufferDeLightingBridge(),
    private val spectralExpansion: NativeSpectralCoefficientBridge = NativeSpectralCoefficientBridge(),
    private val sync: NativeMmsiSyncBridge = NativeMmsiSyncBridge(),
) : Closeable {
    data class DispatchResult(
        val accepted: Boolean,
        val releaseFence: MmsiSyncFence?,
    )

    @Synchronized
    fun dispatchTile(
        storage: HardwareBufferMmsiInterop.SpectralStorageSet,
        width: Int,
        height: Int,
        rgb: ByteBuffer,
        normals: ByteBuffer,
        depth: ByteBuffer,
        roughness: ByteBuffer,
        deLightingContext: DeLightingContext,
        coefficientProjection: SpectralReconstructor.CoefficientProjection,
        forwardParameters: VulkanMmsiRenderer.Parameters,
        rgbProjection: SpectralReconstructor.RgbProjection,
    ): DispatchResult {
        require(width > 0 && height > 0)
        val pixels = Math.multiplyExact(width.toLong(), height.toLong())
        require(pixels <= Int.MAX_VALUE.toLong()) { "Tile is too large" }
        require(pixels == storage.pixelCount.toLong()) {
            "SpectralStorageSet pixel count ${storage.pixelCount} does not match ${width}x$height"
        }
        val pixelCount = pixels.toInt()

        var geometryFence: MmsiSyncFence? = null
        var phase1Fence: MmsiSyncFence? = null
        var phase2Fence: MmsiSyncFence? = null
        var phase3AcquireFence: MmsiSyncFence? = null
        try {
            geometryFence = geometry.upload(
                normals = normals,
                depth = depth,
                roughness = roughness,
                normalDepthRgba32f = storage.normalDepthRgba32f,
                roughnessR32f = storage.roughnessR32f,
                pixelCount = pixelCount,
            )

            phase1Fence = deLighting.inverseRadiometryToHardwareBuffer(
                rgb = rgb,
                normals = normals,
                roughness = roughness,
                outputAlbedoRgba32f = storage.albedoRgba32f,
                pixelCount = pixelCount,
                sunDirection = deLightingContext.sunDirection,
                sunIntensity = deLightingContext.sunIntensity.toFloat(),
                ambientIntensity = deLightingContext.ambientIntensity.toFloat(),
            )

            phase2Fence = spectralExpansion.expandToHardwareBuffer(
                albedoRgba32f = storage.albedoRgba32f,
                spectralCoefficientsVec4x2 = storage.spectralCoefficientsVec4x2,
                pixelCount = pixelCount,
                projection = coefficientProjection,
                acquireFence = phase1Fence,
            )

            phase3AcquireFence = sync.merge(geometryFence, phase2Fence)
            val rendered = renderer.dispatch(
                storage = storage,
                width = width,
                height = height,
                parameters = forwardParameters,
                rgbProjection = rgbProjection,
                acquireFence = phase3AcquireFence,
            )
            return DispatchResult(rendered.accepted, rendered.releaseFence)
        } finally {
            geometryFence?.close()
            phase1Fence?.close()
            phase2Fence?.close()
            phase3AcquireFence?.close()
        }
    }

    override fun close() {
        renderer.close()
    }

    companion object {
        fun create(context: Context): MmsiSpectralHardwarePipeline? =
            VulkanSpectralSyncFdHardwareBufferMmsiRenderer.create(context)?.let(::MmsiSpectralHardwarePipeline)
    }
}
