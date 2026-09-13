package app.lifeos.next.ui.decision

import app.lifeos.core.runtime.trace.DecisionTrace
import app.lifeos.core.runtime.trace.DecisionTraceId
import app.lifeos.core.runtime.trace.DecisionTraceLink
import app.lifeos.core.runtime.trace.DecisionTraceLinkType
import app.lifeos.core.runtime.trace.DecisionTraceNode
import app.lifeos.core.runtime.trace.DecisionTraceNodeType
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DecisionTraceProjectorTest {
    @Test
    fun `goal trace keeps durable reasons links and timeline visible`() {
        val goal = node(
            type = DecisionTraceNodeType.OBSERVED_FACT,
            sourceType = "goal-photon",
            sourceId = "goal-7",
            at = EARLY,
        )
        val constraint = DecisionTraceNode.create(
            type = DecisionTraceNodeType.POLICY_CONSTRAINT,
            sourceType = "owner-policy-decision",
            sourceId = "policy-7",
            sourceRevision = 3,
            reasonCodes = listOf("EFFECT_NOT_GRANTED"),
            recordedAt = MIDDLE,
        )
        val selection = DecisionTraceNode.create(
            type = DecisionTraceNodeType.SELECTION,
            sourceType = "capability-provider-selection",
            sourceId = "search:local-provider",
            sourceRevision = 0,
            reasonCodes = listOf("CAPABILITY_search", "TRUST_OWNER_APPROVED"),
            recordedAt = LATE,
        )
        val trace = DecisionTrace(
            id = DecisionTraceId.create("goal-photon", "goal-7"),
            revision = 3,
            nodes = listOf(selection, goal, constraint),
            links = listOf(
                DecisionTraceLink(constraint.id, goal.id, DecisionTraceLinkType.CONSTRAINS),
                DecisionTraceLink(selection.id, goal.id, DecisionTraceLinkType.SUPPORTS),
            ),
        )

        val projected = DecisionTraceProjector.project(listOf(trace)).traces.single()

        assertEquals(DecisionTraceKind.GOAL, projected.kind)
        assertEquals(EARLY, projected.firstRecordedAt)
        assertEquals(LATE, projected.lastRecordedAt)
        assertEquals(listOf(goal.id), projected.facts.map { it.id })
        assertEquals(listOf(constraint.id), projected.constraints.map { it.id })
        assertEquals(listOf(selection.id), projected.alternatives.map { it.id })
        assertEquals("EFFECT_NOT_GRANTED", projected.constraints.single().reasons.single().raw)
        assertEquals(2, projected.links.size)
        assertFalse(projected.unresolved)
    }

    @Test
    fun `unresolved self healing trace is surfaced and newest trace sorts first`() {
        val unresolved = node(
            type = DecisionTraceNodeType.UNRESOLVED_UNCERTAINTY,
            sourceType = "self-healing-incident",
            sourceId = "incident-9",
            at = LATE,
        )
        val recoveryTrace = DecisionTrace(
            id = DecisionTraceId.create("self-healing-incident", "incident-9"),
            revision = 1,
            nodes = listOf(unresolved),
            links = emptyList(),
        )
        val oldFact = node(
            type = DecisionTraceNodeType.OBSERVED_FACT,
            sourceType = "artifact-request",
            sourceId = "artifact-1",
            at = EARLY,
        )
        val artifactTrace = DecisionTrace(
            id = DecisionTraceId.create("artifact", "artifact-1"),
            revision = 1,
            nodes = listOf(oldFact),
            links = emptyList(),
        )

        val workspace = DecisionTraceProjector.project(listOf(artifactTrace, recoveryTrace))

        assertEquals(recoveryTrace.id, workspace.traces.first().traceId)
        assertEquals(DecisionTraceKind.SELF_HEALING, workspace.traces.first().kind)
        assertTrue(workspace.traces.first().unresolved)
        assertEquals(1, workspace.unresolvedCount)
        assertEquals(DecisionTraceKind.ARTIFACT, workspace.traces.last().kind)
    }

    private fun node(
        type: DecisionTraceNodeType,
        sourceType: String,
        sourceId: String,
        at: Instant,
    ): DecisionTraceNode = DecisionTraceNode.create(
        type = type,
        sourceType = sourceType,
        sourceId = sourceId,
        sourceRevision = 1,
        recordedAt = at,
    )

    private companion object {
        val EARLY: Instant = Instant.parse("2026-09-13T18:00:00Z")
        val MIDDLE: Instant = Instant.parse("2026-09-13T18:05:00Z")
        val LATE: Instant = Instant.parse("2026-09-13T18:10:00Z")
    }
}
