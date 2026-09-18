package app.lifeos.next.kernel

import app.lifeos.core.language.GoalFrame
import app.lifeos.core.language.IntentType
import app.lifeos.core.language.LanguageCode
import app.lifeos.core.language.LanguageUnderstandingEngine
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.Provenance
import app.lifeos.core.runtime.capability.GoalCapabilityPlan
import app.lifeos.core.runtime.capability.GoalCapabilityResolution
import app.lifeos.core.runtime.policy.OwnerPolicyLedger
import app.lifeos.core.runtime.policy.OwnerPolicyEvent
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

class ImageGenerationResourceProfileTest {
    @Test
    fun fixed512x288ImageDemandFitsTwoCoreNominalEnvelopeWithoutRelaxingHardQuota() = runTest {
        val ownerRepository = MemoryOwnerPolicyRepository()
        val budgetRepository = MemoryResourceBudgetRepository()
        val guard = PrivateGoalActionExecutionGuard(
            ownerPolicy = OwnerPolicyLedger(ownerRepository),
            budgets = ResourceBudgetCoordinator(budgetRepository),
            hardware = HardwareExecutionBudgetGate { hardQuota, requested, priority ->
                HardwareExecutionBudgetDecision.Ready(
                    HardwareAdaptiveResourceOptimizer().plan(
                        hardQuota = hardQuota,
                        requested = requested,
                        hardware = twoCoreHardware(),
                        priority = priority,
                    )
                )
            },
        )

        val permit = assertIs<GoalActionExecutionPermit.Reserved>(
            guard.prepare(context("goal-image-two-core"))
        )

        assertEquals(20_000L, permit.reservation.reserved.elapsedMillis)
        assertEquals(48L, permit.reservation.reserved.workUnits)
        assertEquals(48L * MIB, permit.reservation.reserved.memoryBytes)
        assertEquals(16L * MIB, permit.reservation.reserved.ioBytes)
        assertEquals(0L, permit.reservation.reserved.networkBytes)
        assertEquals(2L, permit.reservation.reserved.candidates)

        val account = requireNotNull(budgetRepository.accounts[permit.accountId])
        assertEquals(30_000L, account.quota.elapsedMillis)
        assertEquals(256L, account.quota.workUnits)
        assertEquals(512L * MIB, account.quota.memoryBytes)
        assertEquals(160L * MIB, account.quota.ioBytes)
        assertEquals(0L, account.quota.networkBytes)
        assertEquals(8L, account.quota.candidates)
    }

    private fun context(goalPhotonId: String): GoalActionContext {
        val goal = LanguageUnderstandingEngine()
            .understand("Create an image.")
            .goal
            .copy(objective = "create_image: render a deterministic 512x288 scene")
        return GoalActionContext(
            goal = goal,
            routing = GoalCapabilityResolution(
                plan = GoalCapabilityPlan(goal, emptyList(), languageBlocking = false),
                selectedProviders = emptyMap(),
                gaps = emptyList(),
            ),
            sourcePhoton = Photon(
                id = PhotonId("source-$goalPhotonId"),
                content = "render image",
                provenance = Provenance(
                    source = "unit-test",
                    actor = "ImageGenerationResourceProfileTest",
                    createdAt = NOW,
                ),
            ),
            goalPhotonId = PhotonId(goalPhotonId),
        )
    }

    private fun twoCoreHardware() = HardwareStateSnapshot(
        observedAt = NOW,
        availableProcessors = 2,
        batteryFraction = 1.0,
        charging = true,
        thermalState = HardwareThermalState.NOMINAL,
        cpuLoadFraction = 0.0,
        availableMemoryBytes = 1536L * MIB,
        totalMemoryBytes = 2560L * MIB,
        availableStorageBytes = 8L * GIB,
        totalStorageBytes = 16L * GIB,
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
        val NOW: Instant = Instant.parse("2026-09-13T12:00:00Z")
        const val MIB = 1024L * 1024L
        const val GIB = 1024L * MIB
    }
}
