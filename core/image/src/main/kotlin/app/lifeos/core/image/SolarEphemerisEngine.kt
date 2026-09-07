package app.lifeos.core.image

import java.time.Instant
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/** Deterministic solar position calculator using a compact NOAA-style approximation. */
class SolarEphemerisEngine {
    fun position(latitudeDeg: Double, longitudeDeg: Double, instant: Instant): SolarPosition {
        require(latitudeDeg in -90.0..90.0)
        require(longitudeDeg in -180.0..180.0)

        val julianDay = instant.toEpochMilli() / 86_400_000.0 + 2_440_587.5
        val t = (julianDay - 2_451_545.0) / 36_525.0
        val geomMeanLong = normalizeDegrees(280.46646 + t * (36_000.76983 + t * 0.0003032))
        val geomMeanAnomaly = 357.52911 + t * (35_999.05029 - 0.0001537 * t)
        val eccentricity = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)
        val anomalyRad = geomMeanAnomaly.toRadians()
        val equationOfCenter = sin(anomalyRad) * (1.914602 - t * (0.004817 + 0.000014 * t)) +
            sin(2.0 * anomalyRad) * (0.019993 - 0.000101 * t) +
            sin(3.0 * anomalyRad) * 0.000289
        val trueLongitude = geomMeanLong + equationOfCenter
        val omega = 125.04 - 1934.136 * t
        val apparentLongitude = trueLongitude - 0.00569 - 0.00478 * sin(omega.toRadians())
        val meanObliquity = 23.0 + (26.0 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60.0) / 60.0
        val correctedObliquity = meanObliquity + 0.00256 * cos(omega.toRadians())
        val declination = asin(sin(correctedObliquity.toRadians()) * sin(apparentLongitude.toRadians()))

        val y = kotlin.math.tan(correctedObliquity.toRadians() / 2.0).let { it * it }
        val l0 = geomMeanLong.toRadians()
        val equationOfTimeMinutes = 4.0 * Math.toDegrees(
            y * sin(2.0 * l0) -
                2.0 * eccentricity * sin(anomalyRad) +
                4.0 * eccentricity * y * sin(anomalyRad) * cos(2.0 * l0) -
                0.5 * y * y * sin(4.0 * l0) -
                1.25 * eccentricity * eccentricity * sin(2.0 * anomalyRad),
        )

        val utcMinutes = ((instant.epochSecond % 86_400L + 86_400L) % 86_400L) / 60.0 + instant.nano / 60_000_000_000.0
        val trueSolarMinutes = normalizeMinutes(utcMinutes + equationOfTimeMinutes + 4.0 * longitudeDeg)
        val hourAngleDeg = if (trueSolarMinutes / 4.0 < 0.0) trueSolarMinutes / 4.0 + 180.0 else trueSolarMinutes / 4.0 - 180.0
        val hourAngle = hourAngleDeg.toRadians()
        val latitude = latitudeDeg.toRadians()

        val cosZenith = (sin(latitude) * sin(declination) + cos(latitude) * cos(declination) * cos(hourAngle))
            .coerceIn(-1.0, 1.0)
        val zenith = acos(cosZenith)
        val elevation = PI / 2.0 - zenith

        // Azimuth clockwise from geographic north.
        var azimuth = Math.toDegrees(
            atan2(
                sin(hourAngle),
                cos(hourAngle) * sin(latitude) - kotlin.math.tan(declination) * cos(latitude),
            ),
        ) + 180.0
        azimuth = normalizeDegrees(azimuth)

        val azimuthRad = azimuth.toRadians()
        val vector = SolarVector(
            x = sin(azimuthRad) * cos(elevation), // east
            y = cos(azimuthRad) * cos(elevation), // north
            z = sin(elevation),                   // up
        ).normalized()

        return SolarPosition(
            azimuthDeg = azimuth,
            elevationDeg = Math.toDegrees(elevation),
            zenithDeg = Math.toDegrees(zenith),
            vector = vector,
        )
    }

    private fun normalizeDegrees(value: Double): Double {
        val turns = floor(value / 360.0)
        return value - turns * 360.0
    }

    private fun normalizeMinutes(value: Double): Double {
        val day = 1440.0
        val turns = floor(value / day)
        return value - turns * day
    }

    private fun Double.toRadians(): Double = this * PI / 180.0
}
