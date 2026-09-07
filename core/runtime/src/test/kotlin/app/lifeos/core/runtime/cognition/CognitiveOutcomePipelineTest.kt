package app.lifeos.core.runtime.cognition

import app.lifeos.core.model.FieldInfluence
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.task.TaskId
import app.lifeos.core.model.task.TaskState
import app.lifeos.core.runtime.RuntimeFailure
import app.lifeos.core.runtime.RuntimeFailureCategory
import app.lifeos.core.runtime.workers.CognitiveTaskExecutionResult
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CognitiveOutcomePipelineTest {
    private val t0 = Instant.parse("2026-09-07T19:00:00Z")

    @Test
    fun completedHighConfidenceOutcomeProducesReevaluationTrigger() = runTest {
        val outcomes = InMemoryCognitiveOutcomeJournal()
        val triggers = InMemoryCognitiveTriggerSink()
        val observer = OutcomeTriggerObserver(outcomes, triggers, now = { t0 })
        val photonId = PhotonId("photon-1")

        observer.onExecutionResult(
            CognitiveTaskExecutionResult(
                taskId = TaskId("task-1"),
                photonId = photonId,
                finalState = TaskState.COMPLETED,
                influences = listOf(
                    FieldInfluence(
                        module = "Gedankenmatrix",
                        photonId = photonId,
                        type = "INDEX",
                        deltaEnergy = 1.0,
                        confidence = 0.95,
                        explanation = "indexed",
                        occurredAt = t0,
                    )
                ),
                failures = emptyList(),
            )
        )

        assertEquals(1, outcomes.latest().size)
        val emitted = triggers.snapshot()
        assertEquals(1, emitted.size)
        assertEquals(CognitiveTriggerType.REEVALUATE, emitted.single().type)
        assertEquals(photonId, emitted.single().photonId)
    }

    @Test
    fun nonRecoverableFailureProducesQuarantineReview() = runTest {
        val outcomes = InMemoryCognitiveOutcomeJournal()
        val triggers = InMemoryCognitiveTriggerSink()
        val observer = OutcomeTriggerObserver(outcomes, triggers, now = { t0 })

        observer.onExecutionResult(
            CognitiveTaskExecutionResult(
                taskId = TaskId("task-failed"),
                photonId = null,
                finalState = TaskState.FAILED,
                influences = emptyList(),
                failures = listOf(
                    RuntimeFailure(
                        category = RuntimeFailureCategory.INVARIANT,
                        source = "test",
                        message = "broken invariant",
                        recoverable = false,
                    )
                ),
            )
        )

        val trigger = triggers.snapshot().single()
        assertEquals(CognitiveTriggerType.QUARANTINE_REVIEW, trigger.type)
        assertTrue(trigger.reason.contains("non-recoverable"))
    }

    @Test
    fun triggerSinkIsIdempotentForDeterministicPolicyIds() = runTest {
        val outcomes = InMemoryCognitiveOutcomeJournal()
        val triggers = InMemoryCognitiveTriggerSink()
        val observer = OutcomeTriggerObserver(outcomes, triggers, now = { t0 })
        val result = CognitiveTaskExecutionResult(
            taskId = TaskId("task-repeat"),
            photonId = null,
            finalState = TaskState.FAILED,
            influences = emptyList(),
            failures = listOf(
                RuntimeFailure(
                    category = RuntimeFailureCategory.TIMEOUT,
                    source = "test",
                    message = "timeout",
                    recoverable = true,
                )
            ),
        )

        observer.onExecutionResult(result)
        observer.onExecutionResult(result)

        assertEquals(2, outcomes.latest().size)
        assertEquals(1, triggers.snapshot().size)
        assertEquals(CognitiveTriggerType.RECOVERY, triggers.snapshot().single().type)
    }
}
