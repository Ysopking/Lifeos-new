package app.lifeos.core.runtime.cognition

import app.lifeos.core.model.task.TaskId
import app.lifeos.core.model.task.TaskState
import app.lifeos.core.runtime.workers.CognitiveTaskExecutionResult
import app.lifeos.core.runtime.workers.DurableTaskExecutionObserver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CompositeDurableTaskExecutionObserverTest {
    private val result = CognitiveTaskExecutionResult(
        taskId = TaskId("task:observer"),
        photonId = null,
        finalState = TaskState.COMPLETED,
        influences = emptyList(),
        failures = emptyList(),
    )

    @Test
    fun secondaryObserverFailureCannotRewriteAuthoritativeResult() = runTest {
        val calls = mutableListOf<String>()
        val primary = recordingObserver("primary", calls)
        val brokenSecondary = object : DurableTaskExecutionObserver {
            override suspend fun onExecutionResult(result: CognitiveTaskExecutionResult) {
                calls += "secondary"
                error("telemetry failed")
            }
        }
        val trailingSecondary = recordingObserver("trailing", calls)
        val composite = CompositeDurableTaskExecutionObserver(
            listOf(primary, brokenSecondary, trailingSecondary),
        )

        composite.onExecutionResult(result)

        assertEquals(listOf("primary", "secondary", "trailing"), calls)
    }

    @Test
    fun authoritativeObserverFailureStillPropagatesAndStopsDerivedObservers() = runTest {
        val calls = mutableListOf<String>()
        val primary = object : DurableTaskExecutionObserver {
            override suspend fun onExecutionResult(result: CognitiveTaskExecutionResult) {
                calls += "primary"
                error("durable state bridge failed")
            }
        }
        val secondary = recordingObserver("secondary", calls)
        val composite = CompositeDurableTaskExecutionObserver(listOf(primary, secondary))

        assertFailsWith<IllegalStateException> {
            composite.onExecutionResult(result)
        }
        assertEquals(listOf("primary"), calls)
    }

    @Test
    fun cancellationFromSecondaryObserverAlwaysPropagates() = runTest {
        val calls = mutableListOf<String>()
        val primary = recordingObserver("primary", calls)
        val cancelledSecondary = object : DurableTaskExecutionObserver {
            override suspend fun onExecutionResult(result: CognitiveTaskExecutionResult) {
                calls += "secondary"
                throw CancellationException("stop")
            }
        }
        val trailing = recordingObserver("trailing", calls)
        val composite = CompositeDurableTaskExecutionObserver(
            listOf(primary, cancelledSecondary, trailing),
        )

        assertFailsWith<CancellationException> {
            composite.onExecutionResult(result)
        }
        assertEquals(listOf("primary", "secondary"), calls)
    }

    @Test
    fun compositeRequiresExactlyOneAuthoritativeFrontOfNonEmptyList() {
        assertFailsWith<IllegalArgumentException> {
            CompositeDurableTaskExecutionObserver(emptyList())
        }
    }

    private fun recordingObserver(
        name: String,
        calls: MutableList<String>,
    ) = object : DurableTaskExecutionObserver {
        override suspend fun onExecutionResult(result: CognitiveTaskExecutionResult) {
            calls += name
        }
    }
}
