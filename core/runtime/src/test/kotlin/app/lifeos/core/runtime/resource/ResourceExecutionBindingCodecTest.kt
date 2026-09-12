package app.lifeos.core.runtime.resource

import app.lifeos.core.runtime.trace.DecisionTraceId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ResourceExecutionBindingCodecTest {
    @Test
    fun roundTripIsDeterministic() {
        val binding = sample()
        val first = ResourceExecutionBindingCodec.encode(binding)
        val second = ResourceExecutionBindingCodec.encode(binding)

        assertContentEquals(first, second)
        assertEquals(binding, ResourceExecutionBindingCodec.decode(first))
    }

    @Test
    fun rejectsTrailingBytes() {
        val bytes = ResourceExecutionBindingCodec.encode(sample()) + byteArrayOf(0x01)
        assertFailsWith<IllegalArgumentException> {
            ResourceExecutionBindingCodec.decode(bytes)
        }
    }

    @Test
    fun rejectsUnsupportedVersion() {
        val bytes = ResourceExecutionBindingCodec.encode(sample()).copyOf()
        bytes[7] = 0x02.toByte()
        assertFailsWith<IllegalArgumentException> {
            ResourceExecutionBindingCodec.decode(bytes)
        }
    }

    private fun sample() = ResourceExecutionBinding(
        traceId = DecisionTraceId.create("goal-photon", "g1"),
        domain = ResourceBudgetDomain.GOAL_EXECUTION,
        operationId = "goal-action:1",
        accountId = ResourceBudgetAccountId("goal:g1"),
        reservationId = ResourceBudgetReservation.create(
            ResourceBudgetAccountId("goal:g1"),
            "goal-action:1",
            ResourceBudgetUsage(workUnits = 1),
            Instant.parse("2026-09-12T00:00:00Z"),
        ).id,
        authoritativeStateId = "outbox:1",
        revision = 1,
        boundAt = Instant.parse("2026-09-12T00:00:00Z"),
    )
}
