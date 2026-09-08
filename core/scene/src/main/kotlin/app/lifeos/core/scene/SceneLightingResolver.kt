package app.lifeos.core.scene

import app.lifeos.core.image.AtmosphericRadiometryEngine
import app.lifeos.core.image.ProceduralMmsiProfile
import app.lifeos.core.image.RgbSample
import app.lifeos.core.image.SolarEphemerisEngine
import app.lifeos.core.image.SpectralCurve
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.math.exp

/** Offline geographic anchor used by deterministic astronomical scene lighting. */
data class SceneGeoAnchor(
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val zoneId: String,
    val canonicalName: String,
) {
    init {
        require(latitudeDeg in -90.0..90.0)
        require(longitudeDeg in -180.0..180.0)
        require(zoneId.isNotBlank())
        require(canonicalName.isNotBlank())
        ZoneId.of(zoneId)
    }
}

/** Result of resolving a scene environment into a concrete, reproducible lighting profile. */
data class SceneLightingResolution(
    val profile: ProceduralMmsiProfile,
    val environment: SceneEnvironment,
    val astronomical: Boolean,
    val warnings: List<String> = emptyList(),
)

/**
 * Small offline gazetteer plus direct coordinate parser. This deliberately does not require a
 * network geocoder. Coordinates always win; named places are deterministic built-in anchors.
 */
class OfflineSceneLocationResolver {
    fun resolve(text: String?): SceneGeoAnchor? {
        val value = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        parseCoordinates(value)?.let { return it }
        val normalized = normalize(value)
        return NAMED_LOCATIONS.entries
            .sortedByDescending { it.key.length }
            .firstOrNull { (key, _) -> normalized == key || normalized.contains(key) }
            ?.value
    }

    private fun parseCoordinates(value: String): SceneGeoAnchor? {
        val labelled = LABELLED_COORDINATES.find(value)
        if (labelled != null) {
            return coordinateAnchor(
                labelled.groupValues[1].replace(',', '.').toDoubleOrNull(),
                labelled.groupValues[2].replace(',', '.').toDoubleOrNull(),
            )
        }
        val pair = DECIMAL_COORDINATES.find(value) ?: return null
        return coordinateAnchor(
            pair.groupValues[1].replace(',', '.').toDoubleOrNull(),
            pair.groupValues[2].replace(',', '.').toDoubleOrNull(),
        )
    }

