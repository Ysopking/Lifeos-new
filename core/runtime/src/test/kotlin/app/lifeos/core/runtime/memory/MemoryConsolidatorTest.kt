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
import kotlin.test.assertTrue

class MemoryConsolidatorTest {
    private val at = Instant.parse("2026-09-10T14:00:00Z")
    private val domain = StableFieldIds.domain("memory-consolidation")

    @Test
    fun `matching semantic observations merge evidence without confidence inflation`() = runTest {
        val snapshot = thoughtSnapshot(
            photon("one", "Berlin", confidence = 0.6),
            photon("two", "Berlin", confidence = 0.8),
        )
        val directives = snapshot.nodes.map { node ->
            MemoryProjectionDirective(
                node.id,
                MemoryKind.SEMANTIC,
                SemanticMemoryKind.FACT,
                MemoryEvidenceKind.OBSERVATION,
                semanticKeyOverride = "capital.claim",
            )
        }
        val report = MemoryProjector().project(snapshot, directives)

        val result = MemoryConsolidator().consolidate(report, snapshot.revision, at)
        val semantic = result.snapshot.items.single { it.kind == MemoryKind.SEMANTIC }

        assertEquals(2, semantic.evidence.size)
        assertEquals(2, semantic.observationCount)
        assertEquals(0.8, semantic.confidence)
        assertEquals(MemoryVerificationStatus.OBSERVED, semantic.verification)
        assertEquals(2, result.snapshot.items.count { it.kind == MemoryKind.EPISODIC })
        assertEquals(listOf(semantic.key), result.mergedKeys)
        assertTrue(result.conflictedKeys.isEmpty())
    }

    @Test
    fun `different semantic claims remain explicit conflict with no active fact`() = runTest {
        val snapshot = thoughtSnapshot(
            photon("one", "Berlin"),
            photon("two", "Paris"),
        )
        val directives = snapshot.nodes.map { node ->
            MemoryProjectionDirective(
                node.id,
                MemoryKind.SEMANTIC,
                SemanticMemoryKind.FACT,
                MemoryEvidenceKind.INFERENCE,
                semanticKeyOverride = "capital.claim",
            )
        }
        val report = MemoryProjector().project(snapshot, directives)

        val result = MemoryConsolidator().consolidate(report, snapshot.revision, at)

        assertTrue(result.snapshot.items.none { it.kind == MemoryKind.SEMANTIC })
        assertEquals(1, result.snapshot.conflicts.size)
        val conflict = result.snapshot.conflicts.single()
        assertEquals(setOf("berlin", "paris"), conflict.alternatives.map { it.normalizedClaim }.toSet())
        assertEquals(2, conflict.alternatives.size)
        assertEquals(listOf(conflict.key), result.conflictedKeys)
    }

    @Test
    fun `conflicting user confirmed preferences stay visible rather than choosing latest`() = runTest {
        val snapshot = thoughtSnapshot(
            photon("tea", "Prefers tea"),
            photon("coffee", "Prefers coffee"),
        )
        val directives = snapshot.nodes.map { node ->
            MemoryProjectionDirective(
                node.id,
                MemoryKind.SEMANTIC,
                SemanticMemoryKind.PREFERENCE,
                MemoryEvidenceKind.USER_CONFIRMED,
                semanticKeyOverride = "drink.preference",
            )
        }
        val report = MemoryProjector().project(snapshot, directives)

        val result = MemoryConsolidator().consolidate(report, snapshot.revision, at)

        assertTrue(result.snapshot.items.none { it.isPreference })
        val conflict = result.snapshot.conflicts.single()
        assertTrue(conflict.alternatives.all { it.isPreference })
        assertTrue(conflict.alternatives.all { it.verification == MemoryVerificationStatus.VERIFIED })
        assertTrue(conflict.alternatives.all {
            it.evidence.any { evidence -> evidence.kind == MemoryEvidenceKind.USER_CONFIRMED }
        })
    }

    @Test
    fun `same procedural strategy merges only identical claims`() = runTest {
        val snapshot = thoughtSnapshot(
            photon("p1", "Retry with bounded backoff"),
            photon("p2", "Retry with bounded backoff"),
        )
        val directives = snapshot.nodes.map { node ->
            MemoryProjectionDirective(
                node.id,
                MemoryKind.PROCEDURAL,
                evidenceKind = MemoryEvidenceKind.GENERATED_STRATEGY,
                semanticKeyOverride = "recovery.retry",
            )
        }
        val report = MemoryProjector().project(snapshot, directives)

        val result = MemoryConsolidator().consolidate(report, snapshot.revision, at)
        val procedure = result.snapshot.items.single { it.kind == MemoryKind.PROCEDURAL }

        assertEquals(2, procedure.evidence.size)
        assertEquals("retry with bounded backoff", procedure.normalizedClaim)
        assertTrue(result.snapshot.conflicts.isEmpty())
    }

    private suspend fun thoughtSnapshot(vararg photons: Photon) = ThoughtMatrixV2 { at }.let { matrix ->
        photons.forEach { photon ->
            matrix.project(
                ThoughtProjectionInput(
                    photon = photon,
                    fieldDomainId = domain,
                    semanticKey = "source.${photon.id.value}",
                    verification = ThoughtVerificationStatus.OBSERVED,
                )
            )
        }
        matrix.snapshot(at)
    }

    private fun photon(
        id: String,
        content: String,
        confidence: Double = 0.75,
    ) = Photon(
        id = PhotonId(id),
        revision = 1,
        content = content,
        confidence = confidence,
        provenance = Provenance(
            source = "test",
            actor = "user",
            createdAt = at,
        ),
    )
}
