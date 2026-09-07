package app.lifeos.core.scene

import app.lifeos.core.image.ForwardSynthesisContext
import app.lifeos.core.image.ForwardSynthesisPass
import app.lifeos.core.image.HyperspectralExpansionPass
import app.lifeos.core.image.IntrinsicMaterialSample
import app.lifeos.core.image.ProceduralMmsiProfile
import app.lifeos.core.image.RgbSample
import app.lifeos.core.image.Rgba8Image
import app.lifeos.core.image.SurfaceNormal
import kotlin.math.roundToInt

interface ProceduralSceneImageRenderer {
    val rendererId: String
    fun render(buffers: MmsiSceneRasterBuffers): Rgba8Image
}

/**
 * Guaranteed offline/no-GPU reference renderer for already-intrinsic procedural scene buffers.
 * It deliberately skips inverse radiometry and applies the same spectral expansion + GGX forward
 * synthesis contract used by the hardware path.
 */
class ReferenceCpuProceduralMmsiRenderer(
    private val profile: ProceduralMmsiProfile = ProceduralMmsiProfile(),
) : ProceduralSceneImageRenderer {
    override val rendererId: String = "mmsi-cpu-procedural-v1"

    private val expansion = HyperspectralExpansionPass(profile.reconstructor)
    private val forward = ForwardSynthesisPass(profile.reconstructor)

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
            val material = IntrinsicMaterialSample(
                linearDiffuseAlbedo = RgbSample(
                    buffers.albedoLinearRgba[albedoBase].toDouble(),
                    buffers.albedoLinearRgba[albedoBase + 1].toDouble(),
                    buffers.albedoLinearRgba[albedoBase + 2].toDouble(),
                ),
                normal = normal,
                roughness = roughness,
                removedSpecular = 0.0,
                effectiveLight = 1.0,
            )
            val spectral = expansion.execute(
                material = material,
                incident = profile.normalizedDaylight.curve,
                pixelX = pixel % buffers.size.width,
                pixelY = pixel / buffers.size.width,
            )
            val color = forward.execute(
                pixel = spectral,
                illuminant = profile.normalizedDaylight,
                context = ForwardSynthesisContext(
                    sunDirection = profile.sunDirection,
                    sunColorLinear = profile.sunColorLinear,
                    skyAmbientLinear = profile.skyAmbientLinear,
                    roughness = roughness,
                ),
            )
            rgba[out] = quantize(color.r)
            rgba[out + 1] = quantize(color.g)
            rgba[out + 2] = quantize(color.b)
        }
        return Rgba8Image(buffers.size.width, buffers.size.height, rgba)
    }

    private fun quantize(value: Double): Byte =
        (value.coerceIn(0.0, 1.0) * 255.0).roundToInt().toByte()
}