    private fun coordinateAnchor(latitude: Double?, longitude: Double?): SceneGeoAnchor? {
        if (latitude == null || longitude == null || latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
        // A numeric coordinate has no reliable civil time zone offline. UTC is explicit and
        // deterministic; callers can use a named place when local civil time is intended.
        return SceneGeoAnchor(latitude, longitude, "UTC", "coordinates:$latitude,$longitude")
    }

    private fun normalize(value: String): String = value
        .lowercase()
        .replace('ä', 'a')
        .replace('ö', 'o')
        .replace('ü', 'u')
        .replace('ß', 's')
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()

    companion object {
        private val NUMBER = "[-+]?\\d{1,3}(?:[.,]\\d+)?"
        private val LABELLED_COORDINATES = Regex("(?i)lat(?:itude)?\\s*[=:]?\\s*($NUMBER)\\s*[,; ]+\\s*(?:lon|lng|longitude)\\s*[=:]?\\s*($NUMBER)")
        private val DECIMAL_COORDINATES = Regex("(?<!\\d)($NUMBER)\\s*[,;/ ]\\s*($NUMBER)(?!\\d)")

        private val NAMED_LOCATIONS = mapOf(
            "brandenburger tor" to SceneGeoAnchor(52.516275, 13.377704, "Europe/Berlin", "Brandenburger Tor"),
            "berlin" to SceneGeoAnchor(52.520008, 13.404954, "Europe/Berlin", "Berlin"),
            "hamburg" to SceneGeoAnchor(53.551086, 9.993682, "Europe/Berlin", "Hamburg"),
            "munchen" to SceneGeoAnchor(48.137154, 11.576124, "Europe/Berlin", "München"),
            "muenchen" to SceneGeoAnchor(48.137154, 11.576124, "Europe/Berlin", "München"),
            "koln" to SceneGeoAnchor(50.937531, 6.960279, "Europe/Berlin", "Köln"),
            "koeln" to SceneGeoAnchor(50.937531, 6.960279, "Europe/Berlin", "Köln"),
            "frankfurt" to SceneGeoAnchor(50.110924, 8.682127, "Europe/Berlin", "Frankfurt am Main"),
            "stuttgart" to SceneGeoAnchor(48.775846, 9.182932, "Europe/Berlin", "Stuttgart"),
            "dusseldorf" to SceneGeoAnchor(51.227741, 6.773456, "Europe/Berlin", "Düsseldorf"),
            "leipzig" to SceneGeoAnchor(51.339695, 12.373075, "Europe/Berlin", "Leipzig"),
            "dresden" to SceneGeoAnchor(51.050409, 13.737262, "Europe/Berlin", "Dresden"),
            "hannover" to SceneGeoAnchor(52.375892, 9.732010, "Europe/Berlin", "Hannover"),
            "bremen" to SceneGeoAnchor(53.079296, 8.801694, "Europe/Berlin", "Bremen"),
            "nurnberg" to SceneGeoAnchor(49.452102, 11.076665, "Europe/Berlin", "Nürnberg"),
            "london" to SceneGeoAnchor(51.507351, -0.127758, "Europe/London", "London"),
            "paris" to SceneGeoAnchor(48.856613, 2.352222, "Europe/Paris", "Paris"),
            "tokyo" to SceneGeoAnchor(35.676422, 139.650027, "Asia/Tokyo", "Tokyo"),
        )
    }
}

/**
 * Deterministically resolves scene location + civil time into sun geometry and atmospheric light.
 * If any required value cannot be resolved, the supplied neutral profile is returned unchanged.
 */
class SceneLightingResolver(
    private val ephemeris: SolarEphemerisEngine = SolarEphemerisEngine(),
    private val locationResolver: OfflineSceneLocationResolver = OfflineSceneLocationResolver(),
) {
    fun resolve(
        environment: SceneEnvironment,
        referenceInstant: Instant,
        fallbackProfile: ProceduralMmsiProfile = ProceduralMmsiProfile(),
    ): SceneLightingResolution {
        if (environment.lightingMode != LightingMode.ASTRONOMICAL_IF_RESOLVED) {
            return SceneLightingResolution(fallbackProfile, environment, astronomical = false)
        }

        val warnings = mutableListOf<String>()
        val anchor = locationResolver.resolve(environment.locationText)
            ?: return fallback(environment, fallbackProfile, "astronomical location could not be resolved")
        val zone = runCatching { ZoneId.of(anchor.zoneId) }.getOrElse {
            return fallback(environment, fallbackProfile, "time zone could not be resolved")
        }
        val date = parseDate(environment.dateText, referenceInstant, zone)
            ?: return fallback(environment, fallbackProfile, "scene date could not be resolved")
        val time = parseTime(environment.timeText)
            ?: return fallback(environment, fallbackProfile, "scene time could not be resolved")
        val localDateTime = LocalDateTime.of(date, time)
        val offsets = zone.rules.getValidOffsets(localDateTime)
        val instant = when {
            offsets.size == 1 -> localDateTime.toInstant(offsets.single())
            offsets.size > 1 -> {
                warnings += "civil time is ambiguous at daylight-saving overlap; earlier offset selected"
                localDateTime.toInstant(offsets.first())
            }
            else -> {
                warnings += "civil time falls in daylight-saving gap; zone transition adjustment applied"
                localDateTime.atZone(zone).toInstant()
            }
        }

        val solar = ephemeris.position(anchor.latitudeDeg, anchor.longitudeDeg, instant)
        val phase = astronomicalPhase(solar.elevationDeg)
        val atmosphere = AtmosphericRadiometryEngine(fallbackProfile.grid)
        val illumination = atmosphere.illumination(solar)
        val directRgb = if (phase == AstronomicalLightPhase.DAY) {
            spectrumToLinearRgb(illumination.direct)
        } else {
            RgbSample(0.0, 0.0, 0.0)
        }
        val diffuseBase = spectrumToLinearRgb(illumination.diffuse)
        val skyScale = skyRadianceScale(phase, solar.elevationDeg)
        val skyTint = nightSkyTint(phase)
        val diffuseRgb = RgbSample(
            (diffuseBase.r * skyScale * skyTint.r).coerceIn(0.0, 1.0),
            (diffuseBase.g * skyScale * skyTint.g).coerceIn(0.0, 1.0),
            (diffuseBase.b * skyScale * skyTint.b).coerceIn(0.0, 1.0),
        )
        if (phase == AstronomicalLightPhase.NIGHT) {
            warnings += "direct solar term disabled; deep-night sky only until lunar/local-light resolver is available"
        }
        val resolvedProfile = ProceduralMmsiProfile(
            grid = fallbackProfile.grid,
            sunDirection = solar.vector,
            sunColorLinear = directRgb,
            skyAmbientLinear = diffuseRgb,
            sunSolidAngleRad = fallbackProfile.sunSolidAngleRad,
            shadowFloor = fallbackProfile.shadowFloor,
        )
        val resolvedEnvironment = environment.copy(
            resolvedLatitudeDeg = anchor.latitudeDeg,
            resolvedLongitudeDeg = anchor.longitudeDeg,
            resolvedZoneId = anchor.zoneId,
            resolvedInstantUtc = instant.toString(),
            sunAzimuthDeg = solar.azimuthDeg,
            sunElevationDeg = solar.elevationDeg,
            astronomicalPhase = phase,
        )
        return SceneLightingResolution(
            profile = resolvedProfile,
            environment = resolvedEnvironment,
            astronomical = true,
            warnings = warnings,
        )
    }

    private fun fallback(
        environment: SceneEnvironment,
        profile: ProceduralMmsiProfile,
        warning: String,
    ) = SceneLightingResolution(
        profile = profile,
        environment = environment.copy(lightingMode = LightingMode.SYNTHETIC_NEUTRAL),
        astronomical = false,
        warnings = listOf(warning),
    )

    private fun parseDate(text: String?, referenceInstant: Instant, zone: ZoneId): LocalDate? {
        val value = text?.trim()?.lowercase() ?: return null
        val referenceDate = referenceInstant.atZone(zone).toLocalDate()
        return when (value) {
            "relative:today" -> referenceDate
            "relative:yesterday" -> referenceDate.minusDays(1)
            "relative:tomorrow" -> referenceDate.plusDays(1)
            else -> parseAbsoluteDate(value, referenceDate.year)
        }
    }

    private fun parseAbsoluteDate(value: String, defaultYear: Int): LocalDate? {
        val normalized = value.replace('/', '.').replace('-', '.')
        val parts = normalized.split('.').filter { it.isNotBlank() }
        if (parts.size == 2 || parts.size == 3) {
            val day = parts[0].toIntOrNull() ?: return null
            val month = parts[1].toIntOrNull() ?: return null
            val year = if (parts.size == 3) parts[2].toIntOrNull() ?: return null else defaultYear
            return try { LocalDate.of(year, month, day) } catch (_: DateTimeException) { null }
        }
        return try { LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE) } catch (_: DateTimeParseException) { null }
    }

