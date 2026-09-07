package app.lifeos.core.image

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.pow

/** CPU reference for the compact six-coefficient procedural MMSI Vulkan shading contract. */
class ProceduralMmsiReferenceShading(
    private val profile: ProceduralMmsiProfile = ProceduralMmsiProfile(),
) {
    fun shade(
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
        val n = normal.normalized()
        val l = profile.sunDirection.normalized()
        val v = SolarVector(0.0, 0.0, 1.0)
        val hRaw = SolarVector(l.x + v.x, l.y + v.y, l.z + v.z)
        val hLengthSq = hRaw.x * hRaw.x + hRaw.y * hRaw.y + hRaw.z * hRaw.z
        val h = if (hLengthSq <= 1e-10) v else hRaw.normalized()

        val nDotL = max(n.dot(l), 0.0)
        val nDotV = max(n.dot(v), 0.001)
        val nDotH = max(n.dot(h), 0.0)
        val hDotV = max(h.x * v.x + h.y * v.y + h.z * v.z, 0.0)
        val rough = roughness.coerceIn(0.04, 1.0)
        val a = rough * rough
        val a2 = a * a
        val denom = nDotH * nDotH * (a2 - 1.0) + 1.0
        val d = a2 / max(PI * denom * denom, 1e-6)
        val g = geometrySchlickGgx(nDotV, rough) * geometrySchlickGgx(nDotL, rough)
        val f = fresnelSchlick(hDotV, 0.04)
        val specular = (d * g * f) / (4.0 * nDotV * max(nDotL, 0.001) + 1e-4)
        val kd = 1.0 - f

        val base = doubleArrayOf(baseColor.r, baseColor.g, baseColor.b)
        val sun = doubleArrayOf(profile.sunColorLinear.r, profile.sunColorLinear.g, profile.sunColorLinear.b)
        val sky = doubleArrayOf(profile.skyAmbientLinear.r, profile.skyAmbientLinear.g, profile.skyAmbientLinear.b)
        val output = DoubleArray(3)
        for (channel in 0..2) {
            val direct = (kd * base[channel] / PI + specular) * sun[channel] * nDotL * shadowVisibility
            val ambient = base[channel] * sky[channel] * ambientOcclusion
            val mapped = acesToneMap(max(direct + ambient, 0.0))
            output[channel] = mapped.pow(1.0 / 2.2).coerceIn(0.0, 1.0)
        }
        return RgbSample(output[0], output[1], output[2])
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
