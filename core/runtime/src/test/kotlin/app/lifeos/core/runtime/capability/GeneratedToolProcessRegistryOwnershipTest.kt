package app.lifeos.core.runtime.capability

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertSame

class GeneratedToolProcessRegistryOwnershipTest {
    @AfterTest
    fun cleanup() {
        GeneratedToolRuntimeProcessRegistry.clearForTests()
    }

    @Test
    fun `planning capability registry cannot replace ready productive process registry`() {
        GeneratedToolRuntimeProcessRegistry.clearForTests()
        val productive = CapabilityRegistry()
        GeneratedToolRegistry()

        val planningOnly = CapabilityRegistry(
            listOf(
                CapabilityDescriptor(
                    capabilityId = CapabilityId("planning.only"),
                    providerId = "planning-only-provider",
                    providerType = ProviderType.MODULE,
                )
            )
        )

        assertSame(productive, GeneratedToolRuntimeProcessRegistry.capabilities())
        check(planningOnly !== productive)
    }
}
