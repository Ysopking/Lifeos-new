package app.lifeos.core.runtime.boot

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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

        val report = graph.rehydrate()

        assertFalse(report.degraded)
        assertEquals("protection", calls.first())
        assertTrue(calls.indexOf("leases") > calls.indexOf("protection"))
        assertTrue(calls.indexOf("projection") > calls.indexOf("thought-graph"))
    }

    @Test
    fun `required degraded failure does not abort boot and blocks dependents safely`() = runTest {
        var dependentRan = false
        val graph = BootRehydrationGraph(
            listOf(
                node(
                    id = "base",
                    criticality = BootCriticality.REQUIRED_DEGRADED,
                ) {
                    error("base-failure")
                },
                node(
                    id = "dependent",
                    dependencies = setOf("base"),
                    criticality = BootCriticality.OPTIONAL_WARM,
                ) {
                    dependentRan = true
                },
            )
        )

        val report = graph.rehydrate()

        assertFalse(dependentRan)
        assertEquals(
            listOf(BootRehydrationNodeId("base")),
            report.requiredDegradedFailures.map { it.nodeId },
        )
        assertEquals(
            listOf(BootRehydrationNodeId("dependent")),
            report.optionalWarmFailures.map { it.nodeId },
        )
        assertTrue(report.optionalWarmFailures.single().dependencyFailure)
    }

    @Test
    fun `optional warm failure is isolated from successful sibling`() = runTest {
        var requiredRan = false
        val graph = BootRehydrationGraph(
            listOf(
                node(
                    id = "warm",
                    criticality = BootCriticality.OPTIONAL_WARM,
                ) {
                    error("warm-failure")
                },
                node(
                    id = "required",
                    criticality = BootCriticality.REQUIRED_DEGRADED,
                ) {
                    requiredRan = true
                },
            )
        )

        val report = graph.rehydrate()

        assertTrue(requiredRan)
        assertEquals(setOf(BootRehydrationNodeId("required")), report.completed)
        assertTrue(report.requiredDegradedFailures.isEmpty())
        assertEquals(BootRehydrationNodeId("warm"), report.optionalWarmFailures.single().nodeId)
    }

    @Test
    fun `critical phase excludes optional warm nodes until explicit warm restore`() = runTest {
        val calls = mutableListOf<String>()
        val graph = BootRehydrationGraph(
            listOf(
                node("secure") { calls += "secure" },
                node(
                    id = "required",
                    dependencies = setOf("secure"),
                    criticality = BootCriticality.REQUIRED_DEGRADED,
                ) { calls += "required" },
                node(
                    id = "warm",
                    dependencies = setOf("required"),
                    criticality = BootCriticality.OPTIONAL_WARM,
                ) { calls += "warm" },
            )
        )

        val critical = graph.rehydrateCritical()
        assertEquals(listOf("secure", "required"), calls)
        val warm = graph.rehydrateWarm(critical)
        assertEquals(listOf("secure", "required", "warm"), calls)
        assertEquals(setOf(BootRehydrationNodeId("warm")), warm.completed)
    }

    @Test
    fun `warm phase inherits failed critical dependency without rerun`() = runTest {
        var warmRan = false
        val graph = BootRehydrationGraph(
            listOf(
                node(
                    id = "required",
                    criticality = BootCriticality.REQUIRED_DEGRADED,
                ) { error("required-failure") },
                node(
                    id = "warm",
                    dependencies = setOf("required"),
                    criticality = BootCriticality.OPTIONAL_WARM,
                ) { warmRan = true },
            )
        )

        val critical = graph.rehydrateCritical()
        val warm = graph.rehydrateWarm(critical)

        assertFalse(warmRan)
        assertEquals(BootRehydrationNodeId("warm"), warm.optionalWarmFailures.single().nodeId)
        assertTrue(warm.optionalWarmFailures.single().dependencyFailure)
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
    fun `secure parallel failures are attributed deterministically by node id`() = runTest {
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
        assertEquals(BootCriticality.SECURE_REQUIRED, failure.criticality)
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
