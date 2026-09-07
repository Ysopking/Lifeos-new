package app.lifeos.core.runtime.capability

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class CapabilityFoundationTest {
    @Test
    fun detectsMissingUnhealthyAndContractMismatchCapabilities() = runTest {
        val registry = CapabilityRegistry()
        val detector = CapabilityGapDetector(registry)
        val capability = CapabilityId("document.compare")

        val missing = detector.detect(CapabilityRequirement(capability))
        assertEquals(CapabilityGapType.CAPABILITY_MISSING, missing?.type)

        registry.register(
            CapabilityDescriptor(
                capabilityId = capability,
                providerId = "provider-1",
                providerType = ProviderType.MODULE,
                state = ProviderState.QUARANTINED,
            )
        )
        val unhealthy = detector.detect(CapabilityRequirement(capability))
        assertEquals(CapabilityGapType.PROVIDER_UNHEALTHY, unhealthy?.type)

        registry.register(
            CapabilityDescriptor(
                capabilityId = capability,
                providerId = "provider-2",
                providerType = ProviderType.MODULE,
                contract = CapabilityContract(
                    requiredInputs = setOf("text"),
                    outputs = setOf("comparison"),
                ),
            )
        )
        val mismatch = detector.detect(
            CapabilityRequirement(
                capabilityId = capability,
                requiredInputs = setOf("pdf"),
                requiredOutputs = setOf("report"),
            )
        )
        assertEquals(CapabilityGapType.CONTRACT_MISMATCH, mismatch?.type)

        assertNull(
            detector.detect(
                CapabilityRequirement(
                    capabilityId = capability,
                    requiredInputs = setOf("text"),
                    requiredOutputs = setOf("comparison"),
                )
            )
        )
    }

    @Test
    fun composesExistingProvidersBeforeGenesis() = runTest {
        val registry = CapabilityRegistry()
        registry.register(provider("read", setOf("file"), setOf("text"), 0.99))
        registry.register(provider("compare", setOf("text"), setOf("comparison"), 0.98))
        registry.register(provider("report", setOf("comparison"), setOf("report"), 0.97))

        val plan = CapabilityComposer(registry).compose(
            availableContracts = setOf("file"),
            requiredOutputs = setOf("report"),
        )

        val resolved = assertIs<CompositeCapabilityPlan>(plan)
        assertEquals(listOf("read", "compare", "report"), resolved.steps.map { it.providerId })
        assertEquals(setOf("file", "text", "comparison", "report"), resolved.resultingContracts)
    }

    private fun provider(
        id: String,
        inputs: Set<String>,
        outputs: Set<String>,
        reliability: Double,
    ) = CapabilityDescriptor(
        capabilityId = CapabilityId("capability.$id"),
        providerId = id,
        providerType = ProviderType.MODULE,
        contract = CapabilityContract(inputs, outputs),
        reliability = reliability,
    )
}
