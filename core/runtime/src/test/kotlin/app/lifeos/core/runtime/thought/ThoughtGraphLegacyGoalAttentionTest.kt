package app.lifeos.core.runtime.thought

import app.lifeos.core.field.TemporalValidity
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThoughtGraphLegacyGoalAttentionTest {
    private val time = Instant.parse("2026-09-11T10:00:00Z")

    @Test
    fun `legacy goal mime photon remains goal relevant without history rewrite`() {
        val legacyGoal = node(
            id = "legacy-goal",
            confidence = 0.70,
            authority = 0.60,
            attributes = mapOf("mimeType" to GOAL_MIME_TYPE),
        )
        val unrelated = node(
            id = "unrelated",
            confidence = 1.0,
            authority = 1.0,
        )
        val snapshot = ThoughtGraphSnapshot(
            revision = 0,
            activeNodes = listOf(legacyGoal, unrelated).sortedBy { it.id.value },
            activeEdges = emptyList(),
            conflicts = emptyList(),
            nodeHistoryCount = 2,
            edgeHistoryCount = 0,
            appliedDeltaIds = emptyList(),
            capturedAt = time,
            historyFingerprint = "legacy-goal-history",
        )

        val working = ThoughtGraphAttentionProjector().project(snapshot, asOf = time.plusSeconds(60))

        assertEquals(legacyGoal.id, working.entries.first().nodeId)
        assertTrue(ThoughtGraphAttentionReason.GOAL in working.entries.first().reasons)
        assertEquals(ThoughtGraphNodeKind.PHOTON, legacyGoal.kind)
    }

    private fun node(
        id: String,
        confidence: Double,
        authority: Double,
        attributes: Map<String, String> = emptyMap(),
    ): ThoughtGraphNodeVersion = ThoughtGraphNodeVersion.create(
        kind = ThoughtGraphNodeKind.PHOTON,
        semanticKey = id,
        summary = id,
        confidence = confidence,
        authority = authority,
        validity = TemporalValidity.at(time),
        provenance = ThoughtGraphProvenance(
            sourceKind = ThoughtGraphSourceKind.PHOTON,
            sourceId = id,
            sourceRevision = 1,
            sourceFingerprint = "fingerprint:$id",
            origin = "legacy-goal-test",
            actor = "test",
            createdAt = time,
        ),
        attributes = attributes,
    )

    private companion object {
        const val GOAL_MIME_TYPE = "application/vnd.lifeos.goal+text"
    }
}
