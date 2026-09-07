package app.lifeos.core.runtime.capability

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CapabilityRouterTest {
    @Test
    fun selectsBestCompatibleProviderAndRespectsCostLimit() = runTest {
        val registry = CapabilityRegistry()
        val capability = CapabilityId("search.web")
        registry.register(
            CapabilityDescriptor(
                capabilityId = capability,
                providerId = "trusted",
                providerType = ProviderType.CONNECTOR,
                contract = CapabilityContract(requiredInputs = setOf("query"), outputs = setOf("results")),
                trustLevel = TrustLevel.HIGH,
                reliability = 0.99,
                cost = 2.0,
            )
        )
        registry.register(
            CapabilityDescriptor(
                capabilityId = capability,
                providerId = "cheap",
                providerType = ProviderType.CONNECTOR,
                contract = CapabilityContract(requiredInputs = setOf("query"), outputs = setOf("results")),
                trustLevel = TrustLevel.MEDIUM,
                reliability = 0.9,
                cost = 0.1,
            )
        )
        val requirement = CapabilityRequirement(
            capabilityId = capability,
            requiredInputs = setOf("query"),
            requiredOutputs = setOf("results"),
        )
        val router = CapabilityRouter(registry)

        assertEquals("cheap", router.route(requirement)?.providerId)
        assertEquals("cheap", router.route(requirement, CapabilityRoutingContext(maxCost = 0.5))?.providerId)
        assertNull(router.route(requirement, CapabilityRoutingContext(maxCost = 0.05)))
    }
}
