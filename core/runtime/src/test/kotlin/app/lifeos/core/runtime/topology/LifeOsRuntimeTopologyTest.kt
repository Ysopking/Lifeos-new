package app.lifeos.core.runtime.topology

import app.lifeos.core.runtime.capability.CapabilityContract
import app.lifeos.core.runtime.capability.CapabilityDescriptor
import app.lifeos.core.runtime.capability.CapabilityId
import app.lifeos.core.runtime.capability.CapabilityRegistry
import app.lifeos.core.runtime.capability.ProviderState
import app.lifeos.core.runtime.capability.ProviderType
import app.lifeos.core.runtime.capability.TrustLevel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class LifeOsRuntimeTopologyTest {
    @Test
    fun canonicalInventoryContains36UniqueSubsystems() {
        val descriptors = LifeOsProcessTopology.canonicalSubsystems
        assertEquals(36, descriptors.size)
        assertEquals(36, descriptors.map { it.id }.distinct().size)
    }

    @Test
    fun processSnapshotProjectsLiveCapabilityAvailability() = runTest {
        CapabilityRegistry(
            listOf(
                CapabilityDescriptor(
                    capabilityId = CapabilityId("language.understand"),
                    providerId = "language-core",
                    providerType = ProviderType.MODULE,
                    contract = CapabilityContract(
                        requiredInputs = setOf("chat-photon"),
                        outputs = setOf("goal-photon"),
                    ),
                    state = ProviderState.ACTIVE,
                    trustLevel = TrustLevel.SYSTEM,
                    reliability = 1.0,
                    cost = 0.0,
                )
            )
        )

        val snapshot = assertNotNull(LifeOsProcessTopology.snapshot())
        assertEquals(36, snapshot.registeredSubsystemCount)
        assertEquals(
            LifeOsSubsystemState.ACTIVE,
            snapshot.subsystems.single { it.descriptor.id == "language-understanding" }.state,
        )
        assertEquals(
            LifeOsSubsystemState.UNAVAILABLE,
            snapshot.subsystems.single { it.descriptor.id == "deep-search" }.state,
        )
    }
}
