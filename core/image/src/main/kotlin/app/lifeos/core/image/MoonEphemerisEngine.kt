package app.lifeos.core.image

import java.time.Instant
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

data class MoonPosition(
    val azimuthDeg: Double,
    val elevationDeg: Double,
    val vector: SolarVector,
    val illuminatedFraction: Double,
    val phaseAgeDays: Double,
    val distanceEarthRadii: Double,
    val modelId: String = MoonEphemerisEngine.MODEL_ID,
) {
    init {
        require(azimuthDeg.isFinite() && elevationDeg.isFinite())
        require(illuminatedFraction in 0.0..1.0)
        require(phaseAgeDays >= 0.0)
        require(distanceEarthRadii > 0.0)
    }
}

/**
 * Deterministic low-precision analytical lunar ephemeris suitable for scene lighting.
 * It is deliberately not advertised as observatory/JPL precision; the output is a stable
 * offline moon direction, approximate distance and synodic illumination fraction.
 */
class MoonEphemerisEngine {
    fun position(latitudeDeg: Double, longitudeDeg: Double, instant: Instant): MoonPosition {
        require(latitudeDeg in -90.0..90.0)
        require(longitudeDeg in -180.0..180.0)

        val jd = instant.toEpochMilli() / 86_400_000.0 + 2_440_587.5
        val d = jd - 2_451_543.5

        val nodeDeg = normalizeDegrees(125.1228 - 0.0529538083 * d)
        val inclinationDeg = 5.1454
        val periapsisDeg = normalizeDegrees(318.0634 + 0.1643573223 * d)
        val semiMajorEarthRadii = 60.2666
        val eccentricity = 0.054900
        val meanAnomalyDeg = normalizeDegrees(115.3654 + 13.0649929509 * d)

        val meanAnomaly = meanAnomalyDeg.toRadians()
        // First/second-order Kepler approximation is sufficient for this lighting model.
        val eccentricAnomaly = meanAnomaly +
            eccentricity * sin(meanAnomaly) * (1.0 + eccentricity * cos(meanAnomaly))
        val xv = semiMajorEarthRadii * (cos(eccentricAnomaly) - eccentricity)
        val yv = semiMajorEarthRadii * sqrt(1.0 - eccentricity * eccentricity) * sin(eccentricAnomaly)
        val trueAnomaly = atan2(yv, xv)
        val distance = sqrt(xv * xv + yv * yv)

        val node = nodeDeg.toRadians()
        val inclination = inclinationDeg.toRadians()
        val longitudeInOrbit = trueAnomaly + periapsisDeg.toRadians()
        val xEcl = distance * (
            cos(node) * cos(longitudeInOrbit) - sin(node) * sin(longitudeInOrbit) * cos(inclination)
        )
        val yEcl = distance * (
            sin(node) * cos(longitudeInOrbit) + cos(node) * sin(longitudeInOrbit) * cos(inclination)
        )
        val zEcl = distance * sin(longitudeInOrbit) * sin(inclination)

        val obliquity = (23.4393 - 3.563e-7 * d).toRadians()
        val xEq = xEcl
        val yEq = yEcl * cos(obliquity) - zEcl * sin(obliquity)
        val zEq = yEcl * sin(obliquity) + zEcl * cos(obliquity)
        val rightAscension = atan2(yEq, xEq)
        val declination = atan2(zEq, sqrt(xEq * xEq + yEq * yEq))

        val gmstDeg = normalizeDegrees(280.46061837 + 360.98564736629 * (jd - 2_451_545.0))
        val localSidereal = normalizeDegrees(gmstDeg + longitudeDeg).toRadians()
        val hourAngle = normalizeRadiansSigned(localSidereal - rightAscension)
        val latitude = latitudeDeg.toRadians()
        val elevation = asin(
            (sin(latitude) * sin(declination) + cos(latitude) * cos(declination) * cos(hourAngle))
                .coerceIn(-1.0, 1.0)
        )
        var azimuthDeg = Math.toDegrees(
            atan2(
                sin(hourAngle),
                cos(hourAngle) * sin(latitude) - kotlin.math.tan(declination) * cos(latitude),
            )
        ) + 180.0
        azimuthDeg = normalizeDegrees(azimuthDeg)

        // First-order topocentric altitude correction for lunar horizontal parallax.
        val horizontalParallax = asin((1.0 / distance).coerceIn(-1.0, 1.0))
        val topocentricElevation = elevation - horizontalParallax * cos(elevation)
        val azimuth = azimuthDeg.toRadians()
        val vector = SolarVector(
            x = sin(azimuth) * cos(topocentricElevation),
            y = cos(azimuth) * cos(topocentricElevation),
            z = sin(topocentricElevation),
        ).normalized()

        val phase = phaseAt(instant)
        return MoonPosition(
            azimuthDeg = azimuthDeg,
            elevationDeg = Math.toDegrees(topocentricElevation),
            vector = vector,
            illuminatedFraction = phase.second,
            phaseAgeDays = phase.first,
            distanceEarthRadii = distance,
        )
    }

    private fun phaseAt(instant: Instant): Pair<Double, Double> {
        val days = (instant.toEpochMilli() - NEW_MOON_EPOCH_MILLIS) / 86_400_000.0
        val age = positiveModulo(days, SYNODIC_MONTH_DAYS)
        val phaseAngle = 2.0 * PI * age / SYNODIC_MONTH_DAYS
        val illuminated = ((1.0 - cos(phaseAngle)) * 0.5).coerceIn(0.0, 1.0)
        return age to illuminated
    }

    private fun normalizeDegrees(value: Double): Double = positiveModulo(value, 360.0)

    private fun normalizeRadiansSigned(value: Double): Double {
        var result = positiveModulo(value + PI, 2.0 * PI) - PI
        if (result <= -PI) result += 2.0 * PI
        return result
    }

    private fun positiveModulo(value: Double, modulus: Double): Double {
        val turns = floor(value / modulus)
        return value - turns * modulus
    }

    private fun Double.toRadians(): Double = this * PI / 180.0

    companion object {
        const val MODEL_ID = "lunar-analytic-low-precision-v1"
        private const val SYNODIC_MONTH_DAYS = 29.530588853
        // 2000-01-06T18:14:00Z, a commonly used new-moon reference for synodic phase.
        private const val NEW_MOON_EPOCH_MILLIS = 947_182_440_000L
    }
}
