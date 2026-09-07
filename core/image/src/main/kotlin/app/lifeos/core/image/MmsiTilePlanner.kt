package app.lifeos.core.image

import kotlin.math.floor
import kotlin.math.sqrt

data class MmsiTile(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

data class MmsiTilePlan(
    val tiles: List<MmsiTile>,
    val maxTilePixels: Int,
    val estimatedWorkingSetBytes: Long,
)

/**
 * Keeps Vulkan forward synthesis within a bounded working set.
 * The estimate covers albedo RGBA32F + normal/depth RGBA32F + roughness R32F + output RGBA32F,
 * plus configurable overhead for uniforms, descriptors and driver staging.
 */
class MmsiTilePlanner(
    private val budgetBytes: Long = 96L * 1024L * 1024L,
    private val overheadBytes: Long = 8L * 1024L * 1024L,
    private val bytesPerPixel: Int = MmsiBufferLayout.BYTES_PER_PIXEL,
    private val workgroupSize: Int = 8,
) {
    init {
        require(budgetBytes > overheadBytes)
        require(overheadBytes >= 0)
        require(bytesPerPixel > 0)
        require(workgroupSize > 0)
    }

    fun plan(width: Int, height: Int): MmsiTilePlan {
        require(width > 0 && height > 0)
        val usable = budgetBytes - overheadBytes
        val maxPixels = (usable / bytesPerPixel).coerceAtLeast(1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val rawSide = floor(sqrt(maxPixels.toDouble())).toInt().coerceAtLeast(workgroupSize)
        val alignedSide = (rawSide / workgroupSize * workgroupSize).coerceAtLeast(workgroupSize)
        val tileWidth = minOf(width, alignedSide)
        val tileHeight = minOf(height, maxOf(workgroupSize, maxPixels / tileWidth / workgroupSize * workgroupSize))
            .coerceAtMost(height)

        val tiles = buildList {
            var y = 0
            while (y < height) {
                val h = minOf(tileHeight, height - y)
                var x = 0
                while (x < width) {
                    val w = minOf(tileWidth, width - x)
                    add(MmsiTile(x, y, w, h))
                    x += tileWidth
                }
                y += tileHeight
            }
        }
        val peakPixels = tiles.maxOf { it.width * it.height }
        val estimated = overheadBytes + peakPixels.toLong() * bytesPerPixel
        require(estimated <= budgetBytes) { "Tile planner exceeded memory budget" }
        return MmsiTilePlan(tiles, peakPixels, estimated)
    }
}
