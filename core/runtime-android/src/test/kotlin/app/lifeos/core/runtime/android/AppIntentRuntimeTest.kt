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

class AppIntentRuntimeTest {
    @Test
    fun deep_link_rejects_file_http_and_unregistered_custom_schemes() {
        assertFailsWith<IllegalArgumentException> {
            AppDeepLink.create("file:///sdcard/secret.txt")
        }
        assertFailsWith<IllegalArgumentException> {
            AppDeepLink.create("http://example.com/path")
        }
        assertFailsWith<IllegalArgumentException> {
            AppDeepLink.create("lifeos://open/item")
        }

        val custom = AppDeepLink.create(
            "LIFEOS://open/../item",
            allowedCustomSchemes = setOf("lifeos"),
        )
        assertEquals("lifeos", custom.scheme)
        assertTrue(custom.canonicalUri.startsWith("lifeos:"))
    }

    @Test
    fun intent_extras_are_bounded_unique_and_canonical() {
        val request = AppIntentRequest.create(
            operation = AppIntentOperation.LAUNCH,
            action = AppIntentAction.MAIN,
            packageName = "app.example",
            extras = listOf(
                AppIntentExtra.Text("z", "last"),
                AppIntentExtra.Flag("a", true),
            ),
        )

        assertEquals(listOf("a", "z"), request.extras.map { it.key })

        assertFailsWith<IllegalArgumentException> {
            AppIntentRequest(
                operation = AppIntentOperation.LAUNCH,
                action = AppIntentAction.MAIN,
                extras = listOf(
                    AppIntentExtra.Flag("same", true),
                    AppIntentExtra.Number("same", 1),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AppIntentExtra.Text("text", "x".repeat(2049))
        }
    }

    @Test
    fun resolution_is_bounded_deterministic_and_non_authoritative() = runTest {
        val host = FakeHost().apply {
            targets = listOf(
                AppIntentTarget("z.example", "ZActivity"),
                AppIntentTarget("a.example", "AActivity"),
                AppIntentTarget("a.example", "AActivity"),
            )
        }
        val runtime = runtime(host)
        val request = AppIntentRequest.create(
            operation = AppIntentOperation.RESOLVE,
            action = AppIntentAction.MAIN,
        )

        val result = runtime.resolve(plan(AppIntentOperation.RESOLVE), request)

        assertEquals(
            listOf("a.example", "z.example"),
            result.targets.map { it.packageName },
        )
        assertFalse(result.executionAuthority)
        assertTrue(result.fingerprint.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun exact_package_request_never_falls_back_to_other_package() = runTest {
        val host = FakeHost().apply {
            targets = listOf(AppIntentTarget("other.example", "MainActivity"))
        }
        val runtime = runtime(host)
        val request = AppIntentRequest.create(
            operation = AppIntentOperation.LAUNCH,
            action = AppIntentAction.MAIN,
            packageName = "wanted.example",
        )

        assertFailsWith<IllegalArgumentException> {
            runtime.prepareLaunch(
                plan(AppIntentOperation.LAUNCH),
                OWNER,
                request,
            )
        }
        assertEquals(0, host.launchCalls)
    }

    @Test
    fun missing_owner_grant_blocks_before_launch() = runTest {
        val target = AppIntentTarget("app.example", "MainActivity")
        val host = FakeHost().apply { targets = listOf(target) }
        val runtime = runtime(host)
        val request = AppIntentRequest.create(
            operation = AppIntentOperation.LAUNCH,
            action = AppIntentAction.MAIN,
            packageName = target.packageName,
        )

        val result = runtime.prepareLaunch(
            plan(AppIntentOperation.LAUNCH),
            OWNER,
            request,
        )

        assertIs<AppIntentPrepareResult.Blocked>(result)
        assertEquals(0, host.launchCalls)
    }

    @Test
    fun owner_revocation_after_preparation_wins_at_exposure() = runTest {
        val target = AppIntentTarget("app.example", "MainActivity")
        val host = FakeHost().apply { targets = listOf(target) }
        val repository = TestRepository()
        val ledger = OwnerPolicyLedger(repository) { NOW }
        val runtime = AppIntentRuntime(host, OwnerPolicyEffectGate(ledger))
        val plan = plan(AppIntentOperation.LAUNCH)
        val grant = grant(plan)
        ledger.grant(grant)
        val request = AppIntentRequest.create(
            operation = AppIntentOperation.LAUNCH,
            action = AppIntentAction.MAIN,
            packageName = target.packageName,
        )
        val prepared = assertIs<AppIntentPrepareResult.Ready>(
            runtime.prepareLaunch(plan, OWNER, request)
        ).launchPlan
        ledger.revoke(grant.id)

        val result = runtime.launch(plan, OWNER, prepared)

        assertIs<AppIntentExecutionResult.Blocked>(result)
        assertEquals(0, host.launchCalls)
    }

    @Test
    fun target_set_change_after_preparation_fails_closed() = runTest {
        val first = AppIntentTarget("app.example", "MainActivity")
        val host = FakeHost().apply { targets = listOf(first) }
        val repository = TestRepository()
        val ledger = OwnerPolicyLedger(repository) { NOW }
        val runtime = AppIntentRuntime(host, OwnerPolicyEffectGate(ledger))
        val plan = plan(AppIntentOperation.LAUNCH)
        ledger.grant(grant(plan))
        val request = AppIntentRequest.create(
            operation = AppIntentOperation.LAUNCH,
            action = AppIntentAction.MAIN,
            exactTarget = first,
        )
        val prepared = assertIs<AppIntentPrepareResult.Ready>(
            runtime.prepareLaunch(plan, OWNER, request)
        ).launchPlan

        host.targets = listOf(
            first,
            AppIntentTarget("other.example", "OtherActivity"),
        )

        assertFailsWith<IllegalArgumentException> {
            runtime.launch(plan, OWNER, prepared)
        }
        assertEquals(0, host.launchCalls)
    }

    @Test
    fun successful_deep_link_launch_returns_exact_receipt() = runTest {
        val target = AppIntentTarget("browser.example", "BrowserActivity")
        val host = FakeHost().apply { targets = listOf(target) }
        val repository = TestRepository()
        val ledger = OwnerPolicyLedger(repository) { NOW }
        val runtime = AppIntentRuntime(host, OwnerPolicyEffectGate(ledger))
        val plan = plan(AppIntentOperation.DEEP_LINK_OPEN)
        ledger.grant(grant(plan))
        val deepLink = AppDeepLink.create("https://Example.com:443/a/../b?q=1")
        val request = AppIntentRequest.create(
            operation = AppIntentOperation.DEEP_LINK_OPEN,
            action = AppIntentAction.VIEW,
            deepLink = deepLink,
            exactTarget = target,
        )
        val prepared = assertIs<AppIntentPrepareResult.Ready>(
            runtime.prepareLaunch(plan, OWNER, request)
        ).launchPlan

        val result = assertIs<AppIntentExecutionResult.Launched>(
            runtime.launch(plan, OWNER, prepared)
        )

        assertEquals(target, result.receipt.target)
        assertEquals(deepLink.canonicalUri, result.receipt.canonicalUri)
        assertEquals(AppIntentAction.VIEW, result.receipt.action)
        assertEquals(1, host.launchCalls)
        assertTrue(result.policyAssessment.allowed)
    }

    private fun runtime(host: FakeHost): AppIntentRuntime =
        AppIntentRuntime(
            host,
            OwnerPolicyEffectGate(OwnerPolicyLedger(TestRepository()) { NOW }),
        )

    private fun plan(
        operation: AppIntentOperation,
    ): AndroidCapabilityDispatchPlan {
        val descriptor = CapabilityDescriptor(
            capabilityId = operation.capabilityId,
            providerId = "android.intent",
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
            requestFingerprint = "d".repeat(64),
            binding = AndroidCapabilityBinding(
                descriptor = descriptor,
                providerVersion = "b408-v1",
                requiredOwnerEffect =
                    if (operation.launchesExternally) {
                        OwnerEffectType.EXTERNAL_APP_HANDOFF
                    } else {
                        null
                    },
                ownerScope = operation.capabilityValue,
                permissions = emptyList(),
                riskClass =
                    if (operation.launchesExternally) {
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
        effect = OwnerEffectType.EXTERNAL_APP_HANDOFF,
        resource = OwnerResourceSelector(OwnerResourceSelectorType.ANY),
        scope = plan.binding.ownerScope,
        capability = OwnerCapabilityConstraint(
            capabilityId = plan.capabilityId,
            providerVersion = plan.binding.providerVersion,
        ),
        validFrom = NOW.minusSeconds(1),
    )

    private class FakeHost : AppIntentHost {
        var targets: List<AppIntentTarget> = emptyList()
        var launchCalls: Int = 0

        override suspend fun resolve(
            request: AppIntentRequest,
        ): List<AppIntentTarget> = targets

        override suspend fun launch(
            request: AppIntentRequest,
            target: AppIntentTarget,
        ): AppIntentLaunchReceipt {
            launchCalls += 1
            return AppIntentLaunchReceipt.create(request, target)
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
        val OWNER = OwnerActorId("app-intent-runtime")
    }
}
