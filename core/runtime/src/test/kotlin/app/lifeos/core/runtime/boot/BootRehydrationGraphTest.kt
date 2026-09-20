package app.lifeos.core.runtime.boot

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BootRehydrationGraphTest {
    @Test
    fun `independent ready nodes execute concurrently before the next dependency wave`() = runTest {
        val first = BootRehydrationNodeId("first")
        val second = BootRehydrationNodeId("second")
        val dependent = BootRehydrationNodeId("dependent")
        val firstStarted = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()

        val graph = BootRehydrationGraph(
            nodes = listOf(
                BootRehydrationNode(first) {
                    firstStarted.complete(Unit)
                    release.await()
                    calls += "first"
                },
                BootRehydrationNode(second) {
                    secondStarted.complete(Unit)
                    release.await()
                    calls += "second"
                },
                BootRehydrationNode(
                    id = dependent,
                    dependsOn = setOf(first, second),
                ) {
                    calls += "dependent"
                },
            ),
            restoredState = { RehydratedRuntimeState(previousEpoch = 9) },
        )

        val run = async { graph.rehydrate() }
        firstStarted.await()
        secondStarted.await()
        assertTrue(calls.isEmpty())

        release.complete(Unit)
        val restored = run.await()

        assertEquals(9, restored.previousEpoch)
        assertEquals("dependent", calls.last())
        assertEquals(setOf("first", "second"), calls.take(2).toSet())
    }

    @Test
    fun `unknown dependencies duplicate ids and cycles fail before execution`() {
        val root = BootRehydrationNodeId("root")
        val missing = BootRehydrationNodeId("missing")

        assertFailsWith<IllegalArgumentException> {
            BootRehydrationGraph(
                listOf(
                    BootRehydrationNode(
                        id = root,
                        dependsOn = setOf(missing),
                    ) { Unit }
                )
            )
        }

        assertFailsWith<IllegalArgumentException> {
            BootRehydrationGraph(
                listOf(
                    BootRehydrationNode(root) { Unit },
                    BootRehydrationNode(root) { Unit },
                )
            )
        }

        val a = BootRehydrationNodeId("cycle-a")
        val b = BootRehydrationNodeId("cycle-b")
        assertFailsWith<IllegalArgumentException> {
            BootRehydrationGraph(
                listOf(
                    BootRehydrationNode(a, setOf(b)) { Unit },
                    BootRehydrationNode(b, setOf(a)) { Unit },
                )
            )
        }
    }

    @Test
    fun `parallel failures are attributed deterministically by node id`() = runTest {
        val graph = BootRehydrationGraph(
            nodes = listOf(
                BootRehydrationNode(BootRehydrationNodeId("node-b")) {
                    error("failure-b")
                },
                BootRehydrationNode(BootRehydrationNodeId("node-a")) {
                    error("failure-a")
                },
            )
        )

        val failure = try {
            graph.rehydrate()
            null
        } catch (error: BootRehydrationNodeException) {
            error
        }

        val typed = assertIs<BootRehydrationNodeException>(failure)
        assertEquals(BootRehydrationNodeId("node-a"), typed.nodeId)
        assertEquals("failure-a", typed.cause?.message)
    }
}
