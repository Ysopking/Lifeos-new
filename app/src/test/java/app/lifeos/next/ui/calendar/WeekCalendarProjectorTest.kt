package app.lifeos.next.ui.calendar

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.Provenance
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class WeekCalendarProjectorTest {
    @Test
    fun projectsOnlyCanonicalCalendarEvidence() {
        val event = Photon(
            id = PhotonId("calendar-a"),
            content = """
                calendar_id=1
                title=Arzttermin
                description=Kontrolle
                location=Freiburg
                start_ms=1790413200000
                end_ms=1790416800000
                all_day=0
                status=1
                timezone=Europe/Berlin
            """.trimIndent(),
            provenance = Provenance(
                source = "life-source:android-calendar",
                actor = "android-calendar/v1",
                createdAt = Instant.parse("2026-09-26T09:00:00Z"),
            ),
            tags = setOf("calendar-event"),
        )
        val unrelated = Photon(
            id = PhotonId("other"),
            content = "title=ignore",
            provenance = Provenance("test", "test"),
        )

        val result = WeekCalendarProjector.events(listOf(unrelated, event))

        assertEquals(1, result.size)
        assertEquals("Arzttermin", result.single().title)
        assertEquals("Freiburg", result.single().location)
        assertEquals(false, result.single().allDay)
    }
}
