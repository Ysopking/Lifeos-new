package app.lifeos.core.runtime.boot

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BootRehydrationGraphTest {
    @Test
    fun `independent nodes start before either is released`() = runTest {
        val aStarted = CompletableDeferred<Unit>()
        val bStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val graph = BootRehydrationGraph(
            listOf(
                node("a") {
                    aStarted.complete(Unit)
                    release.await()
                },
                node("b") {
                    bStarted.complete(Unit)
                    release.await()
                },
            )
        )

        coroutineScope {
            val run = async { graph.rehydrate() }
            aStarted.await()
            bStarted.await()
            release.complete(Unit)
            run.await()
        }

        assertTrue(aStarted.isCompleted)
        assertTrue(bStarted.isCompleted)
    }

    @Test
    fun `dependencies form deterministic execution layers`() = runTest {
        val calls = mutableListOf<String>()
        val graph = BootRehydrationGraph(
            listOf(
                node("protection") { calls += "protection" },
                node("leases", setOf("protection")) { calls += "leases" },
                node("goal-plans", setOf("leases")) { calls += "goal-plans" },
                node("thought-graph", setOf("leases")) { calls += "thought-graph" },
                node("projection", setOf("thought-graph")) { calls += "projection" },
            )
        )

        assertEquals(
            listOf(
                listOf("protection"),
                listOf("leases"),
                listOf("goal-plans", "thought-graph"),
                listOf("projection"),
            ),
            graph.topologicalLayers().map { layer -> layer.map { it.value } },
        )

        graph.rehydrate()

        assertEquals("protection", calls.first())
        assertTrue(calls.indexOf("leases") > calls.indexOf("protection"))
        assertTrue(calls.indexOf("projection") > calls.indexOf("thought-graph"))
    }

    @Test
    fun `unknown dependency fails graph construction`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            BootRehydrationGraph(
                listOf(node("a", setOf("missing")) { Unit })
            )
        }
        assertTrue(failure.message.orEmpty().contains("unknown dependencies"))
    }

    @Test
    fun `cycle fails graph construction`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            BootRehydrationGraph(
                listOf(
                    node("a", setOf("b")) { Unit },
                    node("b", setOf("a")) { Unit },
                )
            )
        }
        assertTrue(failure.message.orEmpty().contains("cycle"))
    }

    @Test
    fun `parallel failures are attributed deterministically by node id`() = runTest {
        val graph = BootRehydrationGraph(
            listOf(
                node("zeta") { error("zeta-failure") },
                node("alpha") { error("alpha-failure") },
            )
        )

        val failure = assertFailsWith<BootRehydrationNodeFailure> {
            graph.rehydrate()
        }

        assertEquals(BootRehydrationNodeId("alpha"), failure.nodeId)
        assertTrue(failure.cause?.message.orEmpty().contains("alpha-failure"))
    }

    private fun node(
        id: String,
        dependencies: Set<String> = emptySet(),
        action: suspend () -> Unit,
    ): BootRehydrationNode = BootRehydrationNode(
        id = BootRehydrationNodeId(id),
        dependsOn = dependencies.mapTo(linkedSetOf(), ::BootRehydrationNodeId),
        action = action,
    )
}
