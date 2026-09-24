package app.lifeos.core.runtime.android

import app.lifeos.core.runtime.capability.CapabilityContract
import app.lifeos.core.runtime.capability.CapabilityId
import app.lifeos.core.runtime.capability.CapabilityRegistry
import app.lifeos.core.runtime.capability.ProviderType
import app.lifeos.core.runtime.capability.TrustLevel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class UniversalAppCapabilityRegistryTest {
    @Test
    fun validatedCandidateUsesCanonicalRegistry() = runTest {
        val registry = CapabilityRegistry()
        val adapter = UniversalAppCapabilityRegistryAdapter(registry)
        val candidate = candidate(AppCapabilityInterfaceKind.INTENT)

        val descriptor = adapter.promoteValidated(candidate, evidence(candidate))

        assertEquals(ProviderType.CONNECTOR, descriptor.providerType)
        assertEquals(TrustLevel.LOW, descriptor.trustLevel)
        assertEquals(
            listOf(descriptor),
            registry.providersFor(candidate.capabilityId, includeUnavailable = true),
        )
        assertEquals(listOf(descriptor), adapter.current(candidate.capabilityId))
    }

    @Test
    fun evidenceCannotAuthorizeChangedProviderVersion() = runTest {
        val registry = CapabilityRegistry()
        val adapter = UniversalAppCapabilityRegistryAdapter(registry)
        val original = candidate(AppCapabilityInterfaceKind.INTENT)
        val changed = original.copy(providerVersion = "2")

        assertFailsWith<IllegalArgumentException> {
            adapter.promoteValidated(changed, evidence(original))
        }
    }

    @Test
    fun registrationEvidenceHasNoEffectAuthority() {
        val candidate = candidate(AppCapabilityInterfaceKind.OFFICIAL_API)
        val evidence = evidence(candidate)

        assertFalse(candidate.activationAuthority)
        assertFalse(candidate.executionAuthority)
        assertFalse(candidate.ownerPolicyAuthority)
        assertFalse(evidence.effectAuthority)
    }

    @Test
    fun officialInterfaceStartsAboveUiFallbackTrust() = runTest {
        val official = candidate(AppCapabilityInterfaceKind.OFFICIAL_API)
        val officialDescriptor = UniversalAppCapabilityRegistryAdapter(CapabilityRegistry())
            .promoteValidated(official, evidence(official))
        val fallback = candidate(AppCapabilityInterfaceKind.ACCESSIBILITY_SEMANTIC)
        val fallbackDescriptor = UniversalAppCapabilityRegistryAdapter(CapabilityRegistry())
            .promoteValidated(fallback, evidence(fallback))

        assertEquals(TrustLevel.MEDIUM, officialDescriptor.trustLevel)
        assertEquals(TrustLevel.LOW, fallbackDescriptor.trustLevel)
    }

    private fun candidate(
        kind: AppCapabilityInterfaceKind,
    ) = UniversalAppCapabilityCandidate(
        capabilityId = CapabilityId("communication.message.open"),
        providerId = "android:example.app",
        providerVersion = "1",
        interfaceKind = kind,
        contract = CapabilityContract(
            requiredInputs = setOf("conversation-id"),
            outputs = setOf("external-app-open"),
        ),
        sourceFingerprint = "a".repeat(64),
        discoveryRuleFingerprint = "b".repeat(64),
        reliability = 0.8,
        cost = 0.1,
    )

    private fun evidence(
        candidate: UniversalAppCapabilityCandidate,
    ) = AppCapabilityValidationEvidence(
        candidateFingerprint = candidate.fingerprint,
        shadowTestFingerprint = "c".repeat(64),
        contractTestFingerprint = "d".repeat(64),
        validatedProviderVersion = candidate.providerVersion,
    )
}
