package app.lifeos.core.runtime.memory

import app.lifeos.core.model.PhotonId
import app.lifeos.core.runtime.thought.ThoughtNodeId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFailsWith

class MemoryModelInvariantTest {
    private val at = Instant.parse("2026-09-10T15:30:00Z")

    @Test
    fun `preference cannot be constructed from observation evidence`() {
        val evidence = evidence(MemoryEvidenceKind.OBSERVATION)

        assertFailsWith<IllegalArgumentException> {
            MemoryItem.create(
                key = MemoryKey("preference-key"),
                kind = MemoryKind.SEMANTIC,
                semanticKind = SemanticMemoryKind.PREFERENCE,
                semanticKey = "drink.preference",
                content = "Prefers tea",
                confidence = 0.9,
                verification = MemoryVerificationStatus.OBSERVED,
                evidence = listOf(evidence),
            )
        }
    }

    @Test
    fun `memory cannot be constructed without source evidence`() {
        assertFailsWith<IllegalArgumentException> {
            MemoryItem.create(
                key = MemoryKey("fact-key"),
                kind = MemoryKind.SEMANTIC,
                semanticKind = SemanticMemoryKind.FACT,
                semanticKey = "fact",
                content = "A fact",
                confidence = 0.5,
                verification = MemoryVerificationStatus.UNVERIFIED,
                evidence = emptyList(),
            )
        }
    }

    private fun evidence(kind: MemoryEvidenceKind) = MemoryEvidenceRef(
        thoughtNodeId = ThoughtNodeId("thought-node:test"),
        photonId = PhotonId("photon-test"),
        sourceRevision = 1,
        sourceFingerprint = "fingerprint",
        kind = kind,
        source = "test",
        actor = "user",
        observedAt = at,
    )
}
