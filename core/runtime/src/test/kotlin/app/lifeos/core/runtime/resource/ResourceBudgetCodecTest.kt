package app.lifeos.core.runtime.resource

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ResourceBudgetCodecTest {
    @Test
    fun roundTripPreservesConsumptionAndTerminalReservations() {
        val accountId = ResourceBudgetAccountId("goal:plan-42")
        val reservation = ResourceBudgetReservation.create(
            accountId = accountId,
            idempotencyKey = "step:1",
            usage = ResourceBudgetUsage(workUnits = 5, memoryBytes = 1024),
            createdAt = Instant.parse("2026-09-11T10:00:00Z"),
        ).copy(
            state = ResourceBudgetReservationState.COMMITTED,
            settledUsage = ResourceBudgetUsage(workUnits = 3, memoryBytes = 512),
            settledAt = Instant.parse("2026-09-11T10:00:02Z"),
        )
        val account = ResourceBudgetAccount(
            id = accountId,
            revision = 2,
            quota = ResourceBudgetQuota(
                elapsedMillis = 10_000,
                workUnits = 20,
                memoryBytes = 8_192,
                ioBytes = 4_096,
                networkBytes = 2_048,
                candidates = 4,
            ),
            consumed = ResourceBudgetUsage(workUnits = 3, memoryBytes = 512),
            reservations = listOf(reservation),
        )
        assertEquals(account, ResourceBudgetAccountCodec.decode(ResourceBudgetAccountCodec.encode(account)))
    }

    @Test
    fun malformedPayloadIsRejected() {
        val account = ResourceBudgetAccount(
            id = ResourceBudgetAccountId("a"),
            revision = 1,
            quota = ResourceBudgetQuota(1, 1, 1, 1, 1, 1),
        )
        val payload = ResourceBudgetAccountCodec.encode(account)
        val truncated = payload.copyOf(payload.size - 1)
        assertFailsWith<Exception> { ResourceBudgetAccountCodec.decode(truncated) }
    }
}
