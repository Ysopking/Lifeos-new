package app.lifeos.core.runtime.topology

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SubsystemManifestGraphTest {
    @AfterTest
    fun clearBindings() {
        LifeOsRuntimeBindingRegistry.clearForTests()
    }

    @Test
    fun `duplicate subsystem id is rejected`() {
        val duplicate = manifest("duplicate")
        assertFailsWith<IllegalArgumentException> {
            SubsystemManifestGraph(listOf(duplicate, duplicate.copy(version = "2")))
        }
    }

    @Test
    fun `missing dependency is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            SubsystemManifestGraph(
                listOf(manifest("child", dependencies = setOf(SubsystemId("missing"))))
            )
        }
    }

    @Test
    fun `self dependency is rejected`() {
        val id = SubsystemId("self")
        assertFailsWith<IllegalArgumentException> {
            SubsystemManifest(
                id = id,
                dependencies = setOf(id),
                startupOwner = SubsystemStartupOwner.KERNEL_GRAPH,
            )
        }
    }

    @Test
    fun `cyclic dependency is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            SubsystemManifestGraph(
                listOf(
                    manifest("alpha", dependencies = setOf(SubsystemId("beta"))),
                    manifest("beta", dependencies = setOf(SubsystemId("alpha"))),
                )
            )
        }
    }

    @Test
    fun `layers and fingerprint are deterministic regardless of declaration order`() {
        val alpha = manifest("alpha")
        val beta = manifest("beta")
        val child = manifest(
            "child",
            dependencies = setOf(alpha.id, beta.id),
        )
        val first = SubsystemManifestGraph(listOf(child, beta, alpha))
        val second = SubsystemManifestGraph(listOf(alpha, child, beta))

        assertEquals(
            listOf(listOf("alpha", "beta"), listOf("child")),
            first.startupLayers.map { layer -> layer.map { it.id.value } },
        )
        assertEquals(first.startupLayers, second.startupLayers)
        assertEquals(first.topologicalOrder, second.topologicalOrder)
        assertEquals(first.fingerprint, second.fingerprint)
    }

    @Test
    fun `manual binding cannot register unknown subsystem`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            LifeOsRuntimeBindingRegistry.install(
                subsystemId = "not-a-lifeos-subsystem",
                source = "test",
            )
        }
        assertTrue(failure.message.orEmpty().contains("Unknown LIFEOS subsystem"))
    }

    private fun manifest(
        id: String,
        dependencies: Set<SubsystemId> = emptySet(),
    ) = SubsystemManifest(
        id = SubsystemId(id),
        dependencies = dependencies,
        startupOwner = SubsystemStartupOwner.KERNEL_GRAPH,
    )
}
