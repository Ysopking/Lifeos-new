package app.lifeos.core.runtime.topology

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuntimeTopologyCoverageTest {
    @Test
    fun `all non kernel owners are explicit and singleton where required`() {
        val manifests = LifeOsProcessTopology.canonicalManifestGraph.topologicalOrder
        val byOwner = manifests.groupBy { it.startupOwner }
            .mapValues { (_, values) -> values.mapTo(linkedSetOf<String>()) { it.id.value } }

        assertTrue(
            byOwner[SubsystemStartupOwner.SHARED_RESOURCES] ==
                setOf("owner-policy", "resource-intelligence", "resource-budgets", "decision-trace")
        )
        assertTrue(byOwner[SubsystemStartupOwner.DEEP_SEARCH] == setOf("deep-search"))
        assertTrue(byOwner[SubsystemStartupOwner.SELF_HEALING] == setOf("self-healing"))
        assertTrue(byOwner[SubsystemStartupOwner.DURABLE_GOALS] == setOf("goal-planning"))
        assertTrue(byOwner[SubsystemStartupOwner.OPTIONAL_RUNTIME] == setOf("hot-swap-runtime"))
        assertTrue(byOwner[SubsystemStartupOwner.EXTERNAL_HOST] == setOf("build-studio"))

        val classified = byOwner.values.flatten().toSet()
        assertEquals(43, classified.size)
        assertTrue(
            classified == manifests.mapTo(linkedSetOf<String>()) { it.id.value }
        )
        assertTrue(byOwner[SubsystemStartupOwner.KERNEL_GRAPH].orEmpty().isNotEmpty())
    }
}
