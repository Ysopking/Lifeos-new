package app.lifeos.core.language

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoordinateLocationExtractionTest {
    private val engine = LanguageUnderstandingEngine()

    @Test
    fun `decimal coordinate pair becomes a location entity`() {
        val result = engine.understand(
            "Erzeuge ein Bild von zwei Leuten die Fussball spielen bei 52.516275; 13.377704 am 08.09.2026 um 17:30",
        )
        val locations = result.goal.entities.filter { it.type == EntityType.LOCATION }
        assertTrue(locations.isNotEmpty())
        assertEquals("52.516275; 13.377704", locations.first().normalizedValue)
    }

    @Test
    fun `labelled latitude longitude becomes a location entity`() {
        val result = engine.understand(
            "Erzeuge ein Bild von einer Person bei lat=52.516275 lon=13.377704 am 08.09.2026 um 17:30",
        )
        val location = result.goal.entities.first { it.type == EntityType.LOCATION }
        assertTrue(location.normalizedValue.contains("lat=52.516275"))
        assertTrue(location.normalizedValue.contains("lon=13.377704"))
    }
}
