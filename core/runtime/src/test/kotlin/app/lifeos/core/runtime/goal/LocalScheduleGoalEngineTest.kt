package app.lifeos.core.runtime.goal

import app.lifeos.core.language.EntityType
import app.lifeos.core.language.GoalFrame
import app.lifeos.core.language.IntentType
import app.lifeos.core.language.LanguageCode
import app.lifeos.core.language.SemanticEntity
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.Provenance
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LocalScheduleGoalEngineTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val sourceAt = Instant.parse("2026-09-10T10:00:00Z") // 12:00 local

    @Test
    fun `tomorrow and explicit time create persistent decodable reminder photon`() {
        val result = LocalScheduleGoalEngine().execute(
            goal = goal(date("relative:tomorrow", "morgen"), time("16:30", "16:30")),
            sourcePhoton = source(),
            goalPhotonId = PhotonId("goal-1"),
            zoneId = zone,
            createdAt = sourceAt,
        )

        val scheduled = assertIs<LocalScheduleGoalResult.Scheduled>(result)
        assertEquals(Instant.parse("2026-09-11T14:30:00Z"), scheduled.record.triggerAt)
        assertEquals(LocalScheduleGoalEngine.REMINDER_MIME, scheduled.photon.mimeType)
        assertTrue("reminder" in scheduled.photon.tags)
        assertEquals(source().content, scheduled.record.message)
        assertEquals(scheduled.record, LocalReminderRecord.decode(scheduled.photon))
        assertEquals(setOf(source().id, PhotonId("goal-1")), scheduled.photon.provenance.parentIds)
    }

    @Test
    fun `time without date uses today when still future`() {
        val result = LocalScheduleGoalEngine().execute(
            goal = goal(time("18:00", "18:00")),
            sourcePhoton = source(),
            goalPhotonId = PhotonId("goal-1"),
            zoneId = zone,
            createdAt = sourceAt,
        )

        val scheduled = assertIs<LocalScheduleGoalResult.Scheduled>(result)
        assertEquals(Instant.parse("2026-09-10T16:00:00Z"), scheduled.record.triggerAt)
    }

    @Test
    fun `time without date rolls to tomorrow when local time already passed`() {
        val result = LocalScheduleGoalEngine().execute(
            goal = goal(time("09:00", "09:00")),
            sourcePhoton = source(),
            goalPhotonId = PhotonId("goal-1"),
            zoneId = zone,
            createdAt = sourceAt,
        )

        val scheduled = assertIs<LocalScheduleGoalResult.Scheduled>(result)
        assertEquals(Instant.parse("2026-09-11T07:00:00Z"), scheduled.record.triggerAt)
    }

    @Test
    fun `date without time is blocked instead of inventing a clock time`() {
        val result = LocalScheduleGoalEngine().execute(
            goal = goal(date("relative:tomorrow", "morgen")),
            sourcePhoton = source(),
            goalPhotonId = PhotonId("goal-1"),
            zoneId = zone,
        )

        val blocked = assertIs<LocalScheduleGoalResult.Blocked>(result)
        assertEquals("reminder-time-missing", blocked.reason)
    }

    @Test
    fun `explicit past date is rejected`() {
        val result = LocalScheduleGoalEngine().execute(
            goal = goal(date("01.09.2026", "01.09.2026"), time("10:00", "10:00")),
            sourcePhoton = source(),
            goalPhotonId = PhotonId("goal-1"),
            zoneId = zone,
        )

        val blocked = assertIs<LocalScheduleGoalResult.Blocked>(result)
        assertEquals("reminder-time-not-in-future", blocked.reason)
    }

    @Test
    fun `12 am and pm are parsed correctly`() {
        val midnight = LocalScheduleGoalEngine().execute(
            goal = goal(date("relative:tomorrow", "tomorrow"), time("12am", "12 am")),
            sourcePhoton = source(),
            goalPhotonId = PhotonId("goal-a"),
            zoneId = zone,
        )
        val noon = LocalScheduleGoalEngine().execute(
            goal = goal(date("relative:tomorrow", "tomorrow"), time("12pm", "12 pm")),
            sourcePhoton = source(),
            goalPhotonId = PhotonId("goal-b"),
            zoneId = zone,
        )

        assertEquals(Instant.parse("2026-09-10T22:00:00Z"), assertIs<LocalScheduleGoalResult.Scheduled>(midnight).record.triggerAt)
        assertEquals(Instant.parse("2026-09-11T10:00:00Z"), assertIs<LocalScheduleGoalResult.Scheduled>(noon).record.triggerAt)
    }

    private fun source() = Photon(
        id = PhotonId("source-1"),
        content = "Erinnere mich morgen um 16:30 an den Termin",
        confidence = 0.93,
        provenance = Provenance("test", "user", sourceAt),
        tags = setOf("chat"),
    )

    private fun goal(vararg entities: SemanticEntity) = GoalFrame(
        intent = IntentType.SCHEDULE,
        objective = "schedule reminder",
        entities = entities.toList(),
        references = emptyList(),
        constraints = emptyList(),
        ambiguities = emptyList(),
        confidence = 0.91,
        language = LanguageCode.DE,
    )

    private fun date(value: String, raw: String) = SemanticEntity(
        EntityType.DATE, raw, value, 0, 1, 0.99,
    )

    private fun time(value: String, raw: String) = SemanticEntity(
        EntityType.TIME, raw, value, 1, 2, 0.99,
    )
}
