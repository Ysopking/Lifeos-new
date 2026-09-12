package app.lifeos.core.runtime.life

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.Provenance
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LongTermMemoryRuntimeTest {
    private val now = Instant.parse("2026-09-12T10:00:00Z")

    @Test
    fun recentEvidenceRemainsHot() {
        val photon = photon("recent", now.minus(Duration.ofHours(2)), "new information")
        val result = LongTermMemoryEngine().project(listOf(photon), now = now)

        assertEquals(MemoryStage.HOT, result.stageOf(photon.id))
        assertTrue(result.atoms.isEmpty())
        assertTrue(result.crystals.isEmpty())
    }

    @Test
    fun unusedOldEvidenceAtomizesIntoColdLongTermMemory() {
        val photon = photon(
            id = "cold",
            createdAt = now.minus(Duration.ofDays(90)),
            content = "Person A promised payment. Deadline is next month.",
        )
        val result = LongTermMemoryEngine().project(listOf(photon), now = now)

        assertEquals(MemoryStage.COLD, result.stageOf(photon.id))
        assertTrue(result.atoms.isNotEmpty())
        assertTrue(result.atoms.all { photon.id in it.sourcePhotonIds })
        assertTrue(result.derivedPhotons.any { "memory-atom" in it.tags })
    }

    @Test
    fun veryOldUnusedEvidenceCrystallizesButKeepsSourceLineage() {
        val photon = photon(
            id = "crystal",
            createdAt = now.minus(Duration.ofDays(400)),
            content = "A completed event from long ago. The result was successful.",
            tags = setOf("completed", "event"),
            semanticMass = 0.1,
            confidence = 0.4,
        )
        val result = LongTermMemoryEngine().project(listOf(photon), now = now)

        assertEquals(MemoryStage.CRYSTALLIZED, result.stageOf(photon.id))
        assertTrue(result.crystals.isNotEmpty())
        assertTrue(result.crystals.single().sourcePhotonIds.contains(photon.id))
        assertTrue(result.derivedPhotons.any { "memory-crystal" in it.tags })
    }

    @Test
    fun ageAloneNeverCrystallizesOpenDebtDeadlineOrGoal() {
        val createdAt = now.minus(Duration.ofDays(1000))
        val protected = listOf(
            photon("debt", createdAt, "open debt", setOf("debt")),
            photon("deadline", createdAt, "open deadline", setOf("fact:deadline")),
            photon("goal", createdAt, "unfinished goal", setOf("goal")),
            photon("contract", createdAt, "active contract", setOf("contract")),
        )
        val result = LongTermMemoryEngine().project(protected, now = now)

        assertTrue(result.decisions.all { it.toStage != MemoryStage.CRYSTALLIZED })
        assertTrue(result.decisions.all { it.protections.isNotEmpty() })
    }

    @Test
    fun futureRelevanceRehydratesOldMemoryToWarm() {
        val photon = photon(
            id = "future",
            createdAt = now.minus(Duration.ofDays(500)),
            content = "old project information",
            semanticMass = 0.1,
            confidence = 0.4,
        )
        val ledger = MemoryAccessLedger(
            mapOf(
                photon.id to MemoryUsageProfile(
                    photonId = photon.id,
                    lastAccessAt = photon.provenance.createdAt,
                    futureRelevance = 0.95,
                )
            )
        )
        val result = LongTermMemoryEngine().project(listOf(photon), ledger, now)

        assertEquals(MemoryStage.WARM, result.stageOf(photon.id))
        assertEquals("rehydrated-by-future-or-goal-relevance", result.decisions.single().reason)
    }

    @Test
    fun projectionAndDerivedIdsAreDeterministicForSameInputs() {
        val photon = photon(
            id = "deterministic",
            createdAt = now.minus(Duration.ofDays(400)),
            content = "Closed event. Result recorded.",
            tags = setOf("completed", "event"),
            semanticMass = 0.1,
            confidence = 0.4,
        )
        val engine = LongTermMemoryEngine()
        val first = engine.project(listOf(photon), now = now)
        val second = engine.project(listOf(photon), now = now)

        assertEquals(first, second)
        assertEquals(first.fingerprint, second.fingerprint)
        assertEquals(first.derivedPhotons.map { it.id }, second.derivedPhotons.map { it.id })
    }

    @Test
    fun originalPhotonIsNeverMutatedByCompactionProjection() {
        val original = photon(
            id = "immutable",
            createdAt = now.minus(Duration.ofDays(400)),
            content = "Archived information that remains evidence.",
            tags = setOf("completed"),
            semanticMass = 0.1,
            confidence = 0.4,
        )
        val before = original.copy()
        val result = LongTermMemoryEngine().project(listOf(original), now = now)

        assertEquals(before, original)
        assertNotEquals(original.id, result.derivedPhotons.first().id)
        assertTrue(result.derivedPhotons.all { original.id in it.provenance.parentIds || "memory-crystal" in it.tags })
    }

    private fun photon(
        id: String,
        createdAt: Instant,
        content: String,
        tags: Set<String> = emptySet(),
        semanticMass: Double = 0.2,
        confidence: Double = 0.5,
    ): Photon = Photon(
        id = PhotonId(id),
        content = content,
        semanticMass = semanticMass,
        confidence = confidence,
        provenance = Provenance("test", "user", createdAt),
        tags = tags,
    )
}
