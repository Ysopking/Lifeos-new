package app.lifeos.core.runtime.thought

import app.lifeos.core.field.TemporalValidity
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        val snapshot = snapshot(listOf(legacyGoal, unrelated), "legacy-goal-history")

        val working = ThoughtGraphAttentionProjector().project(snapshot, asOf = time.plusSeconds(60))

        assertEquals(legacyGoal.id, working.entries.first().nodeId)
        assertTrue(ThoughtGraphAttentionReason.GOAL in working.entries.first().reasons)
        assertEquals(ThoughtGraphNodeKind.PHOTON, legacyGoal.kind)
    }

    @Test
    fun `first class goal suppresses legacy duplicate only in working set`() {
        val sourceId = "persisted-goal-source"
        val legacyGoal = node(
            id = "legacy-goal-version",
            sourceId = sourceId,
            confidence = 0.95,
            authority = 0.95,
            attributes = mapOf("mimeType" to GOAL_MIME_TYPE),
        )
        val firstClassGoal = node(
            id = "first-class-goal-version",
            sourceId = sourceId,
            kind = ThoughtGraphNodeKind.GOAL,
            sourceKind = ThoughtGraphSourceKind.GOAL,
            confidence = 0.70,
            authority = 0.60,
            validity = TemporalValidity.UNBOUNDED,
            attributes = mapOf("mimeType" to GOAL_MIME_TYPE),
        )
        val snapshot = snapshot(
            listOf(legacyGoal, firstClassGoal),
            "migrated-goal-history",
        )

        val working = ThoughtGraphAttentionProjector().project(snapshot, asOf = time.plusSeconds(60))

        assertTrue(snapshot.activeNodes.any { it.id == legacyGoal.id })
        assertTrue(snapshot.activeNodes.any { it.id == firstClassGoal.id })
        assertTrue(working.nodes.any { it.id == firstClassGoal.id })
        assertFalse(working.nodes.any { it.id == legacyGoal.id })
        assertEquals(1, working.entries.count { ThoughtGraphAttentionReason.GOAL in it.reasons })
    }

    private fun snapshot(
        nodes: List<ThoughtGraphNodeVersion>,
        historyFingerprint: String,
    ): ThoughtGraphSnapshot = ThoughtGraphSnapshot(
        revision = 0,
        activeNodes = nodes.sortedBy { it.id.value },
        activeEdges = emptyList(),
        conflicts = emptyList(),
        nodeHistoryCount = nodes.size,
        edgeHistoryCount = 0,
        appliedDeltaIds = emptyList(),
        capturedAt = time,
        historyFingerprint = historyFingerprint,
    )

    private fun node(
        id: String,
        sourceId: String = id,
        kind: ThoughtGraphNodeKind = ThoughtGraphNodeKind.PHOTON,
        sourceKind: ThoughtGraphSourceKind = ThoughtGraphSourceKind.PHOTON,
        confidence: Double,
        authority: Double,
        validity: TemporalValidity = TemporalValidity.at(time),
        attributes: Map<String, String> = emptyMap(),
    ): ThoughtGraphNodeVersion = ThoughtGraphNodeVersion.create(
        kind = kind,
        semanticKey = id,
        summary = id,
        confidence = confidence,
        authority = authority,
        validity = validity,
        provenance = ThoughtGraphProvenance(
            sourceKind = sourceKind,
            sourceId = sourceId,
            sourceRevision = if (kind == ThoughtGraphNodeKind.GOAL) 2 else 1,
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
