package app.lifeos.core.image.nativebackend

import android.content.Context
import app.lifeos.core.image.Rgba8Image
import app.lifeos.core.image.SpectralReconstructor
import java.io.Closeable
import java.nio.ByteBuffer

/**
 * Spectral MMSI fast path for procedurally constructed scenes whose albedo is already intrinsic.
 *
 * Unlike [MmsiSpectralHardwarePipeline], this path intentionally skips inverse radiometry. Geometry
 * and intrinsic albedo are independent CPU producers; Phase 2 consumes the albedo release fence,
 * then geometry and spectral-coefficient fences are joined before Vulkan Phase 3.
 */
class MmsiProceduralSpectralHardwarePipeline private constructor(
    private val renderer: VulkanSpectralSyncFdHardwareBufferMmsiRenderer,
    private val geometry: NativeGeometryHardwareBufferBridge = NativeGeometryHardwareBufferBridge(),
    private val albedo: NativeIntrinsicAlbedoHardwareBufferBridge = NativeIntrinsicAlbedoHardwareBufferBridge(),
    private val spectralExpansion: NativeSpectralCoefficientBridge = NativeSpectralCoefficientBridge(),
    private val sync: NativeMmsiSyncBridge = NativeMmsiSyncBridge(),
    private val readback: NativeMmsiOutputReadbackBridge = NativeMmsiOutputReadbackBridge(),
) : Closeable {
    data class DispatchResult(
        val accepted: Boolean,
        val releaseFence: MmsiSyncFence?,
    )

    data class RenderResult(
        val accepted: Boolean,
        val image: Rgba8Image?,
    ) {
        init { require(accepted == (image != null)) }
    }

    @Synchronized
    fun dispatchTile(
        storage: HardwareBufferMmsiInterop.SpectralStorageSet,
        width: Int,
        height: Int,
        intrinsicAlbedoRgba32f: ByteBuffer,
        normals: ByteBuffer,
        depth: ByteBuffer,
        roughness: ByteBuffer,
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
        var albedoFence: MmsiSyncFence? = null
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
            albedoFence = albedo.upload(
                albedoLinearRgba32f = intrinsicAlbedoRgba32f,
                outputAlbedoRgba32f = storage.albedoRgba32f,
                pixelCount = pixelCount,
            )
            phase2Fence = spectralExpansion.expandToHardwareBuffer(
                albedoRgba32f = storage.albedoRgba32f,
                spectralCoefficientsVec4x2 = storage.spectralCoefficientsVec4x2,
                pixelCount = pixelCount,
                projection = coefficientProjection,
                acquireFence = albedoFence,
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
            albedoFence?.close()
            phase2Fence?.close()
            phase3AcquireFence?.close()
        }
    }

    @Synchronized
    fun renderTileRgba8(
        storage: HardwareBufferMmsiInterop.SpectralStorageSet,
        width: Int,
        height: Int,
        intrinsicAlbedoRgba32f: ByteBuffer,
        normals: ByteBuffer,
        depth: ByteBuffer,
        roughness: ByteBuffer,
        coefficientProjection: SpectralReconstructor.CoefficientProjection,
        forwardParameters: VulkanMmsiRenderer.Parameters,
        rgbProjection: SpectralReconstructor.RgbProjection,
        readbackTimeoutMs: Int = NativeMmsiOutputReadbackBridge.DEFAULT_TIMEOUT_MS,
    ): RenderResult {
        val dispatched = dispatchTile(
            storage = storage,
            width = width,
            height = height,
            intrinsicAlbedoRgba32f = intrinsicAlbedoRgba32f,
            normals = normals,
            depth = depth,
            roughness = roughness,
            coefficientProjection = coefficientProjection,
            forwardParameters = forwardParameters,
            rgbProjection = rgbProjection,
        )
        if (!dispatched.accepted) {
            dispatched.releaseFence?.close()
            return RenderResult(false, null)
        }
        return try {
            RenderResult(
                accepted = true,
                image = readback.readRgba8(
                    outputRgba32f = storage.outputRgba32f,
                    width = width,
                    height = height,
                    acquireFence = dispatched.releaseFence,
                    timeoutMs = readbackTimeoutMs,
                ),
            )
        } finally {
            dispatched.releaseFence?.close()
        }
    }

    override fun close() {
        renderer.close()
    }

    companion object {
        fun create(context: Context): MmsiProceduralSpectralHardwarePipeline? =
            VulkanSpectralSyncFdHardwareBufferMmsiRenderer.create(context)?.let(::MmsiProceduralSpectralHardwarePipeline)
    }
}
