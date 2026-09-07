package app.lifeos.core.scene

import app.lifeos.core.image.ProceduralMmsiProfile
import app.lifeos.core.image.ProceduralMmsiReferenceShading
import app.lifeos.core.image.RgbSample
import app.lifeos.core.image.Rgba8Image
import app.lifeos.core.image.SurfaceNormal
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.tan

interface ProceduralSceneImageRenderer {
    val rendererId: String
    fun render(buffers: MmsiSceneRasterBuffers): Rgba8Image
}

/**
 * Guaranteed offline/no-GPU renderer for already-intrinsic procedural scene buffers.
 * It uses the same six-coefficient spectral projection and GGX shading contract as Vulkan, while
 * retaining deterministic CPU execution when no compatible GPU path exists.
 */
class ReferenceCpuProceduralMmsiRenderer(
    private val profile: ProceduralMmsiProfile = ProceduralMmsiProfile(),
) : ProceduralSceneImageRenderer {
    override val rendererId: String = "mmsi-cpu-procedural-v1"

    private val shading = ProceduralMmsiReferenceShading(profile)

    override fun render(buffers: MmsiSceneRasterBuffers): Rgba8Image {
        val pixels = buffers.size.pixelCount
        val rgba = ByteArray(Math.multiplyExact(pixels, 4))

        for (pixel in 0 until pixels) {
            val out = pixel * 4
            rgba[out + 3] = 0xff.toByte()
            if (!buffers.isCovered(pixel)) continue

            val albedoBase = pixel * 4
            val normalBase = pixel * 3
            val normal = SurfaceNormal(
                buffers.normalsXyz[normalBase].toDouble(),
                buffers.normalsXyz[normalBase + 1].toDouble(),
                buffers.normalsXyz[normalBase + 2].toDouble(),
            ).normalized()
            val roughness = buffers.roughness[pixel].toDouble()
            val shadow = softShadow(pixel, buffers)
            val ambientOcclusion = (0.35 + 0.65 * normal.z).coerceIn(0.0, 1.0)
            val color = shading.shade(
                intrinsicLinearAlbedo = RgbSample(
                    buffers.albedoLinearRgba[albedoBase].toDouble(),
                    buffers.albedoLinearRgba[albedoBase + 1].toDouble(),
                    buffers.albedoLinearRgba[albedoBase + 2].toDouble(),
                ),
                normal = normal,
                roughness = roughness,
                shadowVisibility = shadow,
                ambientOcclusion = ambientOcclusion,
            )
            rgba[out] = quantize(color.r)
            rgba[out + 1] = quantize(color.g)
            rgba[out + 2] = quantize(color.b)
        }
        return Rgba8Image(buffers.size.width, buffers.size.height, rgba)
    }

    private fun softShadow(pixelIndex: Int, buffers: MmsiSceneRasterBuffers): Double {
        val width = buffers.size.width
        val height = buffers.size.height
        val x = pixelIndex % width
        val y = pixelIndex / width
        val sourceDepth = buffers.depth[pixelIndex].toDouble()
        val light = profile.sunDirection.normalized()
        var visibility = 1.0
        val tangent = tan(max(profile.sunSolidAngleRad.toDouble(), 1e-4))

        for (step in 1..24) {
            val t = step * 0.015
            val offsetX = round(light.x * t * width).toInt()
            val offsetY = round(light.y * t * height).toInt()
            val sx = x + offsetX
            val sy = y + offsetY
            if (sx !in 0 until width || sy !in 0 until height) break
            val sampleIndex = sy * width + sx
            val expectedDepth = sourceDepth + light.z * t
            val delta = expectedDepth - buffers.depth[sampleIndex]
            if (delta > 0.001) {
                val coneRadius = max(t * tangent, 1e-4)
                val intersection = (delta / coneRadius).coerceIn(0.0, 1.0)
                visibility = min(visibility, 1.0 - intersection)
            }
        }
        return max(visibility, profile.shadowFloor.toDouble())
    }

    private fun quantize(value: Double): Byte =
        (value.coerceIn(0.0, 1.0) * 255.0).roundToInt().toByte()
}
