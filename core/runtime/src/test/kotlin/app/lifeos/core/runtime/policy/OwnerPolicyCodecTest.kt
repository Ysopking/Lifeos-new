package app.lifeos.core.runtime.policy

import app.lifeos.core.runtime.capability.CapabilityId
import app.lifeos.core.runtime.resource.ResourceBudgetAccountId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OwnerPolicyCodecTest {
    @Test
    fun roundTripPreservesGrantAndRevokeHistory() {
        val grant = OwnerPolicyGrant.create(
            actorId = OwnerActorId("owner"),
            effect = OwnerEffectType.NETWORK_ACCESS,
            resource = OwnerResourceSelector(OwnerResourceSelectorType.PREFIX, "https://example.test/"),
            scope = "goal-execution",
            capability = OwnerCapabilityConstraint(CapabilityId("search.web"), "v1"),
            budgetAccountId = ResourceBudgetAccountId("goal:1"),
            validFrom = Instant.parse("2026-09-11T10:00:00Z"),
            validUntil = Instant.parse("2026-09-12T10:00:00Z"),
        )
        val events = listOf(
            OwnerPolicyEvent(1, OwnerPolicyEventType.GRANT, Instant.parse("2026-09-11T10:00:01Z"), grant = grant),
            OwnerPolicyEvent(2, OwnerPolicyEventType.REVOKE, Instant.parse("2026-09-11T10:05:00Z"), revokedGrantId = grant.id),
        )
        assertEquals(events, OwnerPolicyEventLogCodec.decode(OwnerPolicyEventLogCodec.encode(events)))
    }

    @Test
    fun segmentedCodecPreservesGlobalRevisionWithoutWeakeningFullLogValidation() {
        val grant = OwnerPolicyGrant.create(
            actorId = OwnerActorId("owner"),
            effect = OwnerEffectType.FILE_WRITE,
            resource = OwnerResourceSelector(OwnerResourceSelectorType.EXACT, "file://segment"),
            scope = "private",
            validFrom = Instant.EPOCH,
        )
        val event = OwnerPolicyEvent(
            revision = 7,
            type = OwnerPolicyEventType.GRANT,
            recordedAt = Instant.EPOCH,
            grant = grant,
        )
        val encoded = OwnerPolicyEventLogCodec.encodeSegment(event)

        assertEquals(event, OwnerPolicyEventLogCodec.decodeSegment(encoded))
        assertFailsWith<IllegalArgumentException> { OwnerPolicyEventLogCodec.decode(encoded) }
        assertFailsWith<IllegalArgumentException> {
            OwnerPolicyEventLogCodec.encode(listOf(event))
        }
    }

    @Test
    fun corruptionAndNonContiguousHistoryAreRejected() {
        val empty = OwnerPolicyEventLogCodec.encode(emptyList())
        val corrupted = empty.copyOf().also {
            it[it.lastIndex] = (it[it.lastIndex].toInt() xor 0x01).toByte()
        }
        assertFailsWith<Exception> { OwnerPolicyEventLogCodec.decode(corrupted) }

        val grant = OwnerPolicyGrant.create(
            actorId = OwnerActorId("owner"),
            effect = OwnerEffectType.FILE_WRITE,
            resource = OwnerResourceSelector(OwnerResourceSelectorType.ANY),
            scope = "private",
            validFrom = Instant.EPOCH,
        )
        assertFailsWith<IllegalArgumentException> {
            OwnerPolicyEventLogCodec.encode(
                listOf(OwnerPolicyEvent(2, OwnerPolicyEventType.GRANT, Instant.EPOCH, grant = grant))
            )
        }
    }
}
