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
        require(roughness in 0.0..1.0)
        require(shadowVisibility in 0.0..1.0)
        require(ambientOcclusion in 0.0..1.0)

        val coefficients = profile.coefficientProjection.project(intrinsicLinearAlbedo)
        val baseColor = profile.rgbProjection.project(coefficients)

        val nDotL = max(normal.dot(light), 0.0)
        val nDotV = max(normal.dot(view), 0.001)
        val nDotH = max(normal.dot(halfVector), 0.0)
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

        return RgbSample(
            shadeChannel(
                base = baseColor.r,
                sun = profile.sunColorLinear.r,
                sky = profile.skyAmbientLinear.r,
                kd = kd,
                specular = specular,
                nDotL = nDotL,
                shadowVisibility = shadowVisibility,
                ambientOcclusion = ambientOcclusion,
            ),
            shadeChannel(
                base = baseColor.g,
                sun = profile.sunColorLinear.g,
                sky = profile.skyAmbientLinear.g,
                kd = kd,
                specular = specular,
                nDotL = nDotL,
                shadowVisibility = shadowVisibility,
                ambientOcclusion = ambientOcclusion,
            ),
            shadeChannel(
                base = baseColor.b,
                sun = profile.sunColorLinear.b,
                sky = profile.skyAmbientLinear.b,
                kd = kd,
                specular = specular,
                nDotL = nDotL,
                shadowVisibility = shadowVisibility,
                ambientOcclusion = ambientOcclusion,
            ),
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
