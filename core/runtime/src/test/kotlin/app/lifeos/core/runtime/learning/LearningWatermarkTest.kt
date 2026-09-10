package app.lifeos.core.runtime.learning

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class LearningWatermarkTest {
    @Test
    fun codecRoundTripsCanonicalMultiSourceState() {
        val state = LearningWatermarkState(
            revision = 2,
            sources = listOf(
                LearningSourceWatermark(
                    LearningSourceId("a-source"),
                    7,
                    "event-a",
                    "a".repeat(64),
                ),
                LearningSourceWatermark(
                    LearningSourceId("b-source"),
                    4,
                    "event-b",
                    "b".repeat(64),
                ),
            ),
        )

        val encoded = LearningWatermarkCodec.encode(state)
        val decoded = LearningWatermarkCodec.decode(encoded)

        assertEquals(state, decoded)
        assertEquals(encoded, LearningWatermarkCodec.encode(decoded))
    }

    @Test
    fun sourceAdvanceIncrementsGlobalRevisionExactlyOnce() {
        val first = LearningWatermarkState.empty().advance(
            LearningSourceId("events"),
            1,
            "event-1",
            "1".repeat(64),
        )
        val second = first.advance(
            LearningSourceId("events"),
            2,
            "event-2",
            "2".repeat(64),
        )

        assertEquals(1, first.revision)
        assertEquals(2, second.revision)
        assertEquals(2, second.forSource(LearningSourceId("events"))?.sequence)
    }

    @Test
    fun sourceSequenceCannotMoveBackwardsOrRepeat() {
        val state = LearningWatermarkState.empty().advance(
            LearningSourceId("events"),
            3,
            "event-3",
            "3".repeat(64),
        )

        assertFailsWith<IllegalArgumentException> {
            state.advance(LearningSourceId("events"), 3, "event-3", "3".repeat(64))
        }
        assertFailsWith<IllegalArgumentException> {
            state.advance(LearningSourceId("events"), 2, "event-2", "2".repeat(64))
        }
    }

    @Test
    fun eventFingerprintBindsIdentityAndPayload() {
        val event = LearningEvent(
            sourceId = LearningSourceId("source"),
            sequence = 1,
            eventId = "event-1",
            kind = LearningEventKind.CONTEXT_CHANGE,
            provenance = LearningProvenance.OBSERVATION,
            occurredAt = java.time.Instant.parse("2026-09-10T08:00:00Z"),
            attributes = mapOf("context" to "project:lifeos"),
        )

        assertEquals(event.fingerprint(), event.copy(attributes = mapOf("context" to "project:lifeos")).fingerprint())
        assertNotEquals(event.fingerprint(), event.copy(attributes = mapOf("context" to "project:other")).fingerprint())
        assertNotEquals(event.fingerprint(), event.copy(provenance = LearningProvenance.USER_CONFIRMED).fingerprint())
    }
}