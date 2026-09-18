package app.lifeos.core.runtime.escalation

import app.lifeos.core.runtime.health.HealthNodeId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EscalationRecordLogCodecTest {
    @Test
    fun segmentRoundTripIsExactAndBounded() {
        val record = EscalationRecord(
            revision = 7L,
            escalationId = EscalationId("escalation:" + "a".repeat(64)),
            nodeId = HealthNodeId("worker-7"),
            triggerFingerprint = "trigger-fingerprint",
            type = EscalationRecordType.ACTION_FAILED,
            recordedAt = Instant.parse("2026-09-19T00:30:00Z"),
            level = EscalationLevel.L2_RECOVER_COMPONENT,
            detail = "probe-failed",
            evidenceRefs = setOf("b", "a"),
        )

        assertEquals(record, EscalationRecordLogCodec.decodeSegment(EscalationRecordLogCodec.encodeSegment(record)))
    }

    @Test
    fun trailingBytesAreRejected() {
        val record = EscalationRecord(
            revision = 1L,
            escalationId = EscalationId("escalation:" + "b".repeat(64)),
            nodeId = HealthNodeId("runtime"),
            triggerFingerprint = "trigger",
            type = EscalationRecordType.OPENED,
            recordedAt = Instant.parse("2026-09-19T00:31:00Z"),
        )
        val bytes = EscalationRecordLogCodec.encodeSegment(record) + byteArrayOf(1)
        assertFailsWith<IllegalArgumentException> {
            EscalationRecordLogCodec.decodeSegment(bytes)
        }
    }
}
