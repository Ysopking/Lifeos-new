package app.lifeos.core.scene

import app.lifeos.core.image.MmsiBufferLayout

/** Reference resolution for deterministic scene rasterization. */
data class SceneRasterSize(
    val width: Int,
    val height: Int,
) {
    init {
        require(width > 0 && height > 0)
        require(width.toLong() * height.toLong() <= Int.MAX_VALUE.toLong())
    }

    val pixelCount: Int = width * height
}

/**
 * CPU-reference output that maps directly onto the existing MMSI upload contract:
 * albedo RGBA32F, normals XYZ32F, depth R32F and roughness R32F.
 */
data class MmsiSceneRasterBuffers(
    val size: SceneRasterSize,
    val albedoLinearRgba: FloatArray,
    val normalsXyz: FloatArray,
    val depth: FloatArray,
    val roughness: FloatArray,
) {
    init {
        val pixels = size.pixelCount
        require(albedoLinearRgba.size == pixels * 4)
        require(normalsXyz.size == pixels * 3)
        require(depth.size == pixels)
        require(roughness.size == pixels)
        require(albedoLinearRgba.all { it.isFinite() && it in 0f..1f })
        require(normalsXyz.all { it.isFinite() && it in -1.0001f..1.0001f })
        require(depth.all { it.isFinite() && it in 0f..1f })
        require(roughness.all { it.isFinite() && it in 0.04f..1f })
    }

    val mmsiInputBytes: Long =
        MmsiBufferLayout.bytesForPixels(size.pixelCount.toLong(), MmsiBufferLayout.ALBEDO_RGBA32F_BYTES_PER_PIXEL) +
            MmsiBufferLayout.bytesForPixels(size.pixelCount.toLong(), MmsiBufferLayout.NORMAL_DEPTH_RGBA32F_BYTES_PER_PIXEL) +
            MmsiBufferLayout.bytesForPixels(size.pixelCount.toLong(), MmsiBufferLayout.ROUGHNESS_R32F_BYTES_PER_PIXEL)

    fun isCovered(pixelIndex: Int): Boolean {
        require(pixelIndex in 0 until size.pixelCount)
        return depth[pixelIndex] < 1f
    }
}

data class SceneRasterStats(
    val coveredPixels: Int,
    val actorPixels: Int,
    val objectPixels: Int,
    val groundPixels: Int,
) {
    init {
        require(coveredPixels >= 0)
        require(actorPixels >= 0 && objectPixels >= 0 && groundPixels >= 0)
        require(actorPixels + objectPixels + groundPixels <= coveredPixels)
    }
}

sealed interface SceneRasterResult {
    data class Rasterized(
        val buffers: MmsiSceneRasterBuffers,
        val stats: SceneRasterStats,
    ) : SceneRasterResult

    data class Blocked(val reasons: List<String>) : SceneRasterResult {
        init { require(reasons.isNotEmpty()) }
    }
}

interface SceneRasterizer {
    fun rasterize(graph: ProceduralSceneGraph, size: SceneRasterSize): SceneRasterResult
}
