package app.lifeos.core.runtime.field

import app.lifeos.core.field.FieldConvergenceEngine
import app.lifeos.core.field.TemporalValidity
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.Provenance
import app.lifeos.core.runtime.thought.ThoughtGraphEdgeKind
import app.lifeos.core.runtime.thought.ThoughtGraphNodeKind
import app.lifeos.core.runtime.thought.ThoughtGraphSourceKind
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FieldThoughtGraphGoalProjectionTest {
    private val createdAt = Instant.parse("2026-09-11T09:30:00Z")

    @Test
    fun `persisted goal photon becomes first class durable goal node`() {
        val photon = photon(
            id = "goal-projection",
            mimeType = GOAL_MIME_TYPE,
            tags = setOf("goal", "language-understood"),
        )
        val request = DefaultPhotonFieldRequestFactory().create(photon)
        val result = FieldConvergenceEngine().converge(request)
        val delta = FieldThoughtGraphProjector().project(photon, request, result).delta

        val goal = delta.nodeVersions.single { it.attributes["mimeType"] == GOAL_MIME_TYPE }
        assertEquals(ThoughtGraphNodeKind.GOAL, goal.kind)
        assertEquals(ThoughtGraphSourceKind.GOAL, goal.provenance.sourceKind)
        assertEquals("goal", goal.semanticKey)
        assertEquals(TemporalValidity.UNBOUNDED, goal.validity)
        assertEquals(photon.id.value, goal.provenance.sourceId)
        assertEquals(photon.revision, goal.sourceRevision)

        val sourceEdges = delta.edgeVersions.filter {
            it.kind == ThoughtGraphEdgeKind.DERIVED_FROM && it.targetNodeId == goal.id
        }
        assertTrue(sourceEdges.isNotEmpty(), "Field evidence must remain connected to the typed goal node")
    }

    @Test
    fun `ordinary photon remains ordinary photon projection`() {
        val photon = photon(id = "ordinary-projection")
        val request = DefaultPhotonFieldRequestFactory().create(photon)
        val result = FieldConvergenceEngine().converge(request)
        val delta = FieldThoughtGraphProjector().project(photon, request, result).delta

        val node = delta.nodeVersions.single { it.attributes["mimeType"] == "text/plain" }
        assertEquals(ThoughtGraphNodeKind.PHOTON, node.kind)
        assertEquals(ThoughtGraphSourceKind.PHOTON, node.provenance.sourceKind)
        assertEquals("photon", node.semanticKey)
        assertEquals(TemporalValidity.at(createdAt), node.validity)
    }

    private fun photon(
        id: String,
        mimeType: String = "text/plain",
        tags: Set<String> = emptySet(),
    ): Photon = Photon(
        id = PhotonId(id),
        revision = 2,
        content = if (mimeType == GOAL_MIME_TYPE) "goal/v2\nobjective=finish V3" else "ordinary cognitive input",
        mimeType = mimeType,
        confidence = 0.91,
        semanticMass = 1.2,
        energy = 0.9,
        provenance = Provenance(
            source = "goal-projection-test",
            actor = "test",
            createdAt = createdAt,
        ),
        tags = tags,
    )

    private companion object {
        const val GOAL_MIME_TYPE = "application/vnd.lifeos.goal+text"
    }
}
