package app.lifeos.core.runtime.android

import app.lifeos.core.runtime.capability.CapabilityContract
import app.lifeos.core.runtime.capability.CapabilityDescriptor
import app.lifeos.core.runtime.capability.ProviderState
import app.lifeos.core.runtime.capability.ProviderType
import app.lifeos.core.runtime.capability.TrustLevel
import app.lifeos.core.runtime.policy.OwnerActorId
import app.lifeos.core.runtime.policy.OwnerCapabilityConstraint
import app.lifeos.core.runtime.policy.OwnerEffectType
import app.lifeos.core.runtime.policy.OwnerPolicyEffectGate
import app.lifeos.core.runtime.policy.OwnerPolicyEvent
import app.lifeos.core.runtime.policy.OwnerPolicyGrant
import app.lifeos.core.runtime.policy.OwnerPolicyLedger
import app.lifeos.core.runtime.policy.OwnerPolicyRepository
import app.lifeos.core.runtime.policy.OwnerPolicyRepositoryLoadReport
import app.lifeos.core.runtime.policy.OwnerResourceSelector
import app.lifeos.core.runtime.policy.OwnerResourceSelectorType
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NotificationActionRuntimeTest {
    @Test
    fun observation_requires_listener_special_access_metadata_and_grants_no_execution_authority() = runTest {
        val identity = identity("a")
        val snapshot = snapshot(identity, "Reply")
        val host = FakeHost(snapshot)
        val runtime = runtime(host)

        assertFailsWith<IllegalArgumentException> {
            runtime.observe(
                plan(NotificationOperation.OBSERVE, includeSpecialAccess = false),
                identity,
            )
        }

        val observed = runtime.observe(
            plan(NotificationOperation.OBSERVE),
            identity,
        )
        assertEquals(snapshot, observed)
        assertFalse(requireNotNull(observed).executionAuthority)
    }

    @Test
    fun invoke_without_owner_grant_never_exposes_host_action() = runTest {
        val identity = identity("b")
        val snapshot = snapshot(identity, "Reply")
        val host = FakeHost(snapshot)
        val runtime = runtime(host)
        val request = NotificationActionRequest(identity, snapshot.actions.single())

        val result = runtime.prepareInvoke(
            plan(NotificationOperation.INVOKE_ACTION),
            OWNER,
            request,
        )

        assertIs<NotificationPreparationResult.Blocked>(result)
        assertEquals(0, host.invokeCalls)
    }

    @Test
    fun revocation_after_prepare_wins_before_action_callback() = runTest {
        val identity = identity("c")
        val snapshot = snapshot(identity, "Open")
        val host = FakeHost(snapshot)
        val repository = TestRepository()
        val ledger = OwnerPolicyLedger(repository) { NOW }
        val runtime = NotificationActionRuntime(host, OwnerPolicyEffectGate(ledger))
        val plan = plan(NotificationOperation.INVOKE_ACTION)
        val grant = grant(plan)
        ledger.grant(grant)
        val request = NotificationActionRequest(identity, snapshot.actions.single())
        val prepared = assertIs<NotificationPreparationResult.Ready>(
            runtime.prepareInvoke(plan, OWNER, request)
        ).plan
        ledger.revoke(grant.id)

        val result = runtime.invoke(plan, OWNER, prepared)

        assertIs<NotificationExecutionResult.Blocked>(result)
        assertEquals(0, host.invokeCalls)
    }

    @Test
    fun updated_notification_invalidates_prepared_action() = runTest {
        val identity = identity("d")
        val initial = snapshot(identity, "Reply")
        val host = FakeHost(initial)
        val repository = TestRepository()
        val ledger = OwnerPolicyLedger(repository) { NOW }
        val runtime = NotificationActionRuntime(host, OwnerPolicyEffectGate(ledger))
        val plan = plan(NotificationOperation.INVOKE_ACTION)
        ledger.grant(grant(plan))
        val request = NotificationActionRequest(identity, initial.actions.single())
        val prepared = assertIs<NotificationPreparationResult.Ready>(
            runtime.prepareInvoke(plan, OWNER, request)
        ).plan

        host.current = NotificationHandleSnapshot.create(
            identity,
            listOf(NotificationActionDescriptor.create(identity, 0, "Different")),
        )

        assertFailsWith<IllegalArgumentException> {
            runtime.invoke(plan, OWNER, prepared)
        }
        assertEquals(0, host.invokeCalls)
    }

    @Test
    fun exact_action_descriptor_and_identity_are_bound() = runTest {
        val identity = identity("e")
        val snapshot = NotificationHandleSnapshot.create(
            identity,
            listOf(
                NotificationActionDescriptor.create(identity, 0, "Reply"),
                NotificationActionDescriptor.create(identity, 1, "Mark read"),
            ),
        )
        val host = FakeHost(snapshot)
        val repository = TestRepository()
        val ledger = OwnerPolicyLedger(repository) { NOW }
        val runtime = NotificationActionRuntime(host, OwnerPolicyEffectGate(ledger))
        val plan = plan(NotificationOperation.INVOKE_ACTION)
        ledger.grant(grant(plan))
        val selected = snapshot.actions[1]
        val request = NotificationActionRequest(identity, selected)
        val prepared = assertIs<NotificationPreparationResult.Ready>(
            runtime.prepareInvoke(plan, OWNER, request)
        ).plan

        val result = assertIs<NotificationExecutionResult.Exposed>(
            runtime.invoke(plan, OWNER, prepared)
        )

        assertEquals(selected, result.receipt.action)
        assertEquals(identity, result.receipt.identity)
        assertEquals(1, host.invokeCalls)
        assertTrue(result.policyAssessment.allowed)
    }

    @Test
    fun dismiss_is_exact_identity_bound_and_separate_from_observed_outcome() = runTest {
        val identity = identity("f")
        val snapshot = snapshot(identity, "Open")
        val host = FakeHost(snapshot)
        val repository = TestRepository()
        val ledger = OwnerPolicyLedger(repository) { NOW }
        val runtime = NotificationActionRuntime(host, OwnerPolicyEffectGate(ledger))
        val plan = plan(NotificationOperation.DISMISS)
        ledger.grant(grant(plan))
        val prepared = assertIs<NotificationPreparationResult.Ready>(
            runtime.prepareDismiss(
                plan,
                OWNER,
                NotificationDismissRequest(identity),
            )
        ).plan

        val result = assertIs<NotificationExecutionResult.Exposed>(
            runtime.dismiss(plan, OWNER, prepared)
        )

        assertEquals(NotificationOperation.DISMISS, result.receipt.operation)
        assertEquals(identity, result.receipt.identity)
        assertEquals(null, result.receipt.action)
        assertEquals(1, host.dismissCalls)
        // Host receipt records exposure only; the fake observation can still exist until a later delta.
        assertEquals(snapshot, host.current)
    }

    private fun runtime(host: FakeHost): NotificationActionRuntime =
        NotificationActionRuntime(
            host,
            OwnerPolicyEffectGate(OwnerPolicyLedger(TestRepository()) { NOW }),
        )

    private fun identity(suffix: String): NotificationIdentity =
        NotificationIdentity(
            notificationKey = "key-$suffix",
            packageName = "app.example",
            postedAtMillis = 100L,
            observationFingerprint = suffix.padEnd(64, 'a').take(64),
        )

    private fun snapshot(
        identity: NotificationIdentity,
        label: String,
    ): NotificationHandleSnapshot =
        NotificationHandleSnapshot.create(
            identity = identity,
            actions = listOf(NotificationActionDescriptor.create(identity, 0, label)),
        )

    private fun plan(
        operation: NotificationOperation,
        includeSpecialAccess: Boolean = true,
    ): AndroidCapabilityDispatchPlan {
        val descriptor = CapabilityDescriptor(
            capabilityId = operation.capabilityId,
            providerId = "android.notifications",
            providerType = ProviderType.MODULE,
            contract = CapabilityContract(
                requiredInputs = setOf("request"),
                outputs = setOf("result"),
            ),
            state = ProviderState.ACTIVE,
            trustLevel = TrustLevel.SYSTEM,
            reliability = 1.0,
        )
        return AndroidCapabilityDispatchPlan(
            requestFingerprint = "e".repeat(64),
            binding = AndroidCapabilityBinding(
                descriptor = descriptor,
                providerVersion = "b409-v1",
                requiredOwnerEffect =
                    if (operation.productive) OwnerEffectType.NOTIFICATION_ACTION else null,
                ownerScope = operation.capabilityValue,
                permissions =
                    if (includeSpecialAccess) {
                        listOf(
                            AndroidPermissionRequirement(
                                AndroidPermissionKind.SPECIAL_ACCESS,
                                NOTIFICATION_LISTENER_ACCESS,
                            )
                        )
                    } else {
                        emptyList()
                    },
                riskClass =
                    if (operation.productive) {
                        AndroidCapabilityRiskClass.MEDIUM
                    } else {
                        AndroidCapabilityRiskClass.LOW
                    },
                reversibility = AndroidCapabilityReversibility.COMPENSATABLE,
                recoverySemantics = AndroidRecoverySemantics.MANUAL_REVIEW,
                expectedOutcomeContract = "result",
            ),
        )
    }

    private fun grant(
        plan: AndroidCapabilityDispatchPlan,
    ): OwnerPolicyGrant = OwnerPolicyGrant.create(
        actorId = OWNER,
        effect = OwnerEffectType.NOTIFICATION_ACTION,
        resource = OwnerResourceSelector(OwnerResourceSelectorType.ANY),
        scope = plan.binding.ownerScope,
        capability = OwnerCapabilityConstraint(
            capabilityId = plan.capabilityId,
            providerVersion = plan.binding.providerVersion,
        ),
        validFrom = NOW.minusSeconds(1),
    )

    private class FakeHost(
        var current: NotificationHandleSnapshot?,
    ) : NotificationActionHost {
        var invokeCalls = 0
        var dismissCalls = 0

        override suspend fun inspect(
            identity: NotificationIdentity,
        ): NotificationHandleSnapshot? =
            current?.takeIf { it.identity == identity }

        override suspend fun invoke(
            request: NotificationActionRequest,
        ): NotificationActionReceipt {
            invokeCalls += 1
            return NotificationActionReceipt.invoked(request)
        }

        override suspend fun dismiss(
            request: NotificationDismissRequest,
        ): NotificationActionReceipt {
            dismissCalls += 1
            return NotificationActionReceipt.dismissed(request)
        }
    }

    private class TestRepository : OwnerPolicyRepository {
        val events = mutableListOf<OwnerPolicyEvent>()

        override suspend fun loadReport() =
            OwnerPolicyRepositoryLoadReport(events.toList())

        override suspend fun append(
            expectedRevision: Long,
            event: OwnerPolicyEvent,
        ): Boolean {
            val current = events.lastOrNull()?.revision ?: 0L
            if (current != expectedRevision) return false
            require(event.revision == expectedRevision + 1L)
            events += event
            return true
        }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-22T00:00:00Z")
        val OWNER = OwnerActorId("notification-action-runtime")
    }
}
