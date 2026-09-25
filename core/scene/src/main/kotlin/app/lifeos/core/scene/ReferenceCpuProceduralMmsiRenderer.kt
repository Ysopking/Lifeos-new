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
    private val normalizedSunDirection = profile.sunDirection.normalized()
    private val shadowConeTangent =
        tan(max(profile.sunSolidAngleRad.toDouble(), 1e-4))
    private val shadowFloor = profile.shadowFloor.toDouble()

    override fun render(buffers: MmsiSceneRasterBuffers): Rgba8Image {
        val pixels = buffers.size.pixelCount
        val rgba = ByteArray(Math.multiplyExact(pixels, 4))
        val shadowKernel = ShadowKernel.create(
            width = buffers.size.width,
            height = buffers.size.height,
            lightX = normalizedSunDirection.x,
            lightY = normalizedSunDirection.y,
            lightZ = normalizedSunDirection.z,
            coneTangent = shadowConeTangent,
        )

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
            val shadow = softShadow(pixel, buffers, shadowKernel)
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

    private fun softShadow(
        pixelIndex: Int,
        buffers: MmsiSceneRasterBuffers,
        kernel: ShadowKernel,
    ): Double {
        val width = buffers.size.width
        val height = buffers.size.height
        val x = pixelIndex % width
        val y = pixelIndex / width
        val sourceDepth = buffers.depth[pixelIndex].toDouble()
        var visibility = 1.0

        for (step in 0 until kernel.size) {
            val sx = x + kernel.offsetX[step]
            val sy = y + kernel.offsetY[step]
            if (sx !in 0 until width || sy !in 0 until height) break
            val sampleIndex = sy * width + sx
            val expectedDepth = sourceDepth + kernel.depthDelta[step]
            val delta = expectedDepth - buffers.depth[sampleIndex]
            if (delta > 0.001) {
                val intersection =
                    (delta / kernel.coneRadius[step]).coerceIn(0.0, 1.0)
                visibility = min(visibility, 1.0 - intersection)
            }
        }
        return max(visibility, shadowFloor)
    }

    private data class ShadowKernel(
        val offsetX: IntArray,
        val offsetY: IntArray,
        val depthDelta: DoubleArray,
        val coneRadius: DoubleArray,
    ) {
        val size: Int
            get() = offsetX.size

        init {
            require(offsetY.size == size)
            require(depthDelta.size == size)
            require(coneRadius.size == size)
        }

        companion object {
            fun create(
                width: Int,
                height: Int,
                lightX: Double,
                lightY: Double,
                lightZ: Double,
                coneTangent: Double,
            ): ShadowKernel {
                val offsetX = IntArray(SHADOW_STEPS)
                val offsetY = IntArray(SHADOW_STEPS)
                val depthDelta = DoubleArray(SHADOW_STEPS)
                val coneRadius = DoubleArray(SHADOW_STEPS)

                for (index in 0 until SHADOW_STEPS) {
                    val t = (index + 1) * SHADOW_STEP_DISTANCE
                    offsetX[index] = round(lightX * t * width).toInt()
                    offsetY[index] = round(lightY * t * height).toInt()
                    depthDelta[index] = lightZ * t
                    coneRadius[index] = max(t * coneTangent, MIN_CONE_RADIUS)
                }

                return ShadowKernel(
                    offsetX = offsetX,
                    offsetY = offsetY,
                    depthDelta = depthDelta,
                    coneRadius = coneRadius,
                )
            }
        }
    }

    private fun quantize(value: Double): Byte =
        (value.coerceIn(0.0, 1.0) * 255.0).roundToInt().toByte()

    private companion object {
        const val SHADOW_STEPS = 24
        const val SHADOW_STEP_DISTANCE = 0.015
        const val MIN_CONE_RADIUS = 1e-4
    }
}
