package app.lifeos.core.runtime.resource

import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ResourceBudgetCoordinatorTest {
    @Test
    fun restartReusesReservationAndDoesNotDoubleChargeCommittedUsage() = runTest {
        val repository = TestResourceBudgetRepository()
        val accountId = ResourceBudgetAccountId("goal:plan-1")
        val quota = ResourceBudgetQuota(
            elapsedMillis = 10_000,
            workUnits = 10,
            memoryBytes = 4_096,
            ioBytes = 4_096,
            networkBytes = 1_000,
            candidates = 2,
        )
        val first = ResourceBudgetCoordinator(repository, fixedClock())
        first.createAccount(accountId, quota)

        val reserved = assertIs<ResourceBudgetReservationResult.Reserved>(
            first.reserve(
                accountId = accountId,
                idempotencyKey = "goal-action:1",
                usage = ResourceBudgetUsage(workUnits = 4, networkBytes = 100),
            )
        ).reservation

        val afterRestart = ResourceBudgetCoordinator(repository, fixedClock())
        val replay = assertIs<ResourceBudgetReservationResult.Existing>(
            afterRestart.reserve(
                accountId = accountId,
                idempotencyKey = "goal-action:1",
                usage = ResourceBudgetUsage(workUnits = 4, networkBytes = 100),
            )
        ).reservation
        assertEquals(reserved.id, replay.id)

        afterRestart.commit(
            accountId = accountId,
            reservationId = reserved.id,
            actualUsage = ResourceBudgetUsage(workUnits = 3, networkBytes = 90),
        )

        val secondRestart = ResourceBudgetCoordinator(repository, fixedClock())
        secondRestart.commit(
            accountId = accountId,
            reservationId = reserved.id,
            actualUsage = ResourceBudgetUsage(workUnits = 3, networkBytes = 90),
        )

        val account = secondRestart.current(accountId)
        assertEquals(3L, account.consumed.workUnits)
        assertEquals(90L, account.consumed.networkBytes)
        assertEquals(1, account.reservations.size)
        assertEquals(ResourceBudgetReservationState.COMMITTED, account.reservations.single().state)
    }

    @Test
    fun capacityIsReservedBeforeWorkAndOverbookingIsDenied() = runTest {
        val repository = TestResourceBudgetRepository()
        val accountId = ResourceBudgetAccountId("deep-search:1")
        val coordinator = ResourceBudgetCoordinator(repository, fixedClock())
        coordinator.createAccount(
            accountId = accountId,
            quota = ResourceBudgetQuota(
                elapsedMillis = 1_000,
                workUnits = 5,
                memoryBytes = 1_000,
                ioBytes = 1_000,
                networkBytes = 1_000,
                candidates = 1,
            ),
        )
        assertIs<ResourceBudgetReservationResult.Reserved>(
            coordinator.reserve(
                accountId,
                "search:branch-a",
                ResourceBudgetUsage(workUnits = 4, candidates = 1),
            )
        )

        val denied = assertIs<ResourceBudgetReservationResult.Denied>(
            coordinator.reserve(
                accountId,
                "search:branch-b",
                ResourceBudgetUsage(workUnits = 2),
            )
        )
        assertEquals("resource-budget-exhausted", denied.reason)
    }

    @Test
    fun unreadableStoreFailsClosed() = runTest {
        val repository = object : ResourceBudgetRepository {
            override suspend fun load(accountId: ResourceBudgetAccountId) =
                ResourceBudgetRepositoryLoadReport(null, listOf("corrupt-budget-entry"))

            override suspend fun create(account: ResourceBudgetAccount): Boolean = false

            override suspend fun compareAndSet(
                accountId: ResourceBudgetAccountId,
                expectedRevision: Long,
                updated: ResourceBudgetAccount,
            ): Boolean = false
        }
        val coordinator = ResourceBudgetCoordinator(repository, fixedClock())

        assertFailsWith<IllegalStateException> {
            coordinator.current(ResourceBudgetAccountId("corrupt"))
        }
    }

    private fun fixedClock(): () -> Instant = { Instant.parse("2026-09-11T12:00:00Z") }

    private class TestResourceBudgetRepository : ResourceBudgetRepository {
        private val accounts = linkedMapOf<ResourceBudgetAccountId, ResourceBudgetAccount>()

        override suspend fun load(accountId: ResourceBudgetAccountId): ResourceBudgetRepositoryLoadReport =
            ResourceBudgetRepositoryLoadReport(accounts[accountId])

        override suspend fun create(account: ResourceBudgetAccount): Boolean {
            if (accounts.containsKey(account.id)) return false
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
            require(updated.id == accountId)
            require(updated.revision == expectedRevision + 1L)
            accounts[accountId] = updated
            return true
        }
    }
}
