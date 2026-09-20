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
        val owned: (SubsystemStartupOwner) -> Set<String> = { owner ->
            byOwner[owner]?.toSet().orEmpty()
        }

        assertEquals(
            setOf("owner-policy", "resource-intelligence", "resource-budgets", "decision-trace"),
            owned(SubsystemStartupOwner.SHARED_RESOURCES),
        )
        assertEquals(setOf("deep-search"), owned(SubsystemStartupOwner.DEEP_SEARCH))
        assertEquals(setOf("self-healing"), owned(SubsystemStartupOwner.SELF_HEALING))
        assertEquals(setOf("goal-planning"), owned(SubsystemStartupOwner.DURABLE_GOALS))
        assertEquals(setOf("hot-swap-runtime"), owned(SubsystemStartupOwner.OPTIONAL_RUNTIME))
        assertEquals(setOf("build-studio"), owned(SubsystemStartupOwner.EXTERNAL_HOST))

        val classified = byOwner.values.flatten().toSet()
        assertEquals(43, classified.size)
        assertEquals(
            manifests.mapTo(linkedSetOf<String>()) { it.id.value },
            classified,
        )
        assertTrue(owned(SubsystemStartupOwner.KERNEL_GRAPH).isNotEmpty())
    }
}
