package app.lifeos.core.runtime.boot

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun `required degraded failure does not abort unrelated boot work`() = runTest {
        var independentRan = false
        val graph = BootRehydrationGraph(
            listOf(
                node("degraded", criticality = BootCriticality.REQUIRED_DEGRADED) {
                    error("degraded-failure")
                },
                node("independent", criticality = BootCriticality.REQUIRED_DEGRADED) {
                    independentRan = true
                },
            )
        )

        val report = graph.rehydrate()

        assertTrue(independentRan)
        assertEquals(
            listOf(BootRehydrationNodeId("degraded")),
            report.degradedNodeIds,
        )
        assertTrue(report.warmFailures.isEmpty())
    }

    @Test
    fun `optional warm failure blocks only its dependent warm path`() = runTest {
        var dependentRan = false
        var independentRan = false
        val graph = BootRehydrationGraph(
            listOf(
                node("warm-root", criticality = BootCriticality.OPTIONAL_WARM) {
                    error("warm-failure")
                },
                node(
                    "warm-dependent",
                    dependencies = setOf("warm-root"),
                    criticality = BootCriticality.OPTIONAL_WARM,
                ) {
                    dependentRan = true
                },
                node("independent", criticality = BootCriticality.OPTIONAL_WARM) {
                    independentRan = true
                },
            )
        )

        val report = graph.rehydrate()

        assertFalse(dependentRan)
        assertTrue(independentRan)
        assertEquals(
            listOf("warm-dependent", "warm-root"),
            report.warmFailureNodeIds.map { it.value },
        )
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
        criticality: BootCriticality = BootCriticality.SECURE_REQUIRED,
        action: suspend () -> Unit,
    ): BootRehydrationNode = BootRehydrationNode(
        id = BootRehydrationNodeId(id),
        dependsOn = dependencies.mapTo(linkedSetOf(), ::BootRehydrationNodeId),
        criticality = criticality,
        action = action,
    )
}
