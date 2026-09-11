package app.lifeos.next.kernel

import app.lifeos.core.language.GoalFrame
import app.lifeos.core.language.IntentType
import app.lifeos.core.language.LanguageCode
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.Provenance
import app.lifeos.core.runtime.capability.GoalCapabilityPlan
import app.lifeos.core.runtime.capability.GoalCapabilityResolution
import app.lifeos.core.runtime.goal.LocalKnowledgeGoalKind
import app.lifeos.core.runtime.policy.OwnerEffectType
import app.lifeos.core.runtime.policy.OwnerPolicyEvent
import app.lifeos.core.runtime.policy.OwnerPolicyLedger
import app.lifeos.core.runtime.policy.OwnerPolicyRepository
import app.lifeos.core.runtime.policy.OwnerPolicyRepositoryLoadReport
import app.lifeos.core.runtime.resource.HardwareAdaptiveResourceOptimizer
import app.lifeos.core.runtime.resource.HardwareStateSnapshot
import app.lifeos.core.runtime.resource.HardwareThermalState
import app.lifeos.core.runtime.resource.ResourceBudgetAccount
import app.lifeos.core.runtime.resource.ResourceBudgetAccountId
import app.lifeos.core.runtime.resource.ResourceBudgetCoordinator
import app.lifeos.core.runtime.resource.ResourceBudgetRepository
import app.lifeos.core.runtime.resource.ResourceBudgetRepositoryLoadReport
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GoalActionExecutionGuardTest {
    @Test
    fun committedActionIsBlockedAfterGuardReconstruction() = runTest {
        val ownerRepository = MemoryOwnerPolicyRepository()
        val budgetRepository = MemoryResourceBudgetRepository()
        val context = context(IntentType.QUERY, "goal-query")
        val first = guard(ownerRepository, budgetRepository)

        val permit = assertIs<GoalActionExecutionPermit.Reserved>(first.prepare(context))
        first.settle(permit, successfulKnowledgeResult())

        val afterRestart = guard(ownerRepository, budgetRepository)
        val replay = assertIs<GoalActionExecutionPermit.Blocked>(afterRestart.prepare(context))
        assertEquals("goal-action-already-committed", replay.reason)
        val account = ResourceBudgetCoordinator(budgetRepository).current(permit.accountId)
        assertEquals(1, account.reservations.size)
        assertTrue(!account.consumed.isZero())
    }

    @Test
    fun failedActionKeepsReservationReusableAcrossReconstruction() = runTest {
        val ownerRepository = MemoryOwnerPolicyRepository()
        val budgetRepository = MemoryResourceBudgetRepository()
        val context = context(IntentType.QUERY, "goal-retry")
        val first = guard(ownerRepository, budgetRepository)

        val permit = assertIs<GoalActionExecutionPermit.Reserved>(first.prepare(context))
        first.settle(
            permit,
            GoalActionDispatchResult(
                localKnowledge = LocalKnowledgeExecutionResult.Failed("transient")
            ),
        )

        val afterRestart = guard(ownerRepository, budgetRepository)
        val replay = assertIs<GoalActionExecutionPermit.Reserved>(afterRestart.prepare(context))
        assertEquals(permit.reservation.id, replay.reservation.id)
    }

    @Test
    fun hardwareBlockFailsBeforeCreatingDurableAccount() = runTest {
        val ownerRepository = MemoryOwnerPolicyRepository()
        val budgetRepository = MemoryResourceBudgetRepository()
        val guard = PrivateGoalActionExecutionGuard(
            ownerPolicy = OwnerPolicyLedger(ownerRepository),
            budgets = ResourceBudgetCoordinator(budgetRepository),
            hardware = HardwareExecutionBudgetGate { _, _, _ ->
                HardwareExecutionBudgetDecision.Blocked("thermal-suspended")
            },
        )

        val permit = assertIs<GoalActionExecutionPermit.Blocked>(
            guard.prepare(context(IntentType.QUERY, "goal-hot"))
        )
        assertEquals("thermal-suspended", permit.reason)
        assertTrue(budgetRepository.accounts.isEmpty())
    }

    @Test
    fun revokedOwnerGrantRemainsRevokedAfterGuardReconstruction() = runTest {
        val ownerRepository = MemoryOwnerPolicyRepository()
        val budgetRepository = MemoryResourceBudgetRepository()
        val context = context(IntentType.SCHEDULE, "goal-reminder")
        val first = guard(ownerRepository, budgetRepository)

        assertIs<GoalActionExecutionPermit.Reserved>(first.prepare(context))
        val owner = OwnerPolicyLedger(ownerRepository)
        val reminderGrant = owner.snapshot().activeGrants.single { it.effect == OwnerEffectType.REMINDER }
        owner.revoke(reminderGrant.id)

        val afterRestart = guard(ownerRepository, budgetRepository)
        val blocked = assertIs<GoalActionExecutionPermit.Blocked>(afterRestart.prepare(context))
        assertTrue(blocked.reason.startsWith("owner-policy:"))
        assertTrue(owner.snapshot().activeGrants.none { it.id == reminderGrant.id })
    }

    private fun guard(
        ownerRepository: OwnerPolicyRepository,
        budgetRepository: ResourceBudgetRepository,
    ): PrivateGoalActionExecutionGuard = PrivateGoalActionExecutionGuard(
        ownerPolicy = OwnerPolicyLedger(ownerRepository),
        budgets = ResourceBudgetCoordinator(budgetRepository),
        hardware = HardwareExecutionBudgetGate { hardQuota, requested, priority ->
            val hardware = HardwareStateSnapshot(
                observedAt = Instant.parse("2026-09-11T10:00:00Z"),
                availableProcessors = 8,
                batteryFraction = 1.0,
                charging = true,
                thermalState = HardwareThermalState.NOMINAL,
                cpuLoadFraction = 0.0,
                availableMemoryBytes = 6L * GIB,
                totalMemoryBytes = 8L * GIB,
                availableStorageBytes = 64L * GIB,
                totalStorageBytes = 128L * GIB,
            )
            HardwareExecutionBudgetDecision.Ready(
                HardwareAdaptiveResourceOptimizer().plan(
                    hardQuota = hardQuota,
                    requested = requested,
                    hardware = hardware,
                    priority = priority,
                )
            )
        },
    )

    private fun context(intent: IntentType, goalPhotonId: String): GoalActionContext {
        val goal = GoalFrame(
            intent = intent,
            objective = "test goal",
            entities = emptyList(),
            references = emptyList(),
            constraints = emptyList(),
            ambiguities = emptyList(),
            confidence = 1.0,
            language = LanguageCode.EN,
        )
        return GoalActionContext(
            goal = goal,
            routing = GoalCapabilityResolution(
                plan = GoalCapabilityPlan(goal, emptyList(), languageBlocking = false),
                selectedProviders = emptyMap(),
                gaps = emptyList(),
            ),
            sourcePhoton = photon("source-$goalPhotonId"),
            goalPhotonId = PhotonId(goalPhotonId),
        )
    }

    private fun successfulKnowledgeResult(): GoalActionDispatchResult = GoalActionDispatchResult(
        localKnowledge = LocalKnowledgeExecutionResult.Produced(
            kind = LocalKnowledgeGoalKind.QUERY_ANSWER,
            output = PhotonSubmissionResult(
                photon = photon("knowledge-output"),
                processingQueued = false,
            ),
            evidencePhotonIds = emptyList(),
        )
    )

    private fun photon(id: String): Photon = Photon(
        id = PhotonId(id),
        content = "test",
        provenance = Provenance(
            source = "unit-test",
            actor = "GoalActionExecutionGuardTest",
            createdAt = Instant.parse("2026-09-11T10:00:00Z"),
        ),
    )

    private class MemoryOwnerPolicyRepository : OwnerPolicyRepository {
        private val events = mutableListOf<OwnerPolicyEvent>()

        override suspend fun loadReport(): OwnerPolicyRepositoryLoadReport =
            OwnerPolicyRepositoryLoadReport(events.toList())

        override suspend fun append(expectedRevision: Long, event: OwnerPolicyEvent): Boolean {
            val revision = events.lastOrNull()?.revision ?: 0L
            if (revision != expectedRevision) return false
            events += event
            return true
        }
    }

    private class MemoryResourceBudgetRepository : ResourceBudgetRepository {
        val accounts = linkedMapOf<ResourceBudgetAccountId, ResourceBudgetAccount>()

        override suspend fun load(accountId: ResourceBudgetAccountId): ResourceBudgetRepositoryLoadReport =
            ResourceBudgetRepositoryLoadReport(account = accounts[accountId])

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

    private companion object {
        const val GIB = 1024L * 1024L * 1024L
    }
}
