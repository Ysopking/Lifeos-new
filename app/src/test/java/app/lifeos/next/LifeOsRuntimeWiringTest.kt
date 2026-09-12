package app.lifeos.next

import app.lifeos.core.runtime.topology.LifeOsProcessTopology
import app.lifeos.core.runtime.topology.LifeOsRuntimeBindingRegistry
import app.lifeos.core.runtime.topology.LifeOsRuntimeBindingState
import app.lifeos.core.runtime.topology.SubsystemId
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LifeOsRuntimeWiringTest {
    @BeforeTest
    fun reset() {
        LifeOsRuntimeBindingRegistry.clearForTests()
        LifeOsRuntimeWiring.clearForTests()
    }

    @Test
    fun `manifest owners bind only after dependencies are operational`() {
        LifeOsRuntimeWiring.onStageReady(evidence(LifeOsStartupStage.SHARED_RESOURCES))

        assertEquals(
            LifeOsRuntimeBindingState.ACTIVE,
            LifeOsRuntimeBindingRegistry.current(SubsystemId("owner-policy"))?.state,
        )
        assertEquals(
            LifeOsRuntimeBindingState.ACTIVE,
            LifeOsRuntimeBindingRegistry.current(SubsystemId("resource-budgets"))?.state,
        )
        assertNull(LifeOsRuntimeBindingRegistry.current(SubsystemId("decision-trace")))

        LifeOsRuntimeWiring.onStageReady(evidence(LifeOsStartupStage.KERNEL_GRAPH))

        assertEquals(
            LifeOsRuntimeBindingState.ACTIVE,
            LifeOsRuntimeBindingRegistry.current(SubsystemId("photon-store"))?.state,
        )
        assertEquals(
            LifeOsRuntimeBindingState.ACTIVE,
            LifeOsRuntimeBindingRegistry.current(SubsystemId("decision-trace"))?.state,
        )
        assertNull(LifeOsRuntimeBindingRegistry.current(SubsystemId("goal-resume")))
        assertNull(LifeOsRuntimeBindingRegistry.current(SubsystemId("build-studio")))
        assertTrue(
            LifeOsRuntimeBindingRegistry.current(SubsystemId("hot-swap-runtime"))?.state in setOf(
                LifeOsRuntimeBindingState.REGISTERED,
                LifeOsRuntimeBindingState.ACTIVE,
            )
        )

        LifeOsRuntimeWiring.onStageReady(evidence(LifeOsStartupStage.DURABLE_GOALS))

        assertEquals(
            LifeOsRuntimeBindingState.ACTIVE,
            LifeOsRuntimeBindingRegistry.current(SubsystemId("goal-planning"))?.state,
        )
        assertEquals(
            LifeOsRuntimeBindingState.ACTIVE,
            LifeOsRuntimeBindingRegistry.current(SubsystemId("goal-resume"))?.state,
        )
    }

    @Test
    fun `startup evidence from another manifest graph is rejected`() {
        val current = evidence(LifeOsStartupStage.SHARED_RESOURCES)
        val forged = current.copy(manifestGraphFingerprint = "different-manifest-graph")

        kotlin.test.assertFailsWith<IllegalArgumentException> {
            LifeOsRuntimeWiring.onStageReady(forged)
        }
    }

    private fun evidence(stage: LifeOsStartupStage): LifeOsStartupStageEvidence {
        val layerIndex = LifeOsStartupStageGraph.layers.indexOfFirst { layer ->
            layer.any { it.stage == stage }
        }
        val manifests = stage.subsystemOwner
            ?.let(LifeOsProcessTopology.canonicalManifestGraph::manifestsFor)
            .orEmpty()
        return LifeOsStartupStageEvidence(
            stage = stage,
            layerIndex = layerIndex,
            manifestGraphFingerprint = LifeOsProcessTopology.manifestFingerprint,
            ownedManifestFingerprints = manifests.map { it.fingerprint },
        )
    }
}