    private fun parseTime(text: String?): LocalTime? {
        val value = text?.trim()?.lowercase()?.replace(" ", "") ?: return null
        val colon = Regex("^(?:[01]?\\d|2[0-3]):[0-5]\\d$").matchEntire(value)
        if (colon != null) return runCatching { LocalTime.parse(value.padStart(5, '0')) }.getOrNull()
        Regex("^(\\d{1,2})(am|pm)$").matchEntire(value)?.let { match ->
            var hour = match.groupValues[1].toIntOrNull() ?: return null
            if (hour !in 1..12) return null
            val pm = match.groupValues[2] == "pm"
            hour = when {
                pm && hour != 12 -> hour + 12
                !pm && hour == 12 -> 0
                else -> hour
            }
            return LocalTime.of(hour, 0)
        }
        Regex("^(\\d{1,2})uhr$").matchEntire(value)?.let { match ->
            val hour = match.groupValues[1].toIntOrNull() ?: return null
            if (hour !in 0..23) return null
            return LocalTime.of(hour, 0)
        }
        return null
    }

    /** Compact camera-like spectral-to-RGB light projection for the three light multipliers. */
    private fun spectrumToLinearRgb(curve: SpectralCurve): RgbSample {
        fun channel(centerNm: Double, sigmaNm: Double): Double {
            var weighted = 0.0
            var weightSum = 0.0
            for (i in 0 until curve.grid.bandCount) {
                val wavelength = curve.grid.wavelengthNm(i).toDouble()
                val weight = exp(-0.5 * Math.pow((wavelength - centerNm) / sigmaNm, 2.0))
                weighted += curve[i] * weight
                weightSum += weight
            }
            return (weighted / weightSum.coerceAtLeast(1e-12)).coerceIn(0.0, 1.0)
        }
        return RgbSample(
            r = channel(610.0, 55.0),
            g = channel(545.0, 45.0),
            b = channel(460.0, 42.0),
        )
    }

