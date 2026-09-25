package app.lifeos.core.image

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.pow

/** CPU reference for the compact six-coefficient procedural MMSI Vulkan shading contract. */
class ProceduralMmsiReferenceShading(
    private val profile: ProceduralMmsiProfile = ProceduralMmsiProfile(),
) {
    private val light = profile.sunDirection.normalized()
    private val view = SolarVector(0.0, 0.0, 1.0)
    private val halfVector: SolarVector = run {
        val raw = SolarVector(
            light.x + view.x,
            light.y + view.y,
            light.z + view.z,
        )
        val lengthSq = raw.x * raw.x + raw.y * raw.y + raw.z * raw.z
        if (lengthSq <= 1e-10) view else raw.normalized()
    }

    class Scratch internal constructor(
        internal val coefficients: DoubleArray,
        internal val projectedBaseRgb: DoubleArray,
        val rgb: DoubleArray,
        internal var lastAlbedoRBits: Long = Long.MIN_VALUE,
        internal var lastAlbedoGBits: Long = Long.MIN_VALUE,
        internal var lastAlbedoBBits: Long = Long.MIN_VALUE,
    )

    fun newScratch(): Scratch =
        Scratch(
            coefficients = DoubleArray(profile.coefficientProjection.componentCount),
            projectedBaseRgb = DoubleArray(3),
            rgb = DoubleArray(3),
        )

    fun shade(
        intrinsicLinearAlbedo: RgbSample,
        normal: SurfaceNormal,
        roughness: Double,
        shadowVisibility: Double = 1.0,
        ambientOcclusion: Double = 1.0,
    ): RgbSample =
        shadeNormalized(
            intrinsicLinearAlbedo = intrinsicLinearAlbedo,
            normal = normal.normalized(),
            roughness = roughness,
            shadowVisibility = shadowVisibility,
            ambientOcclusion = ambientOcclusion,
        )

    /**
     * Fast path for rasterizers that already guarantee unit-length normals.
     * It retains the exact spectral/GGX contract while avoiding a second per-pixel normalization.
     */
    fun shadeNormalized(
        intrinsicLinearAlbedo: RgbSample,
        normal: SurfaceNormal,
        roughness: Double,
        shadowVisibility: Double = 1.0,
        ambientOcclusion: Double = 1.0,
    ): RgbSample {
        val scratch = newScratch()
        shadeNormalizedInto(
            albedoR = intrinsicLinearAlbedo.r,
            albedoG = intrinsicLinearAlbedo.g,
            albedoB = intrinsicLinearAlbedo.b,
            normalX = normal.x,
            normalY = normal.y,
            normalZ = normal.z,
            roughness = roughness,
            shadowVisibility = shadowVisibility,
            ambientOcclusion = ambientOcclusion,
            scratch = scratch,
        )
        return RgbSample(
            scratch.rgb[0],
            scratch.rgb[1],
            scratch.rgb[2],
        )
    }

    fun shadeNormalizedInto(
        albedoR: Double,
        albedoG: Double,
        albedoB: Double,
        normalX: Double,
        normalY: Double,
        normalZ: Double,
        roughness: Double,
        shadowVisibility: Double,
        ambientOcclusion: Double,
        scratch: Scratch,
    ) {
        require(roughness in 0.0..1.0)
        require(shadowVisibility in 0.0..1.0)
        require(ambientOcclusion in 0.0..1.0)

        val rBits = albedoR.toBits()
        val gBits = albedoG.toBits()
        val bBits = albedoB.toBits()
        if (
            rBits != scratch.lastAlbedoRBits ||
            gBits != scratch.lastAlbedoGBits ||
            bBits != scratch.lastAlbedoBBits
        ) {
            val coefficientProjection = profile.coefficientProjection
            val coefficients = scratch.coefficients
            for (component in coefficients.indices) {
                val row = coefficientProjection.rgbToCoefficients[component]
                coefficients[component] =
                    coefficientProjection.bias[component] +
                        row[0] * albedoR +
                        row[1] * albedoG +
                        row[2] * albedoB
            }

            val rgbProjection = profile.rgbProjection
            val projectedBase = scratch.projectedBaseRgb
            for (channel in 0..2) {
                var value = rgbProjection.meanRgb[channel]
                val row = rgbProjection.coefficientsToRgb[channel]
                for (component in coefficients.indices) {
                    value += row[component] * coefficients[component]
                }
                projectedBase[channel] = value.coerceIn(0.0, 1.0)
            }
            scratch.lastAlbedoRBits = rBits
            scratch.lastAlbedoGBits = gBits
            scratch.lastAlbedoBBits = bBits
        }

        val base = scratch.rgb
        base[0] = scratch.projectedBaseRgb[0]
        base[1] = scratch.projectedBaseRgb[1]
        base[2] = scratch.projectedBaseRgb[2]

        val nDotL = max(
            normalX * light.x + normalY * light.y + normalZ * light.z,
            0.0,
        )
        val nDotV = max(
            normalX * view.x + normalY * view.y + normalZ * view.z,
            0.001,
        )
        val nDotH = max(
            normalX * halfVector.x +
                normalY * halfVector.y +
                normalZ * halfVector.z,
            0.0,
        )
        val hDotV = max(
            halfVector.x * view.x +
                halfVector.y * view.y +
                halfVector.z * view.z,
            0.0,
        )
        val rough = roughness.coerceIn(0.04, 1.0)
        val a = rough * rough
        val a2 = a * a
        val denom = nDotH * nDotH * (a2 - 1.0) + 1.0
        val d = a2 / max(PI * denom * denom, 1e-6)
        val g = geometrySchlickGgx(nDotV, rough) * geometrySchlickGgx(nDotL, rough)
        val f = fresnelSchlick(hDotV, 0.04)
        val specular = (d * g * f) / (4.0 * nDotV * max(nDotL, 0.001) + 1e-4)
        val kd = 1.0 - f

        base[0] = shadeChannel(
            base = base[0],
            sun = profile.sunColorLinear.r,
            sky = profile.skyAmbientLinear.r,
            kd = kd,
            specular = specular,
            nDotL = nDotL,
            shadowVisibility = shadowVisibility,
            ambientOcclusion = ambientOcclusion,
        )
        base[1] = shadeChannel(
            base = base[1],
            sun = profile.sunColorLinear.g,
            sky = profile.skyAmbientLinear.g,
            kd = kd,
            specular = specular,
            nDotL = nDotL,
            shadowVisibility = shadowVisibility,
            ambientOcclusion = ambientOcclusion,
        )
        base[2] = shadeChannel(
            base = base[2],
            sun = profile.sunColorLinear.b,
            sky = profile.skyAmbientLinear.b,
            kd = kd,
            specular = specular,
            nDotL = nDotL,
            shadowVisibility = shadowVisibility,
            ambientOcclusion = ambientOcclusion,
        )
    }

    private fun shadeChannel(
        base: Double,
        sun: Double,
        sky: Double,
        kd: Double,
        specular: Double,
        nDotL: Double,
        shadowVisibility: Double,
        ambientOcclusion: Double,
    ): Double {
        val direct =
            (kd * base / PI + specular) * sun * nDotL * shadowVisibility
        val ambient = base * sky * ambientOcclusion
        val mapped = acesToneMap(max(direct + ambient, 0.0))
        return mapped.pow(1.0 / 2.2).coerceIn(0.0, 1.0)
    }

    private fun fresnelSchlick(cosTheta: Double, f0: Double): Double =
        f0 + (1.0 - f0) * (1.0 - cosTheta.coerceIn(0.0, 1.0)).pow(5.0)

    private fun geometrySchlickGgx(nDotV: Double, roughness: Double): Double {
        val r = roughness + 1.0
        val k = r * r / 8.0
        return nDotV / max(nDotV * (1.0 - k) + k, 1e-5)
    }

    private fun acesToneMap(value: Double): Double =
        ((value * (2.51 * value + 0.03)) / (value * (2.43 * value + 0.59) + 0.14)).coerceIn(0.0, 1.0)
}
