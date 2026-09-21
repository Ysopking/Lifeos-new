package app.lifeos.core.runtime.policy

import app.lifeos.core.runtime.capability.CapabilityId
import app.lifeos.core.runtime.resource.ResourceBudgetAccountId
import app.lifeos.core.runtime.resource.ResourceBudgetReservation
import app.lifeos.core.runtime.resource.ResourceBudgetUsage
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class OwnerPolicyLedgerTest {
    @Test
    fun resumedEffectRechecksCurrentPolicyAndSeesRevocation() = runTest {
        val repository = TestOwnerPolicyRepository()
        val now = Instant.parse("2026-09-11T12:00:00Z")
        val ledger = OwnerPolicyLedger(repository) { now }
        val grant = OwnerPolicyGrant.create(
            actorId = OwnerActorId("goal-runtime"),
            effect = OwnerEffectType.NETWORK_ACCESS,
            resource = OwnerResourceSelector(OwnerResourceSelectorType.PREFIX, "https://api.example/"),
            scope = "goal:plan-1",
            capability = OwnerCapabilityConstraint(
                capabilityId = CapabilityId("external-search"),
                providerVersion = "v2",
            ),
            validFrom = now.minusSeconds(60),
            validUntil = now.plusSeconds(3600),
        )
        ledger.grant(grant)
        val request = OwnerEffectRequest(
            actorId = OwnerActorId("goal-runtime"),
            effect = OwnerEffectType.NETWORK_ACCESS,
            resource = "https://api.example/search",
            scope = "goal:plan-1",
            capabilityId = CapabilityId("external-search"),
            providerVersion = "v2",
        )

        val before = assertIs<OwnerPolicyDecision.Allowed>(ledger.evaluate(request))
        assertEquals(1L, before.policyRevision)

        ledger.revoke(grant.id)

        val resumedLedger = OwnerPolicyLedger(repository) { now.plusSeconds(1) }
        val after = assertIs<OwnerPolicyDecision.Blocked>(resumedLedger.evaluate(request))
        assertEquals(2L, after.policyRevision)
    }

    @Test
    fun budgetBoundGrantRequiresMatchingReservation() = runTest {
        val repository = TestOwnerPolicyRepository()
        val now = Instant.parse("2026-09-11T12:00:00Z")
        val budgetAccountId = ResourceBudgetAccountId("goal:plan-2")
        val ledger = OwnerPolicyLedger(repository) { now }
        val grant = OwnerPolicyGrant.create(
            actorId = OwnerActorId("goal-runtime"),
            effect = OwnerEffectType.COMMUNICATION,
            resource = OwnerResourceSelector(OwnerResourceSelectorType.EXACT, "contact:alice"),
            scope = "goal:plan-2",
            budgetAccountId = budgetAccountId,
            validFrom = now.minusSeconds(10),
        )
        ledger.grant(grant)
        val missingReservation = OwnerEffectRequest(
            actorId = OwnerActorId("goal-runtime"),
            effect = OwnerEffectType.COMMUNICATION,
            resource = "contact:alice",
            scope = "goal:plan-2",
            budgetAccountId = budgetAccountId,
        )
        assertIs<OwnerPolicyDecision.Blocked>(ledger.evaluate(missingReservation))

        val reservation = ResourceBudgetReservation.create(
            accountId = budgetAccountId,
            idempotencyKey = "send:message-1",
            usage = ResourceBudgetUsage(workUnits = 1),
            createdAt = now,
        )
        val allowed = assertIs<OwnerPolicyDecision.Allowed>(
            ledger.evaluate(
                missingReservation.copy(budgetReservationId = reservation.id)
            )
        )
        assertEquals(reservation.id, allowed.budgetReservationId)
    }

    @Test
    fun providerVersionAndValidityArePartOfTheDecision() = runTest {
        val repository = TestOwnerPolicyRepository()
        val now = Instant.parse("2026-09-11T12:00:00Z")
        val ledger = OwnerPolicyLedger(repository) { now }
        ledger.grant(
            OwnerPolicyGrant.create(
                actorId = OwnerActorId("evolution-runtime"),
                effect = OwnerEffectType.PROVIDER_ACTIVATION,
                resource = OwnerResourceSelector(OwnerResourceSelectorType.EXACT, "provider:search"),
                scope = "evolution:trial-1",
                capability = OwnerCapabilityConstraint(CapabilityId("search"), "candidate-7"),
                validFrom = now.minusSeconds(30),
                validUntil = now.plusSeconds(30),
            )
        )

        val wrongVersion = ledger.evaluate(
            OwnerEffectRequest(
                actorId = OwnerActorId("evolution-runtime"),
                effect = OwnerEffectType.PROVIDER_ACTIVATION,
                resource = "provider:search",
                scope = "evolution:trial-1",
                capabilityId = CapabilityId("search"),
                providerVersion = "candidate-8",
            )
        )
        assertIs<OwnerPolicyDecision.Blocked>(wrongVersion)

        val expiredLedger = OwnerPolicyLedger(repository) { now.plusSeconds(31) }
        val expired = expiredLedger.evaluate(
            OwnerEffectRequest(
                actorId = OwnerActorId("evolution-runtime"),
                effect = OwnerEffectType.PROVIDER_ACTIVATION,
                resource = "provider:search",
                scope = "evolution:trial-1",
                capabilityId = CapabilityId("search"),
                providerVersion = "candidate-7",
            )
        )
        assertIs<OwnerPolicyDecision.Blocked>(expired)
    }

    @Test
    fun grantHistoryDistinguishesNeverSeenActiveAndRevoked() = runTest {
        val repository = TestOwnerPolicyRepository()
        val now = Instant.parse("2026-09-11T12:00:00Z")
        val ledger = OwnerPolicyLedger(repository) { now }
        val grant = OwnerPolicyGrant.create(
            actorId = OwnerActorId("private-owner"),
            effect = OwnerEffectType.FILE_WRITE,
            resource = OwnerResourceSelector(
                OwnerResourceSelectorType.EXACT,
                "file://local/default",
            ),
            scope = "private-default",
            validFrom = Instant.EPOCH,
        )

        assertEquals(
            OwnerGrantHistoryState.NEVER_SEEN,
            ledger.historyState(grant.id),
        )

        ledger.grant(grant)
        assertEquals(
            OwnerGrantHistoryState.ACTIVE,
            ledger.historyState(grant.id),
        )

        ledger.revoke(grant.id)
        assertEquals(
            OwnerGrantHistoryState.REVOKED,
            ledger.historyState(grant.id),
        )

        ledger.grant(grant)
        assertEquals(
            OwnerGrantHistoryState.ACTIVE,
            ledger.historyState(grant.id),
        )
    }

    @Test
    fun unreadableOrNonContiguousLedgerFailsClosed() = runTest {
        val now = Instant.parse("2026-09-11T12:00:00Z")
        val grant = OwnerPolicyGrant.create(
            actorId = OwnerActorId("runtime"),
            effect = OwnerEffectType.FILE_WRITE,
            resource = OwnerResourceSelector(OwnerResourceSelectorType.ANY),
            scope = "test",
            validFrom = now,
        )
        val corrupted = object : OwnerPolicyRepository {
            override suspend fun loadReport() = OwnerPolicyRepositoryLoadReport(
                events = listOf(
                    OwnerPolicyEvent(
                        revision = 2,
                        type = OwnerPolicyEventType.GRANT,
                        recordedAt = now,
                        grant = grant,
                    )
                )
            )

            override suspend fun append(expectedRevision: Long, event: OwnerPolicyEvent): Boolean = false
        }
        val ledger = OwnerPolicyLedger(corrupted) { now }

        assertFailsWith<IllegalArgumentException> { ledger.snapshot() }
    }

    private class TestOwnerPolicyRepository : OwnerPolicyRepository {
        private val events = mutableListOf<OwnerPolicyEvent>()

        override suspend fun loadReport(): OwnerPolicyRepositoryLoadReport =
            OwnerPolicyRepositoryLoadReport(events.toList())

        override suspend fun append(expectedRevision: Long, event: OwnerPolicyEvent): Boolean {
            val current = events.lastOrNull()?.revision ?: 0L
            if (current != expectedRevision) return false
            require(event.revision == expectedRevision + 1L)
            events += event
            return true
        }
    }
}
