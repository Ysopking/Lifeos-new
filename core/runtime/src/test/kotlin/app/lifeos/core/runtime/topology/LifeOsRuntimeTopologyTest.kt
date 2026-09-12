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
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LifeOsRuntimeTopologyTest {
    @BeforeTest
    fun resetProcessState() {
        LifeOsRuntimeBindingRegistry.clearForTests()
        GeneratedToolRuntimeProcessRegistry.clearForTests()
    }

    @Test
    fun canonicalInventoryIsPreservedByTypedManifestGraph() {
        val expected = setOf(
            "photon-store",
            "binary-asset-store",
            "thought-matrix",
            "thought-graph",
            "field-runtime",
            "field-thought-graph-projection",
            "world-formula",
            "language-understanding",
            "language-context",
            "capability-registry",
            "capability-router",
            "owner-policy",
            "resource-intelligence",
            "resource-budgets",
            "decision-trace",
            "goal-planning",
            "goal-resume",
            "local-knowledge",
            "deep-search",
            "scene-compiler",
            "scene-rasterizer",
            "image-renderer",
            "image-transform",
            "reminder-scheduler",
            "communication",
            "durable-task-engine",
            "continuous-cognition",
            "cognition-reconciler",
            "cognition-outcome-pipeline",
            "cognitive-worker",
            "task-scheduler",
            "lease-recovery",
            "runtime-supervisor",
            "health-graph",
            "protection-coordinator",
            "self-healing",
            "tool-workshop",
            "generated-tool-registry",
            "autonomous-tool-workshop",
            "evolution-hot-swap",
            "hot-swap-runtime",
            "learning-adaptation",
            "build-studio",
        )
        val manifests = LifeOsProcessTopology.canonicalManifestGraph.topologicalOrder

        assertEquals(43, manifests.size)
        assertEquals(expected, manifests.mapTo(linkedSetOf()) { it.id.value })
        assertEquals(expected, LifeOsProcessTopology.canonicalSubsystems.mapTo(linkedSetOf()) { it.id })
        assertTrue(LifeOsProcessTopology.manifestFingerprint.isNotBlank())
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
        assertEquals(LifeOsProcessTopology.manifestFingerprint, snapshot.manifestFingerprint)
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

    @Test
    fun degradedDependencyPropagatesDegradedState() = runTest {
        CapabilityRegistry()
        LifeOsRuntimeBindingRegistry.install(
            subsystemId = "photon-store",
            state = LifeOsRuntimeBindingState.DEGRADED,
            source = "test",
        )
        LifeOsRuntimeBindingRegistry.install(
            subsystemId = "thought-graph",
            source = "test",
        )

        val snapshot = assertNotNull(LifeOsProcessTopology.snapshot())
        val thoughtGraph = snapshot.subsystems.single { it.descriptor.id == "thought-graph" }
        assertEquals(LifeOsSubsystemState.DEGRADED, thoughtGraph.state)
        assertTrue(thoughtGraph.unavailableDependencies.isEmpty())
    }

    @Test
    fun optionalRuntimesCannotAppearActiveWithoutBindingAndCapabilityTruth() = runTest {
        CapabilityRegistry()

        val snapshot = assertNotNull(LifeOsProcessTopology.snapshot())
        val hotSwap = snapshot.subsystems.single { it.descriptor.id == "hot-swap-runtime" }
        val buildStudio = snapshot.subsystems.single { it.descriptor.id == "build-studio" }

        assertNotEquals(LifeOsSubsystemState.ACTIVE, hotSwap.state)
        assertNotEquals(LifeOsSubsystemState.ACTIVE, buildStudio.state)
        assertTrue(buildStudio.unavailableCapabilities.contains("buildstudio.run"))
    }
}