    private fun astronomicalPhase(elevationDeg: Double): AstronomicalLightPhase = when {
        elevationDeg >= 0.0 -> AstronomicalLightPhase.DAY
        elevationDeg >= -6.0 -> AstronomicalLightPhase.CIVIL_TWILIGHT
        elevationDeg >= -12.0 -> AstronomicalLightPhase.NAUTICAL_TWILIGHT
        elevationDeg >= -18.0 -> AstronomicalLightPhase.ASTRONOMICAL_TWILIGHT
        else -> AstronomicalLightPhase.NIGHT
    }

    private fun skyRadianceScale(phase: AstronomicalLightPhase, elevationDeg: Double): Double = when (phase) {
        AstronomicalLightPhase.DAY -> 0.45
        AstronomicalLightPhase.CIVIL_TWILIGHT -> 0.10 + 0.35 * ((elevationDeg + 6.0) / 6.0).coerceIn(0.0, 1.0)
        AstronomicalLightPhase.NAUTICAL_TWILIGHT -> 0.035 + 0.065 * ((elevationDeg + 12.0) / 6.0).coerceIn(0.0, 1.0)
        AstronomicalLightPhase.ASTRONOMICAL_TWILIGHT -> 0.012 + 0.023 * ((elevationDeg + 18.0) / 6.0).coerceIn(0.0, 1.0)
        AstronomicalLightPhase.NIGHT -> 0.008
    }

    private fun nightSkyTint(phase: AstronomicalLightPhase): RgbSample = when (phase) {
        AstronomicalLightPhase.DAY -> RgbSample(1.0, 1.0, 1.0)
        AstronomicalLightPhase.CIVIL_TWILIGHT -> RgbSample(1.0, 0.86, 0.78)
        AstronomicalLightPhase.NAUTICAL_TWILIGHT -> RgbSample(0.70, 0.78, 1.0)
        AstronomicalLightPhase.ASTRONOMICAL_TWILIGHT -> RgbSample(0.46, 0.58, 1.0)
        AstronomicalLightPhase.NIGHT -> RgbSample(0.32, 0.45, 1.0)
    }
}
