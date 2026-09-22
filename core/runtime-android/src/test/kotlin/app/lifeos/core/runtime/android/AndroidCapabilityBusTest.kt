package app.lifeos.core.runtime.android

import app.lifeos.core.runtime.capability.CapabilityContract
import app.lifeos.core.runtime.capability.CapabilityDescriptor
import app.lifeos.core.runtime.capability.CapabilityId
import app.lifeos.core.runtime.capability.CapabilityProviderCatalog
import app.lifeos.core.runtime.capability.ProviderState
import app.lifeos.core.runtime.capability.ProviderType
import app.lifeos.core.runtime.capability.TrustLevel
import app.lifeos.core.runtime.policy.OwnerEffectType
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class AndroidCapabilityBusTest {
    @Test
    fun ranked_catalog_order_selects_first_compatible_bound_provider() = runTest {
        val capability = CapabilityId("file.read")
        val preferred = descriptor(capability, "provider.preferred", reliability = 0.99)
        val fallback = descriptor(capability, "provider.fallback", reliability = 0.80)
        val bus = AndroidCapabilityBus(
            providerCatalog = Catalog(listOf(preferred, fallback)),
            bindings = listOf(binding(preferred), binding(fallback)),
        )

        val result = bus.resolve(
            request = request(capability),
            permissions = AndroidPermissionSnapshot(setOf(FILE_PERMISSION)),
        )

        val ready = assertIs<AndroidCapabilityResolution.Ready>(result)
        assertEquals("provider.preferred", ready.plan.providerId)
        assertFalse(ready.plan.executionAuthority)
        assertFalse(ready.plan.permissionGrantAuthority)
        assertFalse(ready.plan.ownerPolicyAuthority)
    }

    @Test
    fun missing_permission_fails_closed_without_dispatch_authority() = runTest {
        val descriptor = descriptor(CapabilityId("file.write"), "provider.file")
        val bus = AndroidCapabilityBus(
            Catalog(listOf(descriptor)),
            listOf(binding(descriptor)),
        )

        val result = bus.resolve(
            request = request(descriptor.capabilityId),
            permissions = AndroidPermissionSnapshot(emptySet()),
        )

        val missing = assertIs<AndroidCapabilityResolution.PermissionsMissing>(result)
        assertEquals(listOf(FILE_PERMISSION), missing.missing)
        assertFalse(missing.binding.executionAuthority)
        assertFalse(missing.binding.permissionGrantAuthority)
    }

    @Test
    fun request_contract_mismatch_is_explicit() = runTest {
        val descriptor = descriptor(
            CapabilityId("calendar.event.create"),
            "provider.calendar",
            inputs = setOf("title", "start"),
            outputs = setOf("event-id"),
        )
        val bus = AndroidCapabilityBus(
            Catalog(listOf(descriptor)),
            listOf(binding(descriptor)),
        )

        val result = bus.resolve(
            request = AndroidCapabilityRequest(
                capabilityId = descriptor.capabilityId,
                availableInputs = setOf("title"),
                requiredOutputs = setOf("event-id"),
                resource = "calendar://primary",
                scope = "calendar.event.create",
            ),
            permissions = AndroidPermissionSnapshot(setOf(FILE_PERMISSION)),
        )

        val mismatch = assertIs<AndroidCapabilityResolution.ContractMismatch>(result)
        assertEquals(listOf("provider.calendar"), mismatch.candidateProviderIds)
    }

    @Test
    fun exact_provider_request_never_falls_back_to_another_provider() = runTest {
        val capability = CapabilityId("share.prepare")
        val first = descriptor(capability, "provider.first")
        val second = descriptor(capability, "provider.second")
        val bus = AndroidCapabilityBus(
            Catalog(listOf(first, second)),
            listOf(binding(first), binding(second)),
        )

        val result = bus.resolve(
            request = request(capability).copy(providerId = "provider.missing"),
            permissions = AndroidPermissionSnapshot(setOf(FILE_PERMISSION)),
        )

        val unavailable = assertIs<AndroidCapabilityResolution.CapabilityUnavailable>(result)
        assertEquals(listOf("provider.first", "provider.second"), unavailable.candidateProviderIds)
    }

    @Test
    fun binding_identity_binds_policy_risk_permission_and_recovery_metadata() {
        val descriptor = descriptor(CapabilityId("app.intent.launch"), "provider.intent")
        val first = binding(descriptor)
        val changedRisk = first.copy(riskClass = AndroidCapabilityRiskClass.HIGH)
        val changedPermission = first.copy(
            permissions = listOf(
                AndroidPermissionRequirement(
                    AndroidPermissionKind.SPECIAL_ACCESS,
                    "notification-listener",
                )
            )
        )

        assertNotEquals(first.fingerprint(), changedRisk.fingerprint())
        assertNotEquals(first.fingerprint(), changedPermission.fingerprint())
        assertFalse(first.ownerPolicyAuthority)
    }

    @Test
    fun dynamic_reliability_change_does_not_invalidate_stable_binding_contract() = runTest {
        val capability = CapabilityId("file.read")
        val bound = descriptor(capability, "provider.file", reliability = 0.80)
        val live = bound.copy(reliability = 0.95)
        val bus = AndroidCapabilityBus(
            Catalog(listOf(live)),
            listOf(binding(bound)),
        )

        val result = bus.resolve(
            request = request(capability),
            permissions = AndroidPermissionSnapshot(setOf(FILE_PERMISSION)),
        )

        val ready = assertIs<AndroidCapabilityResolution.Ready>(result)
        assertEquals("provider.file", ready.plan.providerId)
    }

    @Test
    fun changed_provider_contract_cannot_reuse_stale_binding() = runTest {
        val capability = CapabilityId("file.read")
        val bound = descriptor(capability, "provider.file")
        val changed = bound.copy(
            contract = CapabilityContract(
                requiredInputs = setOf("query", "scope"),
                outputs = setOf("result"),
            )
        )
        val bus = AndroidCapabilityBus(
            Catalog(listOf(changed)),
            listOf(binding(bound)),
        )

        val result = bus.resolve(
            request = AndroidCapabilityRequest(
                capabilityId = capability,
                availableInputs = setOf("query", "scope"),
                requiredOutputs = setOf("result"),
                resource = "android://device",
                scope = capability.value,
            ),
            permissions = AndroidPermissionSnapshot(setOf(FILE_PERMISSION)),
        )

        assertIs<AndroidCapabilityResolution.CapabilityUnavailable>(result)
    }

    @Test
    fun unavailable_catalog_provider_is_never_resolved_from_stale_binding() = runTest {
        val descriptor = descriptor(CapabilityId("contact.resolve"), "provider.contact")
        val bus = AndroidCapabilityBus(
            Catalog(emptyList()),
            listOf(binding(descriptor)),
        )

        val result = bus.resolve(
            request = request(descriptor.capabilityId),
            permissions = AndroidPermissionSnapshot(setOf(FILE_PERMISSION)),
        )

        assertIs<AndroidCapabilityResolution.CapabilityUnavailable>(result)
    }

    private fun request(capabilityId: CapabilityId): AndroidCapabilityRequest =
        AndroidCapabilityRequest(
            capabilityId = capabilityId,
            availableInputs = setOf("query"),
            requiredOutputs = setOf("result"),
            resource = "android://device",
            scope = capabilityId.value,
        )

    private fun descriptor(
        capabilityId: CapabilityId,
        providerId: String,
        reliability: Double = 1.0,
        inputs: Set<String> = setOf("query"),
        outputs: Set<String> = setOf("result"),
    ): CapabilityDescriptor =
        CapabilityDescriptor(
            capabilityId = capabilityId,
            providerId = providerId,
            providerType = ProviderType.MODULE,
            contract = CapabilityContract(
                requiredInputs = inputs,
                outputs = outputs,
            ),
            state = ProviderState.ACTIVE,
            trustLevel = TrustLevel.SYSTEM,
            reliability = reliability,
        )

    private fun binding(
        descriptor: CapabilityDescriptor,
    ): AndroidCapabilityBinding =
        AndroidCapabilityBinding(
            descriptor = descriptor,
            providerVersion = "1",
            requiredOwnerEffect = OwnerEffectType.EXTERNAL_APP_HANDOFF,
            ownerScope = descriptor.capabilityId.value,
            permissions = listOf(FILE_PERMISSION),
            riskClass = AndroidCapabilityRiskClass.MEDIUM,
            reversibility = AndroidCapabilityReversibility.COMPENSATABLE,
            recoverySemantics = AndroidRecoverySemantics.MANUAL_REVIEW,
            expectedOutcomeContract = "result",
        )

    private class Catalog(
        private val providers: List<CapabilityDescriptor>,
    ) : CapabilityProviderCatalog {
        override suspend fun providersFor(
            capabilityId: CapabilityId,
            includeUnavailable: Boolean,
        ): List<CapabilityDescriptor> =
            providers.filter { it.capabilityId == capabilityId }
    }

    companion object {
        private val FILE_PERMISSION = AndroidPermissionRequirement(
            AndroidPermissionKind.RUNTIME_PERMISSION,
            "android.permission.READ_MEDIA_IMAGES",
        )
    }
}
