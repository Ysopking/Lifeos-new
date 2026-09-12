package app.lifeos.core.runtime.topology

import app.lifeos.core.runtime.capability.CapabilityContract
import app.lifeos.core.runtime.capability.CapabilityDescriptor
import app.lifeos.core.runtime.capability.CapabilityId
import app.lifeos.core.runtime.capability.CapabilityRegistry
import app.lifeos.core.runtime.capability.GeneratedToolRuntimeProcessRegistry
import app.lifeos.core.runtime.capability.ProviderState
import app.lifeos.core.runtime.capability.ProviderType
import app.lifeos.core.runtime.capability.TrustLevel
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LifeOsRuntimeTopologyTest {
    @BeforeTest
    fun resetProcessState() {
        LifeOsRuntimeBindingRegistry.clearForTests()
        GeneratedToolRuntimeProcessRegistry.clearForTests()
    }

    @Test
    fun canonicalInventoryNeverShrinksBelowEstablishedBaseline() {
        val descriptors = LifeOsProcessTopology.canonicalSubsystems
        assertTrue(descriptors.size >= LifeOsProcessTopology.MINIMUM_CANONICAL_SUBSYSTEMS)
        assertEquals(descriptors.size, descriptors.map { it.id }.distinct().size)
        val ids = descriptors.mapTo(linkedSetOf()) { it.id }
        assertTrue(descriptors.all { ids.containsAll(it.dependencies) })
    }

    @Test
    fun processSnapshotCombinesRuntimeBindingAndLiveCapabilityAvailability() = runTest {
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
        LifeOsRuntimeBindingRegistry.install(
            subsystemId = "language-understanding",
            source = "test",
        )

        val snapshot = assertNotNull(LifeOsProcessTopology.snapshot())
        assertTrue(snapshot.registeredSubsystemCount >= LifeOsProcessTopology.MINIMUM_CANONICAL_SUBSYSTEMS)
        assertEquals(
            LifeOsSubsystemState.ACTIVE,
            snapshot.subsystems.single { it.descriptor.id == "language-understanding" }.state,
        )
        assertEquals(
            LifeOsSubsystemState.UNAVAILABLE,
            snapshot.subsystems.single { it.descriptor.id == "deep-search" }.state,
        )
    }

    @Test
    fun missingDependencyPropagatesUnavailableState() = runTest {
        CapabilityRegistry()
        LifeOsRuntimeBindingRegistry.install(
            subsystemId = "thought-graph",
            source = "test",
        )

        val snapshot = assertNotNull(LifeOsProcessTopology.snapshot())
        val thoughtGraph = snapshot.subsystems.single { it.descriptor.id == "thought-graph" }
        assertEquals(LifeOsSubsystemState.UNAVAILABLE, thoughtGraph.state)
        assertEquals(setOf("photon-store"), thoughtGraph.unavailableDependencies)
    }
}
