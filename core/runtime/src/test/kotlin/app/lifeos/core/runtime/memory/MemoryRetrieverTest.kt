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

class MemoryRetrieverTest {
    private val at = Instant.parse("2026-09-10T15:00:00Z")
    private val domain = StableFieldIds.domain("memory-retrieval")

    @Test
    fun `conflicted semantic key is returned as conflict never as fact match`() = runTest {
        val thought = thoughtSnapshot(
            photon("berlin", "Berlin is the capital"),
            photon("paris", "Paris is the capital"),
        )
        val report = MemoryProjector().project(
            thought,
            thought.nodes.map { node ->
                MemoryProjectionDirective(
                    node.id,
                    MemoryKind.SEMANTIC,
                    SemanticMemoryKind.FACT,
                    MemoryEvidenceKind.INFERENCE,
                    semanticKeyOverride = "capital.claim",
                )
            },
        )
        val memory = MemoryConsolidator().consolidate(report, thought.revision, at).snapshot

        val result = MemoryRetriever().retrieve(
            memory,
            MemoryQuery(text = "Paris capital", kinds = setOf(MemoryKind.SEMANTIC)),
        )

        assertTrue(result.matches.isEmpty())
        assertEquals(1, result.conflicts.size)
        assertTrue("conflicted-key-not-authoritative" in result.conflicts.single().reasons)
    }

    @Test
    fun `retrieval is deterministic and evidence remains attached`() = runTest {
        val thought = thoughtSnapshot(
            photon("alpha", "Project Alpha deployment notes", 0.9),
            photon("beta", "Project Beta deployment notes", 0.7),
        )
        val report = MemoryProjector().project(thought)
        val memory = MemoryConsolidator().consolidate(report, thought.revision, at).snapshot
        val query = MemoryQuery(text = "project deployment", kinds = setOf(MemoryKind.EPISODIC))
        val retriever = MemoryRetriever()

        val first = retriever.retrieve(memory, query)
        val second = retriever.retrieve(memory.copy(capturedAt = at.plusSeconds(100)), query)

        assertEquals(first.matches.map { it.item.id }, second.matches.map { it.item.id })
        assertEquals(first.matches.map { it.score }, second.matches.map { it.score })
        assertEquals(2, first.matches.size)
        assertEquals("alpha", first.matches.first().item.evidence.single().photonId.value)
        assertTrue(first.matches.all { it.item.evidence.isNotEmpty() })
    }

    @Test
    fun `explicit preference retrieval explains user confirmation`() = runTest {
        val thought = thoughtSnapshot(photon("pref", "Prefers dark mode", 0.85))
        val node = thought.nodes.single()
        val report = MemoryProjector().project(
            thought,
            listOf(
                MemoryProjectionDirective(
                    node.id,
                    MemoryKind.SEMANTIC,
                    SemanticMemoryKind.PREFERENCE,
                    MemoryEvidenceKind.USER_CONFIRMED,
                    semanticKeyOverride = "ui.theme.preference",
                )
            ),
        )
        val memory = MemoryConsolidator().consolidate(report, thought.revision, at).snapshot

        val result = MemoryRetriever().retrieve(
            memory,
            MemoryQuery(text = "dark mode", kinds = setOf(MemoryKind.SEMANTIC)),
        )

        val match = result.matches.single()
        assertTrue(match.item.isPreference)
        assertTrue("explicit-user-confirmed-preference" in match.reasons)
        assertEquals(MemoryVerificationStatus.VERIFIED, match.item.verification)
    }

    private suspend fun thoughtSnapshot(vararg photons: Photon) = ThoughtMatrixV2 { at }.let { matrix ->
        photons.forEach { photon ->
            matrix.project(
                ThoughtProjectionInput(
                    photon = photon,
                    fieldDomainId = domain,
                    semanticKey = "topic.${photon.id.value}",
                    verification = ThoughtVerificationStatus.OBSERVED,
                )
            )
        }
        matrix.snapshot(at)
    }

    private fun photon(
        id: String,
        content: String,
        confidence: Double = 0.8,
    ) = Photon(
        id = PhotonId(id),
        revision = 1,
        content = content,
        confidence = confidence,
        provenance = Provenance("test", "user", at),
    )
}
