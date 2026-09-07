package app.lifeos.core.runtime.cognition

import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CognitiveRuntimeFoundationTest {
    private val budget = CognitiveWorkBudget(
        maxDurationMs = 1_000,
        maxModuleInvocations = 2,
        maxNewPhotons = 4,
        maxNetworkCalls = 0,
    )

    @Test
    fun journalAppendIsIdempotentByEventId() = runTest {
        val journal = InMemoryCognitiveEventJournal()
        val event = CognitiveEvent(
            eventId = "event-1",
            delta = PhotonDelta(
                deltaId = "delta-1",
                source = "test",
                type = PhotonDeltaType.CREATED,
            ),
        )

        val first = journal.append(event)
        val second = journal.append(event)

        assertEquals(first, second)
        assertEquals(1, journal.size())
        assertEquals(listOf("event-1"), journal.readFrom(0).map { it.event.eventId })
    }

    @Test
    fun schedulerEvictsLowerPriorityWorkUnderBackpressure() = runTest {
        val scheduler = CognitiveScheduler(maxQueued = 2)
        val t0 = Instant.parse("2026-09-07T18:00:00Z")
        val background = work("background", CognitivePriority.BACKGROUND, 0.2, t0)
        val normal = work("normal", CognitivePriority.NORMAL, 0.5, t0.plusSeconds(1))
        val critical = work("critical", CognitivePriority.CRITICAL, 1.0, t0.plusSeconds(2))

        assertTrue(scheduler.offer(background).accepted)
        assertTrue(scheduler.offer(normal).accepted)
        val offer = scheduler.offer(critical)

        assertTrue(offer.accepted)
        assertEquals("background", offer.evictedWorkId)
        assertEquals("critical", scheduler.poll()?.id)
        assertEquals("normal", scheduler.poll()?.id)
    }

    @Test
    fun schedulerRejectsWorseWorkWhenFull() = runTest {
        val scheduler = CognitiveScheduler(maxQueued = 1)
        val t0 = Instant.parse("2026-09-07T18:00:00Z")
        scheduler.offer(work("high", CognitivePriority.HIGH, 0.8, t0))

        val rejected = scheduler.offer(work("idle", CognitivePriority.IDLE, 0.1, t0.plusSeconds(1)))

        assertFalse(rejected.accepted)
        assertEquals("high", scheduler.poll()?.id)
    }

    private fun work(
        id: String,
        priority: CognitivePriority,
        salience: Double,
        at: Instant,
    ) = CognitiveWorkItem(
        id = id,
        triggeringDeltaId = "delta-$id",
        priority = priority,
        salience = salience,
        enqueuedAt = at,
        budget = budget,
    )
}
