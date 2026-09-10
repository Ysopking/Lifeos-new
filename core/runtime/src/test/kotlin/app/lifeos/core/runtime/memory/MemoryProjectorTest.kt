package app.lifeos.core.runtime.memory

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.Provenance
import app.lifeos.core.runtime.thought.ThoughtMatrixV2
import app.lifeos.core.runtime.thought.ThoughtProjectionInput
import app.lifeos.core.runtime.thought.ThoughtVerificationStatus
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MemoryProjectorTest {
    private val at = Instant.parse("2026-09-10T13:00:00Z")
    private val domain = StableFieldIds.domain("memory-test")

    @Test
    fun `repeated observations never silently become preference`() = runTest {
        val matrix = ThoughtMatrixV2 { at }
        matrix.project(input(photon("obs-1", "Likes tea"), "drink.preference"))
        matrix.project(input(photon("obs-2", "Likes tea"), "drink.preference"))
        val snapshot = matrix.snapshot(at)
        val unsafeDirectives = snapshot.nodes.map { node ->
            MemoryProjectionDirective(
                thoughtNodeId = node.id,
                kind = MemoryKind.SEMANTIC,
                semanticKind = SemanticMemoryKind.PREFERENCE,
                evidenceKind = MemoryEvidenceKind.OBSERVATION,
            )
        }

        val report = MemoryProjector().project(snapshot, unsafeDirectives)

        assertEquals(2, report.candidates.count { it.kind == MemoryKind.EPISODIC })
        assertTrue(report.candidates.none { it.isPreference })
        assertEquals(2, report.rejected.size)
        assertTrue(report.rejected.all { it.reason == "preference-requires-user-confirmed-evidence" })
    }

    @Test
    fun `explicit user confirmation can create preference and remains evidence bound`() = runTest {
        val matrix = ThoughtMatrixV2 { at }
        matrix.project(input(photon("confirmed", "Prefers tea"), "drink.preference"))
        val snapshot = matrix.snapshot(at)
        val node = snapshot.nodes.single()

        val report = MemoryProjector().project(
            snapshot,
            listOf(
                MemoryProjectionDirective(
                    thoughtNodeId = node.id,
                    kind = MemoryKind.SEMANTIC,
                    semanticKind = SemanticMemoryKind.PREFERENCE,
                    evidenceKind = MemoryEvidenceKind.USER_CONFIRMED,
                )
            ),
        )

        val preference = report.candidates.single { it.isPreference }
        assertEquals(MemoryVerificationStatus.VERIFIED, preference.verification)
        assertEquals(node.id, preference.evidence.single().thoughtNodeId)
        assertEquals(node.photonId, preference.evidence.single().photonId)
        assertEquals(node.sourceRevision, preference.evidence.single().sourceRevision)
        assertEquals(node.provenance.sourceFingerprint, preference.evidence.single().sourceFingerprint)
        assertEquals(MemoryEvidenceKind.USER_CONFIRMED, preference.evidence.single().kind)
    }

    @Test
    fun `fact and preference use distinct stable keys even for same semantic key`() = runTest {
        val matrix = ThoughtMatrixV2 { at }
        matrix.project(input(photon("same-source", "Tea"), "drink"))
        val snapshot = matrix.snapshot(at)
        val node = snapshot.nodes.single()
        val report = MemoryProjector().project(
            snapshot,
            listOf(
                MemoryProjectionDirective(
                    node.id,
                    MemoryKind.SEMANTIC,
                    SemanticMemoryKind.FACT,
                    MemoryEvidenceKind.OBSERVATION,
                ),
                MemoryProjectionDirective(
                    node.id,
                    MemoryKind.SEMANTIC,
                    SemanticMemoryKind.PREFERENCE,
                    MemoryEvidenceKind.USER_CONFIRMED,
                ),
            ),
        )

        val semantic = report.candidates.filter { it.kind == MemoryKind.SEMANTIC }
        assertEquals(2, semantic.size)
        assertNotEquals(semantic[0].key, semantic[1].key)
    }

    @Test
    fun `thought projection conflicts remain visible to memory`() = runTest {
        val matrix = ThoughtMatrixV2 { at }
        val first = photon("conflicted", "Version A")
        val second = first.copy(content = "Version B")
        matrix.project(input(first, "claim"))
        matrix.project(input(second, "claim"))

        val report = MemoryProjector().project(matrix.snapshot(at))

        assertTrue(report.candidates.isEmpty())
        assertEquals(1, report.sourceConflicts.size)
        val conflict = report.sourceConflicts.single()
        assertEquals(first.id, conflict.photonId)
        assertEquals(1L, conflict.sourceRevision)
        assertEquals(2, conflict.fingerprints.size)
    }

    @Test
    fun `directive cannot target missing thought node`() = runTest {
        val report = MemoryProjector().project(
            snapshot = ThoughtMatrixV2 { at }.snapshot(at),
            directives = listOf(
                MemoryProjectionDirective(
                    thoughtNodeId = app.lifeos.core.runtime.thought.ThoughtNodeId("thought-node:missing"),
                    kind = MemoryKind.PROCEDURAL,
                    evidenceKind = MemoryEvidenceKind.GENERATED_STRATEGY,
                )
            ),
        )

        assertTrue(report.candidates.isEmpty())
        assertEquals("thought-node-not-active-or-conflicted", report.rejected.single().reason)
    }

    @Test
    fun `active thought explicitly marked conflicted is rejected instead of throwing`() = runTest {
        val matrix = ThoughtMatrixV2 { at }
        matrix.project(input(photon("marked-conflict", "Ambiguous"), "claim"))
        val base = matrix.snapshot(at)
        val conflictedNode = base.nodes.single().copy(
            verification = ThoughtVerificationStatus.CONFLICTED,
        )
        val legalExternalSnapshot = base.copy(nodes = listOf(conflictedNode))
        val directive = MemoryProjectionDirective(
            thoughtNodeId = conflictedNode.id,
            kind = MemoryKind.PROCEDURAL,
            evidenceKind = MemoryEvidenceKind.GENERATED_STRATEGY,
        )

        val report = MemoryProjector().project(legalExternalSnapshot, listOf(directive))

        assertTrue(report.candidates.isEmpty())
        assertEquals(2, report.rejected.size)
        assertTrue(report.rejected.all { it.reason == "conflicted-thought-node-not-projectable" })
    }

    private fun input(photon: Photon, semanticKey: String) = ThoughtProjectionInput(
        photon = photon,
        fieldDomainId = domain,
        semanticKey = semanticKey,
        verification = ThoughtVerificationStatus.OBSERVED,
    )

    private fun photon(id: String, content: String) = Photon(
        id = PhotonId(id),
        revision = 1,
        content = content,
        confidence = 0.8,
        provenance = Provenance(
            source = "chat",
            actor = "user",
            createdAt = at,
        ),
        tags = setOf("memory"),
    )
}
