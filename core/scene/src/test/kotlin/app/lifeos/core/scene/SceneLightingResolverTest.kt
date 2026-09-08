package app.lifeos.core.scene

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SceneLightingResolverTest {
    private val resolver = SceneLightingResolver()

    @Test
    fun `Berlin civil time resolves to astronomical sun and persists coordinates`() {
        val result = resolver.resolve(
            environment = SceneEnvironment(
                locationText = "Berlin",
                dateText = "08.09.2026",
                timeText = "12:00",
                lightingMode = LightingMode.ASTRONOMICAL_IF_RESOLVED,
                groundMaterial = "grass-diffuse",
            ),
            referenceInstant = Instant.parse("2026-09-08T00:00:00Z"),
        )

        assertTrue(result.astronomical)
        assertEquals(52.520008, result.environment.resolvedLatitudeDeg)
        assertEquals(13.404954, result.environment.resolvedLongitudeDeg)
        assertEquals("Europe/Berlin", result.environment.resolvedZoneId)
        assertEquals("2026-09-08T10:00:00Z", result.environment.resolvedInstantUtc)
        assertNotNull(result.environment.sunAzimuthDeg)
        assertTrue(result.environment.sunElevationDeg!! > 35.0)
        assertTrue(result.profile.sunColorLinear.r > 0.0)
    }

    @Test
    fun `morning and evening in Berlin produce opposite east west sun components`() {
        fun at(time: String) = resolver.resolve(
            SceneEnvironment(
                locationText = "Berlin",
                dateText = "08.09.2026",
                timeText = time,
                lightingMode = LightingMode.ASTRONOMICAL_IF_RESOLVED,
                groundMaterial = "grass-diffuse",
            ),
            Instant.parse("2026-09-08T00:00:00Z"),
        )

        val morning = at("08:00")
        val evening = at("18:00")
        assertTrue(morning.astronomical && evening.astronomical)
        assertTrue(morning.profile.sunDirection.x > 0.0, "morning sun should be east of the scene")
        assertTrue(evening.profile.sunDirection.x < 0.0, "evening sun should be west of the scene")
        assertTrue(kotlin.math.abs(morning.profile.sunDirection.x - evening.profile.sunDirection.x) > 0.5)
    }

    @Test
    fun `relative today is anchored to utterance instant in location zone`() {
        val result = resolver.resolve(
            SceneEnvironment(
                locationText = "Tokyo",
                dateText = "relative:today",
                timeText = "09:00",
                lightingMode = LightingMode.ASTRONOMICAL_IF_RESOLVED,
                groundMaterial = "neutral-ground",
            ),
            // Already September 9 in Tokyo.
            Instant.parse("2026-09-08T16:30:00Z"),
        )

        assertTrue(result.astronomical)
        assertEquals("2026-09-09T00:00:00Z", result.environment.resolvedInstantUtc)
        assertEquals("Asia/Tokyo", result.environment.resolvedZoneId)
    }

    @Test
    fun `Brandenburg Gate resolves more precisely than Berlin city anchor`() {
        val location = OfflineSceneLocationResolver().resolve("Brandenburger Tor")
        assertNotNull(location)
        assertEquals(52.516275, location.latitudeDeg)
        assertEquals(13.377704, location.longitudeDeg)
        assertEquals("Brandenburger Tor", location.canonicalName)
    }

    @Test
    fun `numeric coordinates stay offline and use explicit UTC civil time`() {
        val location = OfflineSceneLocationResolver().resolve("52.516275; 13.377704")
        assertNotNull(location)
        assertEquals(52.516275, location.latitudeDeg)
        assertEquals(13.377704, location.longitudeDeg)
        assertEquals("UTC", location.zoneId)
    }

    @Test
    fun `unresolved location falls back without inventing a sun position`() {
        val result = resolver.resolve(
            SceneEnvironment(
                locationText = "Unbekannter Fantasieort",
                dateText = "08.09.2026",
                timeText = "12:00",
                lightingMode = LightingMode.ASTRONOMICAL_IF_RESOLVED,
                groundMaterial = "neutral-ground",
            ),
            Instant.parse("2026-09-08T00:00:00Z"),
        )

        assertFalse(result.astronomical)
        assertEquals(LightingMode.SYNTHETIC_NEUTRAL, result.environment.lightingMode)
        assertTrue(result.warnings.any { it.contains("location") })
        assertEquals(null, result.environment.sunElevationDeg)
    }

    @Test
    fun `night scene suppresses direct solar color`() {
        val result = resolver.resolve(
            SceneEnvironment(
                locationText = "Berlin",
                dateText = "08.09.2026",
                timeText = "23:00",
                lightingMode = LightingMode.ASTRONOMICAL_IF_RESOLVED,
                groundMaterial = "neutral-ground",
            ),
            Instant.parse("2026-09-08T00:00:00Z"),
        )

        assertTrue(result.astronomical)
        assertTrue(result.environment.sunElevationDeg!! < -6.0)
        assertEquals(0.0, result.profile.sunColorLinear.r)
        assertEquals(0.0, result.profile.sunColorLinear.g)
        assertEquals(0.0, result.profile.sunColorLinear.b)
    }
}
