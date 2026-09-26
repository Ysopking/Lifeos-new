package app.lifeos.next.ui.calendar

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import java.time.Instant

data class CalendarEventUiModel(
    val photonId: PhotonId,
    val title: String,
    val description: String,
    val location: String,
    val start: Instant,
    val end: Instant?,
    val allDay: Boolean,
)

object WeekCalendarProjector {
    fun events(photons: List<Photon>): List<CalendarEventUiModel> = photons
        .asSequence()
        .filter { "calendar-event" in it.tags }
        .mapNotNull(::event)
        .sortedWith(
            compareBy<CalendarEventUiModel> { it.start }
                .thenBy { it.title }
                .thenBy { it.photonId.value }
        )
        .toList()

    private fun event(photon: Photon): CalendarEventUiModel? {
        val fields = photon.content.lineSequence()
            .mapNotNull { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) null
                else line.substring(0, separator) to line.substring(separator + 1)
            }
            .toMap()
        val startMs = fields["start_ms"]?.toLongOrNull()
            ?.takeIf { it > 0L }
            ?: return null
        val endMs = fields["end_ms"]?.toLongOrNull()
            ?.takeIf { it >= startMs }

        return CalendarEventUiModel(
            photonId = photon.id,
            title = fields["title"].orEmpty().trim().ifBlank { "Termin" }.take(MAX_TEXT),
            description = fields["description"].orEmpty().trim().take(MAX_TEXT),
            location = fields["location"].orEmpty().trim().take(MAX_TEXT),
            start = Instant.ofEpochMilli(startMs),
            end = endMs?.let(Instant::ofEpochMilli),
            allDay = fields["all_day"] == "1",
        )
    }

    private const val MAX_TEXT = 500
}
