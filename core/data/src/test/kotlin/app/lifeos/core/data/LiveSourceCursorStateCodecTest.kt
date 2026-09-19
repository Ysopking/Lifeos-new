package app.lifeos.core.data

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LiveSourceCursorStateCodecTest {
    @Test
    fun cursorStateRoundTripsExactly() {
        val state = LiveSourceCursorState(
            revision = 7L,
            sourceId = LiveSourceId("calendar-primary"),
            connectorIdentityFingerprint = "a".repeat(64),
            cursor = SourceCursor("opaque-provider-cursor"),
            bootstrapped = true,
            baselineFingerprint = "b".repeat(64),
            lastObservationRevision = 42L,
            updatedAt = Instant.parse("2026-09-19T03:30:00Z"),
        )

        assertEquals(
            state,
            LiveSourceCursorStateCodec.decode(LiveSourceCursorStateCodec.encode(state)),
        )
    }

    @Test
    fun trailingBytesAreRejected() {
        val state = LiveSourceCursorState.initial(
            sourceId = LiveSourceId("files-primary"),
            connectorIdentityFingerprint = "c".repeat(64),
            at = Instant.parse("2026-09-19T03:31:00Z"),
        )
        val bytes = LiveSourceCursorStateCodec.encode(state) + byteArrayOf(1)

        assertFailsWith<IllegalArgumentException> {
            LiveSourceCursorStateCodec.decode(bytes)
        }
    }
}
