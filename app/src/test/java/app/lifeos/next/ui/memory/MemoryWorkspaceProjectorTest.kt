package app.lifeos.next.ui.memory

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.Provenance
import app.lifeos.core.runtime.life.DurableLifeMemorySnapshot
import app.lifeos.core.runtime.life.LifeGraphSnapshot
import app.lifeos.core.runtime.life.LongTermMemoryProjection
import app.lifeos.core.runtime.life.MemoryAccessLedger
import app.lifeos.core.runtime.life.MemoryAtom
import app.lifeos.core.runtime.life.MemoryAtomKind
import app.lifeos.core.runtime.life.MemoryCompactionDecision
import app.lifeos.core.runtime.life.MemoryCrystal
import app.lifeos.core.runtime.life.MemoryEpisode
import app.lifeos.core.runtime.life.MemoryStage
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryWorkspaceProjectorTest {
    private val now = Instant.parse("2026-09-13T12:00:00Z")

    @Test
    fun nowUsesExactProjectedStageAndLeavesNewEvidenceUnclassified() {
        val hot = photon("hot", "Aktive Erinnerung", now.minusSeconds(60), setOf("chat"))
        val warm = photon("warm", "Nahe Erinnerung", now.minusSeconds(120), setOf("chat"))
        val cold = photon("cold", "Langzeit Erinnerung", now.minusSeconds(180), setOf("chat"))
        val fresh = photon("fresh", "Noch nicht projiziert", now, setOf("chat"))
        val snapshot = snapshot(
            decisions = listOf(
                decision(hot.id, MemoryStage.HOT),
                decision(warm.id, MemoryStage.WARM),
                decision(cold.id, MemoryStage.COLD),
            )
        )

        val model = MemoryWorkspaceProjector.project(snapshot, listOf(cold, warm, fresh, hot))

        assertEquals(listOf("fresh", "hot", "warm"), model.now.map { it.photonId.value })
        assertNull(model.now.first().stage)
        assertTrue(model.now.first().isNew)
        assertEquals(MemoryStage.HOT, model.now[1].stage)
        assertEquals(MemoryStage.WARM, model.now[2].stage)
        assertFalse(model.now[1].isNew)
    }

    @Test
    fun managementAndLowLevelEvidenceNeverAppearAsRawNowCards() {
        val visible = photon("visible", "Sichtbar", now, setOf("chat"))
        val management = photon("management", "Intern", now, setOf("life-memory-management"))
        val rawSpeech = photon("speech", "raw", now, setOf("perception-raw-source"))
        val scene = photon("scene", "graph", now, setOf("scene-graph"))

        val model = MemoryWorkspaceProjector.project(
            snapshot = null,
            photons = listOf(management, rawSpeech, scene, visible),
        )

        assertEquals(listOf("visible"), model.now.map { it.photonId.value })
        assertFalse(model.projectionAvailable)
    }

    @Test
    fun topicsCrystalsAndEpisodesRetainSourcesAndExposeMissingEvidence() {
        val source = photon("source", "Mutter anrufen", now.minusSeconds(600), setOf("person:Mutter", "goal"))
        val missing = PhotonId("missing")
        val atom = MemoryAtom(
            atomId = "atom-1",
            kind = MemoryAtomKind.PERSON,
            content = "Mutter",
            sourcePhotonIds = setOf(source.id, missing),
            sourceStateHash = "state",
            episodeId = "episode-1",
            confidence = 0.9,
            observedAt = source.provenance.createdAt,
            stage = MemoryStage.COLD,
        )
        val crystal = MemoryCrystal(
            crystalId = "crystal-1",
            semanticCore = "Familie bleibt wichtig",
            atomIds = setOf(atom.atomId),
            sourcePhotonIds = setOf(source.id, missing),
            startedAt = source.provenance.createdAt,
            endedAt = now.minusSeconds(300),
            confidence = 0.8,
        )
        val episode = MemoryEpisode(
            episodeId = "episode-1",
            sourcePhotonIds = setOf(source.id, missing),
            stage = MemoryStage.CRYSTALLIZED,
            startedAt = source.provenance.createdAt,
            endedAt = now.minusSeconds(300),
            semanticKeys = setOf("family", "person"),
        )
        val model = MemoryWorkspaceProjector.project(
            snapshot(
                decisions = listOf(decision(source.id, MemoryStage.CRYSTALLIZED)),
                atoms = listOf(atom),
                crystals = listOf(crystal),
                episodes = listOf(episode),
            ),
            listOf(source),
        )

        val projectedAtom = model.topicGroups.single().atoms.single()
        assertEquals(MemoryAtomKind.PERSON, model.topicGroups.single().kind)
        assertEquals(1, projectedAtom.resolvedSourceCount)
        assertEquals(1, projectedAtom.missingSourceCount)
        assertEquals(setOf(source.id, missing), projectedAtom.sourcePhotonIds)
        assertEquals(1, model.crystals.single().missingSourceCount)
        assertEquals(1, model.episodes.single().missingSourceCount)
    }

    @Test
    fun searchMatchesProjectionAndSourceEvidenceWithoutMutatingStage() {
        val source = photon(
            "berlin",
            "Termin in Berlin",
            now.minusSeconds(500),
            setOf("place:Berlin", "calendar"),
        )
        val atom = MemoryAtom(
            atomId = "atom-place",
            kind = MemoryAtomKind.PLACE,
            content = "Hauptstadt",
            sourcePhotonIds = setOf(source.id),
            sourceStateHash = "state",
            episodeId = "episode-place",
            confidence = 0.95,
            observedAt = source.provenance.createdAt,
            stage = MemoryStage.COLD,
        )
        val snapshot = snapshot(
            decisions = listOf(decision(source.id, MemoryStage.COLD)),
            atoms = listOf(atom),
        )

        val bySourceContent = MemoryWorkspaceProjector.project(snapshot, listOf(source), "Berlin")
        val byTag = MemoryWorkspaceProjector.project(snapshot, listOf(source), "calendar")
        val missing = MemoryWorkspaceProjector.project(snapshot, listOf(source), "Hamburg")

        assertEquals("atom-place", bySourceContent.topicGroups.single().atoms.single().atomId)
        assertEquals("atom-place", byTag.topicGroups.single().atoms.single().atomId)
        assertTrue(missing.topicGroups.isEmpty())
        assertTrue(missing.now.isEmpty())
    }

    @Test
    fun resolveSourceFailsClosedWhenEvidenceIsMissing() {
        val source = photon("known", "Bekannt", now, setOf("chat"))
        val snapshot = snapshot(decisions = listOf(decision(source.id, MemoryStage.HOT)))

        assertEquals(
            MemoryStage.HOT,
            MemoryWorkspaceProjector.resolveSource(source.id, listOf(source), snapshot)?.stage,
        )
        assertNull(
            MemoryWorkspaceProjector.resolveSource(PhotonId("missing"), listOf(source), snapshot)
        )
    }

    @Test
    fun projectionIsDeterministicForEquivalentInputOrder() {
        val first = photon("a", "Alpha", now.minusSeconds(20), setOf("chat"))
        val second = photon("b", "Beta", now.minusSeconds(10), setOf("chat"))
        val snapshot = snapshot(
            decisions = listOf(
                decision(first.id, MemoryStage.HOT),
                decision(second.id, MemoryStage.WARM),
            )
        )

        val left = MemoryWorkspaceProjector.project(snapshot, listOf(first, second), "a")
        val right = MemoryWorkspaceProjector.project(snapshot, listOf(second, first), "a")

        assertEquals(left, right)
    }

    private fun photon(
        id: String,
        content: String,
        createdAt: Instant,
        tags: Set<String>,
    ): Photon = Photon(
        id = PhotonId(id),
        content = content,
        provenance = Provenance(
            source = "test",
            actor = "user",
            createdAt = createdAt,
        ),
        tags = tags,
    )

    private fun decision(id: PhotonId, stage: MemoryStage): MemoryCompactionDecision =
        MemoryCompactionDecision(
            photonId = id,
            fromStage = MemoryStage.HOT,
            toStage = stage,
            inactivity = Duration.ZERO,
            relevanceScore = 0.5,
            protections = emptySet(),
            reason = "test",
            decisionId = "decision-${id.value}-$stage",
        )

    private fun snapshot(
        decisions: List<MemoryCompactionDecision> = emptyList(),
        atoms: List<MemoryAtom> = emptyList(),
        crystals: List<MemoryCrystal> = emptyList(),
        episodes: List<MemoryEpisode> = emptyList(),
    ): DurableLifeMemorySnapshot {
        val projection = LongTermMemoryProjection(
            decisions = decisions,
            episodes = episodes,
            atoms = atoms,
            crystals = crystals,
            derivedPhotons = emptyList(),
            evaluatedAt = now.minusSeconds(1),
            fingerprint = "projection",
        )
        return DurableLifeMemorySnapshot(
            graph = LifeGraphSnapshot(
                entities = emptyList(),
                events = emptyList(),
                relationships = emptyList(),
                fingerprint = "graph",
            ),
            memory = projection,
            accessLedger = MemoryAccessLedger(),
            authoritativePhotonCount = decisions.size,
            fingerprint = "snapshot",
        )
    }
}
