package app.lifeos.core.runtime.resource

import app.lifeos.core.runtime.trace.DecisionTraceId
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ResourceIntelligenceCompletionTest {
    @Test
    fun learnedEstimateNeverRaisesHardCeiling() {
        val estimate = SoftCostEstimate(
            domain = ResourceBudgetDomain.DEEP_SEARCH,
            operationKind = "search",
            estimated = ResourceBudgetUsage(
                elapsedMillis = 99_000,
                workUnits = 100,
                memoryBytes = 100_000,
                ioBytes = 100_000,
                networkBytes = 100_000,
                candidates = 100,
            ),
            samples = 20,
            confidence = 0.95,
        )
        val hard = ResourceBudgetQuota(
            elapsedMillis = 1_000,
            workUnits = 10,
            memoryBytes = 2_000,
            ioBytes = 3_000,
            networkBytes = 4_000,
            candidates = 2,
        )

        assertEquals(
            ResourceBudgetUsage(1_000, 10, 2_000, 3_000, 4_000, 2),
            estimate.conservativeReservation(hard),
        )
    }

    @Test
    fun fairnessPrefersMostDeferredLaneDeterministically() {
        val state = ResourceFairnessState(
            revision = 1,
            lanes = listOf(
                ResourceFairnessLane(ResourceBudgetDomain.GOAL_EXECUTION, 2, 10, 1, null),
                ResourceFairnessLane(ResourceBudgetDomain.BACKGROUND_LEARNING, 1, 1, 5, null),
                ResourceFairnessLane(ResourceBudgetDomain.DEEP_SEARCH, 1, 2, 5, null),
            ),
        )

        assertEquals(
            ResourceBudgetDomain.BACKGROUND_LEARNING,
            state.nextEligible(ResourceBudgetDomain.entries.toSet()),
        )
    }

    @Test
    fun bindingRequiresTraceAndDurableOperationIdentity() {
        val binding = ResourceExecutionBinding(
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

        assertEquals("goal-action:1", binding.operationId)
        assertEquals(ResourceBudgetDomain.GOAL_EXECUTION, binding.domain)
    }

    @Test
    fun restartReconcilerCommitsOnlyAfterDurableOutcome() = runTest {
        val repository = TestRepository()
        val accountId = ResourceBudgetAccountId("goal:g1")
        val coordinator = ResourceBudgetCoordinator(repository) { Instant.parse("2026-09-12T00:00:00Z") }
        coordinator.createAccount(
            accountId,
            ResourceBudgetQuota(1_000, 10, 1_000, 1_000, 1_000, 1),
        )
        assertIs<ResourceBudgetReservationResult.Reserved>(
            coordinator.reserve(accountId, "goal-action:1", ResourceBudgetUsage(workUnits = 5)),
        )

        val inFlight = ResourceReservationReconciler(
            coordinator,
            AuthoritativeExecutionStateReader { _, _ ->
                AuthoritativeExecutionState.InFlight("task:running")
            },
        ).reconcile(accountId)

        assertEquals(ResourceReconciliationAction.KEPT_IN_FLIGHT, inFlight.entries.single().action)
        assertEquals(ResourceBudgetReservationState.RESERVED, coordinator.current(accountId).reservations.single().state)

        val completed = ResourceReservationReconciler(
            coordinator,
            AuthoritativeExecutionStateReader { _, _ ->
                AuthoritativeExecutionState.Completed(
                    "outcome:durable",
                    ResourceBudgetUsage(workUnits = 3),
                )
            },
        ).reconcile(accountId)

        assertEquals(ResourceReconciliationAction.COMMITTED, completed.entries.single().action)
        val current = coordinator.current(accountId)
        assertEquals(ResourceBudgetReservationState.COMMITTED, current.reservations.single().state)
        assertEquals(3L, current.consumed.workUnits)
    }

    @Test
    fun unreadableRestartStateFailsClosedAndKeepsCapacityHeld() = runTest {
        val repository = TestRepository()
        val accountId = ResourceBudgetAccountId("deep-search:s1")
        val coordinator = ResourceBudgetCoordinator(repository) { Instant.parse("2026-09-12T00:00:00Z") }
        coordinator.createAccount(
            accountId,
            ResourceBudgetQuota(1_000, 10, 1_000, 1_000, 1_000, 1),
        )
        coordinator.reserve(accountId, "search:1", ResourceBudgetUsage(workUnits = 5))

        val report = ResourceReservationReconciler(
            coordinator,
            AuthoritativeExecutionStateReader { _, _ ->
                AuthoritativeExecutionState.Unreadable("checkpoint-corrupt")
            },
        ).reconcile(accountId)

        assertTrue(report.blocked)
        assertEquals(ResourceReconciliationAction.BLOCKED_UNREADABLE, report.entries.single().action)
        assertEquals(ResourceBudgetReservationState.RESERVED, coordinator.current(accountId).reservations.single().state)
    }

    private class TestRepository : ResourceBudgetRepository {
        private val accounts = linkedMapOf<ResourceBudgetAccountId, ResourceBudgetAccount>()

        override suspend fun load(accountId: ResourceBudgetAccountId) =
            ResourceBudgetRepositoryLoadReport(accounts[accountId])

        override suspend fun create(account: ResourceBudgetAccount): Boolean {
            if (account.id in accounts) return false
            accounts[account.id] = account
            return true
        }

        override suspend fun compareAndSet(
            accountId: ResourceBudgetAccountId,
            expectedRevision: Long,
            updated: ResourceBudgetAccount,
        ): Boolean {
            val current = accounts[accountId] ?: return false
            if (current.revision != expectedRevision) return false
            accounts[accountId] = updated
            return true
        }
    }
}
