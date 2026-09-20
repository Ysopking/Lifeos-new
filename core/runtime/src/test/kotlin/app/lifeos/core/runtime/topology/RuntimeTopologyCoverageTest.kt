package app.lifeos.core.runtime.topology

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuntimeTopologyCoverageTest {
    @Test
    fun `all non kernel owners are explicit and singleton where required`() {
        val manifests = LifeOsProcessTopology.canonicalManifestGraph.topologicalOrder
        val byOwner = manifests.groupBy { it.startupOwner }
            .mapValues { (_, values) -> values.mapTo(linkedSetOf()) { it.id.value } }

        assertEquals(
            setOf("owner-policy", "resource-intelligence", "resource-budgets", "decision-trace"),
            byOwner[SubsystemStartupOwner.SHARED_RESOURCES],
        )
        assertEquals(setOf("deep-search"), byOwner[SubsystemStartupOwner.DEEP_SEARCH])
        assertEquals(setOf("self-healing"), byOwner[SubsystemStartupOwner.SELF_HEALING])
        assertEquals(setOf("goal-planning"), byOwner[SubsystemStartupOwner.DURABLE_GOALS])
        assertEquals(setOf("hot-swap-runtime"), byOwner[SubsystemStartupOwner.OPTIONAL_RUNTIME])
        assertEquals(setOf("build-studio"), byOwner[SubsystemStartupOwner.EXTERNAL_HOST])

        val classified = byOwner.values.flatten().toSet()
        assertEquals(43, classified.size)
        assertEquals(
            manifests.mapTo(linkedSetOf()) { it.id.value },
            classified,
        )
        assertTrue(byOwner[SubsystemStartupOwner.KERNEL_GRAPH].orEmpty().isNotEmpty())
    }
}
