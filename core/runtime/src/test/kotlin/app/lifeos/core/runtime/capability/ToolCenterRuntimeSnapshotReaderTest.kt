package app.lifeos.core.runtime.capability

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ToolCenterRuntimeSnapshotReaderTest {
    private val reader = ToolCenterRuntimeSnapshotReader()

    @Test
    fun `missing productive pair is reported as unavailable`() = runTest {
        val snapshot = reader.snapshot(capabilities = null, tools = null)

        assertEquals(ToolCenterRuntimeAvailability.UNAVAILABLE, snapshot.availability)
        assertTrue(snapshot.capabilityProviders.isEmpty())
        assertTrue(snapshot.generatedTools.isEmpty())
    }

    @Test
    fun `single productive registry is reported as partial`() = runTest {
        val capabilities = CapabilityRegistry(
            initialProviders = listOf(
                CapabilityDescriptor(
                    capabilityId = CapabilityId("memory.read"),
                    providerId = "memory-provider",
                    providerType = ProviderType.MODULE,
                )
            )
        )

        val snapshot = reader.snapshot(capabilities = capabilities, tools = null)

        assertEquals(ToolCenterRuntimeAvailability.PARTIAL, snapshot.availability)
        assertEquals(1, snapshot.capabilityCount)
        assertEquals("memory-provider", snapshot.capabilityProviders.single().providerId)
        assertTrue(snapshot.generatedTools.isEmpty())
    }

    @Test
    fun `ready snapshot includes unavailable providers and canonicalizes contracts`() = runTest {
        val capabilities = CapabilityRegistry(
            initialProviders = listOf(
                CapabilityDescriptor(
                    capabilityId = CapabilityId("search.web"),
                    providerId = "provider-z",
                    providerType = ProviderType.CONNECTOR,
                    contract = CapabilityContract(
                        requiredInputs = setOf("query", "locale"),
                        outputs = setOf("citations", "answer"),
                    ),
                    state = ProviderState.QUARANTINED,
                    trustLevel = TrustLevel.HIGH,
                    reliability = 0.75,
                    cost = 2.0,
                ),
                CapabilityDescriptor(
                    capabilityId = CapabilityId("memory.read"),
                    providerId = "provider-a",
                    providerType = ProviderType.MODULE,
                    state = ProviderState.ACTIVE,
                    trustLevel = TrustLevel.SYSTEM,
                ),
            )
        )
        val tools = GeneratedToolRegistry()

        val snapshot = reader.snapshot(capabilities = capabilities, tools = tools)

        assertEquals(ToolCenterRuntimeAvailability.READY, snapshot.availability)
        assertEquals(2, snapshot.capabilityCount)
        assertEquals(2, snapshot.providerCount)
        assertEquals(
            listOf("memory.read:provider-a", "search.web:provider-z"),
            snapshot.capabilityProviders.map { "${it.capabilityId}:${it.providerId}" },
        )
        val quarantined = snapshot.capabilityProviders.last()
        assertEquals(ProviderState.QUARANTINED, quarantined.state)
        assertEquals(listOf("locale", "query"), quarantined.requiredInputs)
        assertEquals(listOf("answer", "citations"), quarantined.outputs)
        assertTrue(snapshot.generatedTools.isEmpty())
    }
}
