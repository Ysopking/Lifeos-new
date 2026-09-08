package app.lifeos.core.model.health

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RuntimeProtectionStateCodecTest {
    private val enteredAt = Instant.parse("2026-09-08T04:00:00Z")

    @Test
    fun protectedStateRoundTripsExactly() {
        val state = RuntimeProtectionState(
            generation = 4,
            revision = 9,
            mode = ProtectionMode.QUARANTINED,
            reasons = listOf(
                ProtectionReason(
                    code = ProtectionReasonCode.RECOVERY_EXHAUSTED,
                    source = "worker:test",
                    message = "repair attempts exhausted",
                ),
            ),
            affectedNodes = setOf(ProtectionNodeRef("worker:test")),
            enteredAt = enteredAt,
            lastVerifiedAt = enteredAt.plusSeconds(5),
            actor = ProtectionActor.RECOVERY,
            provenance = "recovery:plan-7",
            resumePolicy = ProtectionResumePolicy.USER_AFTER_VERIFICATION,
        )

        assertEquals(state, RuntimeProtectionStateCodec.decode(RuntimeProtectionStateCodec.encode(state)))
    }

    @Test
    fun normalStateCannotRetainProtectionReasons() {
        assertFailsWith<IllegalArgumentException> {
            RuntimeProtectionState(
                generation = 1,
                revision = 1,
                mode = ProtectionMode.NORMAL,
                reasons = listOf(
                    ProtectionReason(
                        ProtectionReasonCode.MANUAL,
                        "test",
                        "must not remain",
                    ),
                ),
            )
        }
    }

    @Test
    fun decoderRejectsTrailingData() {
        val encoded = RuntimeProtectionStateCodec.encode(RuntimeProtectionState.normal())
        assertFailsWith<IllegalArgumentException> {
            RuntimeProtectionStateCodec.decode(encoded + byteArrayOf(1))
        }
    }
}
