package app.lifeos.core.image

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin

/**
 * Compact deterministic visible-spectrum atmosphere model.
 * It models direct extinction with Beer-Lambert-Bouguer and a bounded diffuse sky term.
 * The default extraterrestrial spectrum is a normalized 5778 K black-body approximation.
 */
class AtmosphericRadiometryEngine(
    private val grid: SpectralGrid = SpectralGrid(),
) {
    data class Atmosphere(
        val aerosolOpticalDepth550: Double = 0.12,
        val angstromExponent: Double = 1.3,
        val diffuseFloor: Double = 0.06,
    ) {
        init {
            require(aerosolOpticalDepth550 >= 0.0)
            require(angstromExponent >= 0.0)
            require(diffuseFloor in 0.0..1.0)
        }
    }

    data class GroundIllumination(
        val direct: SpectralCurve,
        val diffuse: SpectralCurve,
        val total: SpectralCurve,
        val airMass: Double,
    )

    fun illumination(
        solarPosition: SolarPosition,
        atmosphere: Atmosphere = Atmosphere(),
    ): GroundIllumination {
        val airMass = relativeAirMass(solarPosition.zenithDeg)
        val direct = DoubleArray(grid.bandCount)
        val diffuse = DoubleArray(grid.bandCount)
        val total = DoubleArray(grid.bandCount)

        for (i in 0 until grid.bandCount) {
            val lambdaNm = grid.wavelengthNm(i).toDouble()
            val extraterrestrial = normalizedSolarSpectrum(lambdaNm)
            val tauRayleigh = rayleighOpticalDepth(lambdaNm)
            val tauAerosol = atmosphere.aerosolOpticalDepth550 * (lambdaNm / 550.0).pow(-atmosphere.angstromExponent)
            val transmission = if (solarPosition.elevationDeg <= -6.0) {
                0.0
            } else {
                exp(-airMass * (tauRayleigh + tauAerosol))
            }
            val directValue = extraterrestrial * transmission
            val scatteredFraction = (1.0 - transmission).coerceIn(0.0, 1.0)
            val diffuseValue = extraterrestrial * (atmosphere.diffuseFloor + 0.45 * scatteredFraction)
            direct[i] = directValue
            diffuse[i] = diffuseValue
            total[i] = directValue + diffuseValue
        }

        return GroundIllumination(
            direct = SpectralCurve(grid, direct),
            diffuse = SpectralCurve(grid, diffuse),
            total = SpectralCurve(grid, total),
            airMass = airMass,
        )
    }

    fun effectiveIncidentSpectrum(
        illumination: GroundIllumination,
        normal: SurfaceNormal,
        sun: SolarVector,
        visibility: Double = 1.0,
    ): SpectralCurve {
        require(visibility in 0.0..1.0)
        val nDotS = normal.normalized().dot(sun.normalized()).coerceAtLeast(0.0)
        val values = DoubleArray(grid.bandCount) { i ->
            illumination.diffuse[i] + illumination.direct[i] * nDotS * visibility
        }
        return SpectralCurve(grid, values)
    }

    /** Kasten-Young 1989 relative optical air mass approximation. */
    fun relativeAirMass(zenithDeg: Double): Double {
        if (zenithDeg >= 90.0) return 40.0
        val z = zenithDeg.coerceIn(0.0, 89.999)
        val sinElevation = sin((90.0 - z) * PI / 180.0)
        return 1.0 / (sinElevation + 0.50572 * (6.07995 + (90.0 - z)).pow(-1.6364))
    }

    private fun rayleighOpticalDepth(lambdaNm: Double): Double {
        val lambdaMicrometers = lambdaNm / 1000.0
        val inv2 = 1.0 / (lambdaMicrometers * lambdaMicrometers)
        return 0.008569 * inv2 * inv2 * (1.0 + 0.0113 * inv2 + 0.00013 * inv2 * inv2)
    }

    private fun normalizedSolarSpectrum(lambdaNm: Double): Double {
        val temperatureK = 5778.0
        val wavelengthM = lambdaNm * 1e-9
        val c2 = 1.438776877e-2
        val raw = 1.0 / (wavelengthM.pow(5.0) * (exp(c2 / (wavelengthM * temperatureK)) - 1.0))
        val referenceM = 550e-9
        val reference = 1.0 / (referenceM.pow(5.0) * (exp(c2 / (referenceM * temperatureK)) - 1.0))
        return (raw / reference).coerceAtLeast(0.0)
    }
}
