package app.lifeos.core.runtime.policy

import app.lifeos.core.runtime.capability.CapabilityId
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * V14 productive-effect contracts.
 *
 * These tests intentionally rebuild OwnerPolicyLedger over the same durable repository after a
 * revoke to model process death/restart. A preparation from the old process must never authorize
 * ToolWorkshop execution or provider cutover in the resumed process.
 */
class OwnerPolicyProductiveEffectContractTest {
    @Test
    fun `tool execution prepared before revoke is blocked after restart`() = runTest {
        val repository = MemoryOwnerPolicyRepository()
        val beforeRestart = OwnerPolicyLedger(repository) { NOW }
        val grant = grant(
            effect = OwnerEffectType.TOOL_EXECUTION,
            resourcePrefix = "tool-workshop:",
            scope = TOOL_SCOPE,
            capabilityId = TOOL_CAPABILITY,
            providerVersion = TOOL_VERSION,
        )
        beforeRestart.grant(grant)
        val request = OwnerEffectRequest(
            actorId = OWNER,
            effect = OwnerEffectType.TOOL_EXECUTION,
            resource = "tool-workshop:job-17:specified",
            scope = TOOL_SCOPE,
            capabilityId = TOOL_CAPABILITY,
            providerVersion = TOOL_VERSION,
        )
        val prepared = assertIs<OwnerEffectPreparationResult.Ready>(
            OwnerPolicyEffectGate(beforeRestart).prepare(request)
        ).preparation

        beforeRestart.revoke(grant.id)

        val resumedLedger = OwnerPolicyLedger(repository) { NOW.plusSeconds(1) }
        var stageExecutions = 0
        val exposure = OwnerPolicyEffectGate(resumedLedger).expose(request, prepared) {
            stageExecutions += 1
            "stage-artifact"
        }

        val blocked = assertIs<OwnerEffectExposureResult.Blocked>(exposure)
        assertEquals(0, stageExecutions)
        assertEquals(2L, blocked.assessment.policyRevision)
        assertTrue(blocked.assessment.reasonCodes.isNotEmpty())
    }

    @Test
    fun `provider activation prepared before revoke cannot cut over after restart`() = runTest {
        val repository = MemoryOwnerPolicyRepository()
        val beforeRestart = OwnerPolicyLedger(repository) { NOW }
        val grant = grant(
            effect = OwnerEffectType.PROVIDER_ACTIVATION,
            resourcePrefix = "hot-swap:",
            scope = HOT_SWAP_SCOPE,
            capabilityId = PROVIDER_CAPABILITY,
            providerVersion = PROVIDER_VERSION,
        )
        beforeRestart.grant(grant)
        val request = OwnerEffectRequest(
            actorId = OWNER,
            effect = OwnerEffectType.PROVIDER_ACTIVATION,
            resource = "hot-swap:${PROVIDER_CAPABILITY.value}:tool-old->tool-new",
            scope = HOT_SWAP_SCOPE,
            capabilityId = PROVIDER_CAPABILITY,
            providerVersion = PROVIDER_VERSION,
        )
        val prepared = assertIs<OwnerEffectPreparationResult.Ready>(
            OwnerPolicyEffectGate(beforeRestart).prepare(request)
        ).preparation

        beforeRestart.revoke(grant.id)

        val resumedLedger = OwnerPolicyLedger(repository) { NOW.plusSeconds(1) }
        var providerCutovers = 0
        val exposure = OwnerPolicyEffectGate(resumedLedger).expose(request, prepared) {
            providerCutovers += 1
            "new-provider-active"
        }

        val blocked = assertIs<OwnerEffectExposureResult.Blocked>(exposure)
        assertEquals(0, providerCutovers)
        assertEquals(2L, blocked.assessment.policyRevision)
        assertTrue(blocked.assessment.reasonCodes.isNotEmpty())
    }

    @Test
    fun `unrelated policy mutation invalidates prepared productive authority`() = runTest {
        val repository = MemoryOwnerPolicyRepository()
        val ledger = OwnerPolicyLedger(repository) { NOW }
        val grant = grant(
            effect = OwnerEffectType.TOOL_EXECUTION,
            resourcePrefix = "tool-workshop:",
            scope = TOOL_SCOPE,
            capabilityId = TOOL_CAPABILITY,
            providerVersion = TOOL_VERSION,
        )
        ledger.grant(grant)
        val request = OwnerEffectRequest(
            actorId = OWNER,
            effect = OwnerEffectType.TOOL_EXECUTION,
            resource = "tool-workshop:job-21:built",
            scope = TOOL_SCOPE,
            capabilityId = TOOL_CAPABILITY,
            providerVersion = TOOL_VERSION,
        )
        val gate = OwnerPolicyEffectGate(ledger)
        val prepared = assertIs<OwnerEffectPreparationResult.Ready>(gate.prepare(request)).preparation

        ledger.grant(
            OwnerPolicyGrant.create(
                actorId = OwnerActorId("other-actor"),
                effect = OwnerEffectType.FILE_WRITE,
                resource = OwnerResourceSelector(OwnerResourceSelectorType.ANY),
                scope = "other-scope",
                validFrom = Instant.EPOCH,
            )
        )

        var stageExecutions = 0
        val exposure = gate.expose(request, prepared) {
            stageExecutions += 1
        }

        val blocked = assertIs<OwnerEffectExposureResult.Blocked>(exposure)
        assertEquals(0, stageExecutions)
        assertEquals(
            listOf(OwnerPolicyReasonCode.POLICY_CHANGED_SINCE_PREPARATION),
            blocked.assessment.reasonCodes,
        )
    }

    private fun grant(
        effect: OwnerEffectType,
        resourcePrefix: String,
        scope: String,
        capabilityId: CapabilityId,
        providerVersion: String,
    ): OwnerPolicyGrant = OwnerPolicyGrant.create(
        actorId = OWNER,
        effect = effect,
        resource = OwnerResourceSelector(OwnerResourceSelectorType.PREFIX, resourcePrefix),
        scope = scope,
        capability = OwnerCapabilityConstraint(capabilityId, providerVersion),
        validFrom = Instant.EPOCH,
    )

    private class MemoryOwnerPolicyRepository : OwnerPolicyRepository {
        private val events = mutableListOf<OwnerPolicyEvent>()

        override suspend fun loadReport() = OwnerPolicyRepositoryLoadReport(events.toList())

        override suspend fun append(expectedRevision: Long, event: OwnerPolicyEvent): Boolean {
            val current = events.lastOrNull()?.revision ?: 0L
            if (current != expectedRevision) return false
            require(event.revision == expectedRevision + 1L)
            events += event
            return true
        }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-11T20:00:00Z")
        val OWNER = OwnerActorId("private-owner-contract")
        val TOOL_CAPABILITY = CapabilityId("tool-workshop.contract")
        val PROVIDER_CAPABILITY = CapabilityId("provider.hot-swap.contract")
        const val TOOL_VERSION = "workshop-v14"
        const val PROVIDER_VERSION = "provider-build-v14"
        const val TOOL_SCOPE = "private-apk-tool-workshop"
        const val HOT_SWAP_SCOPE = "private-apk-hot-swap"
    }
}
