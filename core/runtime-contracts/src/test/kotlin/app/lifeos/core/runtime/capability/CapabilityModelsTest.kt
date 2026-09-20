package app.lifeos.core.runtime.capability

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CapabilityModelsTest {
    @Test
    fun `capability identity and descriptor validation stay contract local`() {
        val id = CapabilityId("deepsearch.query")
        val descriptor = CapabilityDescriptor(
            capabilityId = id,
            providerId = "provider:local",
            providerType = ProviderType.MODULE,
            state = ProviderState.ACTIVE,
            trustLevel = TrustLevel.HIGH,
            reliability = 0.9,
            cost = 0.25,
        )

        assertEquals("deepsearch.query", id.value)
        assertEquals(id, descriptor.capabilityId)
        assertEquals(ProviderState.ACTIVE, descriptor.state)
    }

    @Test
    fun `invalid contract values fail closed`() {
        assertFailsWith<IllegalArgumentException> { CapabilityId(" ") }
        assertFailsWith<IllegalArgumentException> {
            CapabilityDescriptor(
                capabilityId = CapabilityId("x"),
                providerId = "",
                providerType = ProviderType.MODULE,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            CapabilityDescriptor(
                capabilityId = CapabilityId("x"),
                providerId = "p",
                providerType = ProviderType.MODULE,
                reliability = 1.01,
            )
        }
    }
}
