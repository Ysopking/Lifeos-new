package app.lifeos.core.runtime.cognition

import app.lifeos.core.field.ConvergenceStatus
import app.lifeos.core.field.FieldDomainId
import app.lifeos.core.field.FieldRunId
import app.lifeos.core.field.FieldSnapshotId
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.task.TaskId
import app.lifeos.core.model.task.TaskState
import app.lifeos.core.runtime.field.FieldShadowExecution
import app.lifeos.core.runtime.field.FieldShadowState
import app.lifeos.core.runtime.workers.CognitiveTaskExecutionResult
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class CognitiveOutcomeFieldShadowTest {
    @Test
    fun `shadow provenance is journaled but does not create legacy triggers`() = runTest {
        val outcomes = InMemoryCognitiveOutcomeJournal()
        val triggers = InMemoryCognitiveTriggerSink()
        val recordedAt = Instant.parse("2026-09-08T12:00:00Z")
        val shadow = FieldShadowExecution(
            state = FieldShadowState.COMPLETED,
            domainId = FieldDomainId("domain:test"),
            runId = FieldRunId("run:test"),
            snapshotId = FieldSnapshotId("snapshot:test"),
            convergenceStatus = ConvergenceStatus.CONVERGED,
        )
        val observer = OutcomeTriggerObserver(
            outcomes = outcomes,
            triggers = triggers,
            now = { recordedAt },
        )

        observer.onExecutionResult(
            CognitiveTaskExecutionResult(
                taskId = TaskId("task-shadow-outcome"),
                photonId = PhotonId("photon-shadow-outcome"),
                finalState = TaskState.COMPLETED,
                influences = emptyList(),
                failures = emptyList(),
                fieldShadow = shadow,
            ),
        )

        assertEquals(shadow, outcomes.latest().single().fieldShadow)
        assertEquals(emptyList(), triggers.snapshot())
    }
}
