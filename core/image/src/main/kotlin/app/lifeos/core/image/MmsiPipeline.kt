package app.lifeos.core.image

import java.time.Instant
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.pow

enum class MmsiBackend { CPU_REFERENCE, NATIVE_SIMD, VULKAN_COMPUTE }

data class MmsiPerformanceBudget(
    val peakRamBytes: Long = 100L * 1024L * 1024L,
    val totalLatencyMs: Double = 40.0,
    val deLightingLatencyMs: Double = 5.0,
    val forwardSynthesisLatencyMs: Double = 15.0,
) {
    init {
        require(peakRamBytes > 0)
        require(totalLatencyMs > 0.0)
        require(deLightingLatencyMs > 0.0)
        require(forwardSynthesisLatencyMs > 0.0)
    }
}

data class DeLightingContext(
    val sunDirection: SolarVector,
    val sunIntensity: Double,
    val ambientIntensity: Double,
    val viewDirection: SolarVector = SolarVector(0.0, 0.0, 1.0),
) {
    init {
        require(sunIntensity >= 0.0)
        require(ambientIntensity >= 0.0)
    }
}

data class MaterialObservation(
    val srgb: RgbSample,
    val normal: SurfaceNormal,
    val roughness: Double,
    val sunVisibility: Double = 1.0,
) {
    init {
        require(roughness in 0.0..1.0)
        require(sunVisibility in 0.0..1.0)
    }
}

data class IntrinsicMaterialSample(
    val linearDiffuseAlbedo: RgbSample,
    val normal: SurfaceNormal,
    val roughness: Double,
    val removedSpecular: Double,
    val effectiveLight: Double,
)

private fun stableHalfVector(light: SolarVector, view: SolarVector): SolarVector {
    val x = light.x + view.x
    val y = light.y + view.y
    val z = light.z + view.z
    val lengthSquared = x * x + y * y + z * z
    return if (lengthSquared <= 1e-12) view.normalized() else SolarVector(x, y, z).normalized()
}

/** Phase 1: deterministic inverse radiometry with GGX-based specular removal. */
class InverseRadiometryPass {
    fun execute(observation: MaterialObservation, context: DeLightingContext): IntrinsicMaterialSample {
        val n = observation.normal.normalized()
        val l = context.sunDirection.normalized()
        val v = context.viewDirection.normalized()
        val nDotL = max(0.001, n.dot(l) * observation.sunVisibility)
        val h = stableHalfVector(l, v)
        val nDotH = max(0.0, n.dot(h))
        val nDotV = max(0.001, n.dot(v))
        val rough = max(0.04, observation.roughness)
        val alpha = rough * rough
        val alphaSq = alpha * alpha
        val denom = nDotH * nDotH * (alphaSq - 1.0) + 1.0
        val dGgx = alphaSq / (PI * denom * denom)
        val specularEstimate = (dGgx * 0.04) / (4.0 * nDotL * nDotV)
        val totalLight = max(1e-6, context.sunIntensity * nDotL + context.ambientIntensity)

        val linear = doubleArrayOf(
            ColorTransfer.srgbToLinear(observation.srgb.r),
            ColorTransfer.srgbToLinear(observation.srgb.g),
            ColorTransfer.srgbToLinear(observation.srgb.b),
        )
        val albedo = linear.map { ((it - specularEstimate) / totalLight).coerceIn(0.0, 1.0) }
        return IntrinsicMaterialSample(
            linearDiffuseAlbedo = RgbSample(albedo[0], albedo[1], albedo[2]),
            normal = n,
            roughness = rough,
            removedSpecular = specularEstimate.coerceAtLeast(0.0),
            effectiveLight = totalLight,
        )
    }
}

data class WaveFieldPhase(
    val phaseX: Double,
    val phaseY: Double,
    val amplitude: Double = 0.0,
    val spatialFrequency: Double = 1.0,
) {
    init {
        require(amplitude in 0.0..0.2)
        require(spatialFrequency > 0.0)
    }
}

