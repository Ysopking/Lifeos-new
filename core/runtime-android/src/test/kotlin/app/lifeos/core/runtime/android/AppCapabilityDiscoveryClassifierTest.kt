package app.lifeos.core.runtime.android

import app.lifeos.core.runtime.capability.CapabilityContract
import app.lifeos.core.runtime.capability.CapabilityId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppCapabilityDiscoveryClassifierTest {
    private val rule = AppCapabilityDiscoveryRule(
        ruleId = "calendar-read/v1",
        capabilityId = CapabilityId("calendar.event.read"),
        acceptedInterfaces = setOf(
            AppCapabilityInterfaceKind.OFFICIAL_API,
            AppCapabilityInterfaceKind.CONTENT_PROVIDER,
        ),
        requiredSemanticContracts = setOf("calendar:event:read"),
        capabilityContract = CapabilityContract(
            requiredInputs = setOf("calendar-id"),
            outputs = setOf("calendar-event-observation"),
        ),
        reliability = 0.9,
        cost = 0.1,
    )

    @Test
    fun explicitSurfaceEvidenceProducesShadowCandidateOnly() {
        val evidence = AppSurfaceEvidence(
            providerId = "android:calendar.example",
            providerVersion = "10",
            interfaceKind = AppCapabilityInterfaceKind.CONTENT_PROVIDER,
            surfaceKey = "calendar-provider",
            semanticContracts = setOf("calendar:event:read"),
            sourceFingerprint = "a".repeat(64),
        )

        val candidate =
            AppCapabilityDiscoveryClassifier(listOf(rule))
                .classify(listOf(evidence))
                .single()

        assertEquals("calendar.event.read", candidate.capabilityId.value)
        assertEquals("android:calendar.example", candidate.providerId)
        assertFalse(candidate.activationAuthority)
        assertFalse(candidate.executionAuthority)
        assertFalse(candidate.ownerPolicyAuthority)
        assertFalse(evidence.platformInspectionAuthority)
    }

    @Test
    fun missingSemanticContractDoesNotInferCapability() {
        val evidence = AppSurfaceEvidence(
            providerId = "android:calendar.example",
            providerVersion = "10",
            interfaceKind = AppCapabilityInterfaceKind.CONTENT_PROVIDER,
            surfaceKey = "calendar-provider",
            semanticContracts = setOf("calendar:declared"),
            sourceFingerprint = "b".repeat(64),
        )

        assertTrue(
            AppCapabilityDiscoveryClassifier(listOf(rule))
                .classify(listOf(evidence))
                .isEmpty()
        )
    }

    @Test
    fun classificationIsStableAcrossEvidenceOrder() {
        val official = AppSurfaceEvidence(
            providerId = "android:calendar.example",
            providerVersion = "10",
            interfaceKind = AppCapabilityInterfaceKind.OFFICIAL_API,
            surfaceKey = "official",
            semanticContracts = setOf("calendar:event:read"),
            sourceFingerprint = "c".repeat(64),
        )
        val provider = AppSurfaceEvidence(
            providerId = "android:calendar.example",
            providerVersion = "10",
            interfaceKind = AppCapabilityInterfaceKind.CONTENT_PROVIDER,
            surfaceKey = "provider",
            semanticContracts = setOf("calendar:event:read"),
            sourceFingerprint = "d".repeat(64),
        )
        val classifier = AppCapabilityDiscoveryClassifier(listOf(rule))

        val first = classifier.classify(listOf(provider, official))
        val second = classifier.classify(listOf(official, provider))

        assertEquals(first, second)
        assertEquals(
            AppCapabilityInterfaceKind.OFFICIAL_API,
            first.first().interfaceKind,
        )
    }
}