/** Phase 2: regularized spectral expansion plus bounded deterministic microtexture modulation. */
class HyperspectralExpansionPass(
    private val reconstructor: SpectralReconstructor,
) {
    fun execute(
        material: IntrinsicMaterialSample,
        incident: SpectralCurve,
        pixelX: Int,
        pixelY: Int,
        waveField: WaveFieldPhase = WaveFieldPhase(0.0, 0.0),
    ): SpectralPixel {
        val reconstructed = reconstructor.reconstruct(material.linearDiffuseAlbedo, incident)
        val modulated = reconstructed.reflectance.values.copyOf()
        if (waveField.amplitude > 0.0) {
            val phase = (pixelX * waveField.phaseX + pixelY * waveField.phaseY) * waveField.spatialFrequency
            val spatial = kotlin.math.sin(phase) * waveField.amplitude
            for (i in modulated.indices) {
                val bandPhase = i.toDouble() / max(1, modulated.lastIndex)
                val spectralWindow = 0.5 + 0.5 * kotlin.math.cos((bandPhase - 0.5) * PI)
                modulated[i] = (modulated[i] * (1.0 + spatial * spectralWindow)).coerceIn(0.0, 1.0)
            }
        }
        return SpectralPixel(
            reflectance = SpectralCurve(reconstructed.reflectance.grid, modulated),
            normal = material.normal,
            confidence = reconstructed.confidence,
            reconstructionError = reconstructed.reconstructionError,
        )
    }
}

data class ForwardSynthesisContext(
    val sunDirection: SolarVector,
    val sunColorLinear: RgbSample,
    val skyAmbientLinear: RgbSample,
    val roughness: Double,
    val shadowVisibility: Double = 1.0,
    val ambientOcclusion: Double = 1.0,
) {
    init {
        require(roughness in 0.0..1.0)
        require(shadowVisibility in 0.0..1.0)
        require(ambientOcclusion in 0.0..1.0)
    }
}

/** Phase 3 CPU reference for the GGX forward renderer. Vulkan must match this contract. */
class ForwardSynthesisPass(
    private val reconstructor: SpectralReconstructor,
) {
    fun execute(pixel: SpectralPixel, illuminant: IlluminantSpectrum, context: ForwardSynthesisContext): RgbSample {
        val albedo = reconstructor.predictRgb(pixel.reflectance.values, illuminant.curve)
        val n = pixel.normal.normalized()
        val l = context.sunDirection.normalized()
        val v = SolarVector(0.0, 0.0, 1.0)
        val h = stableHalfVector(l, v)
        val nDotL = max(0.0, n.dot(l))
        val nDotV = max(0.001, n.dot(v))
        val nDotH = max(0.0, n.dot(h))
        val hDotV = max(0.0, h.x * v.x + h.y * v.y + h.z * v.z)
        val rough = max(0.04, context.roughness)
        val a = rough * rough
        val a2 = a * a
        val denom = nDotH * nDotH * (a2 - 1.0) + 1.0
        val d = a2 / (PI * denom * denom)
        val g = geometrySchlickGgx(nDotV, rough) * geometrySchlickGgx(nDotL, rough)
        val f = fresnelSchlick(hDotV, 0.04)
        val specular = (d * g * f) / (4.0 * nDotV * max(0.001, nDotL) + 1e-4)
        val kd = 1.0 - f
        val channels = doubleArrayOf(albedo.r, albedo.g, albedo.b)
        val sun = doubleArrayOf(context.sunColorLinear.r, context.sunColorLinear.g, context.sunColorLinear.b)
        val sky = doubleArrayOf(context.skyAmbientLinear.r, context.skyAmbientLinear.g, context.skyAmbientLinear.b)
        val out = DoubleArray(3)
        for (i in 0..2) {
            val direct = (kd * channels[i] / PI + specular) * sun[i] * nDotL * context.shadowVisibility
            val ambient = channels[i] * sky[i] * context.ambientOcclusion
            out[i] = acesToneMap(direct + ambient)
        }
        return RgbSample(
            ColorTransfer.linearToSrgb(out[0]),
            ColorTransfer.linearToSrgb(out[1]),
            ColorTransfer.linearToSrgb(out[2]),
        )
    }

    private fun fresnelSchlick(cosTheta: Double, f0: Double): Double =
        f0 + (1.0 - f0) * (1.0 - cosTheta.coerceIn(0.0, 1.0)).pow(5.0)

    private fun geometrySchlickGgx(nDotV: Double, roughness: Double): Double {
        val r = roughness + 1.0
        val k = r * r / 8.0
        return nDotV / (nDotV * (1.0 - k) + k)
    }

    private fun acesToneMap(value: Double): Double {
        val v = max(0.0, value)
        return ((v * (2.51 * v + 0.03)) / (v * (2.43 * v + 0.59) + 0.14)).coerceIn(0.0, 1.0)
    }
}

data class MmsiPipelineRequest(
    val observation: MaterialObservation,
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val instant: Instant,
    val deLightingContext: DeLightingContext,
    val relightContext: ForwardSynthesisContext,
    val waveField: WaveFieldPhase = WaveFieldPhase(0.0, 0.0),
)

data class MmsiPipelineResult(
    val intrinsic: IntrinsicMaterialSample,
    val spectral: SpectralPixel,
    val output: RgbSample,
    val solarPosition: SolarPosition,
    val backend: MmsiBackend,
)
