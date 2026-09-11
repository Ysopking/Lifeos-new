package app.lifeos.core.runtime.deepsearch

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonPhase
import app.lifeos.core.model.Provenance
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DeepSearchMissionCoordinatorTest {
    private val now = Instant.parse("2026-09-11T17:00:00Z")

    @Test
    fun `terminal mission recovers durable photon and checkpoint without invoking search again`() = runTest {
        val missionRepo = MemoryMissionRepository()
        val checkpointRepo = MemoryCheckpointRepository()
        val photons = MemoryResultPhotons()
        val ledger = DeepSearchMissionLedger(missionRepo, now = { now })
        val checkpoints = DeepSearchCheckpointStore(checkpointRepo, now = { now })
        val coordinator = DeepSearchMissionCoordinator(ledger, checkpoints, photons)
        val definition = definition()
        var searchCalls = 0

        val first = coordinator.run(definition) { _, sink, missionId ->
            searchCalls += 1
            searchProduct(missionId, sink)
        }

        val terminal = requireNotNull(ledger.snapshot(definition.id))
        assertEquals(DeepSearchMissionState.COMPLETED, terminal.state)
        assertEquals(first.photon.id, terminal.resultPhotonId)
        assertEquals(first.photon, photons.load(first.photon.id))
        assertEquals(1, searchCalls)

        val recovered = DeepSearchMissionCoordinator(
            ledger = DeepSearchMissionLedger(missionRepo, now = { now.plusSeconds(10) }),
            checkpoints = DeepSearchCheckpointStore(checkpointRepo, now = { now.plusSeconds(10) }),
            resultPhotons = photons,
        ).run(definition) { _, _, _ ->
            error("terminal recovery must not re-run search")
        }

        assertEquals(first.photon, recovered.photon)
        assertEquals(first.result, recovered.result)
        assertEquals(1, searchCalls)
    }

    @Test
    fun `terminal ledger with missing checkpoint fails closed instead of re-searching`() = runTest {
        val missionRepo = MemoryMissionRepository()
        val originalCheckpoints = MemoryCheckpointRepository()
        val photons = MemoryResultPhotons()
        val definition = definition()
        val coordinator = DeepSearchMissionCoordinator(
            DeepSearchMissionLedger(missionRepo, now = { now }),
            DeepSearchCheckpointStore(originalCheckpoints, now = { now }),
            photons,
        )
        coordinator.run(definition) { _, sink, missionId -> searchProduct(missionId, sink) }

        assertFailsWith<IllegalArgumentException> {
            DeepSearchMissionCoordinator(
                DeepSearchMissionLedger(missionRepo, now = { now.plusSeconds(20) }),
                DeepSearchCheckpointStore(MemoryCheckpointRepository(), now = { now.plusSeconds(20) }),
                photons,
            ).run(definition) { _, _, _ ->
                error("corrupt terminal recovery must not re-run search")
            }
        }
    }

    private suspend fun searchProduct(
        missionId: DeepSearchMissionId,
        sink: DeepSearchCheckpointSink,
    ): DeepSearchMissionProduct {
        val request = DeepSearchRequest(
            query = "lifeos photons",
            budget = DeepSearchBudget(
                maxDepth = 2,
                maxBreadth = 4,
                maxWorkUnits = 4,
                maxElapsed = Duration.ofSeconds(4),
            ),
            minimumResolutionScore = 0.0,
            minimumWinnerMargin = 0.0,
        )
        val result = DeepSearchPlannerV2(now = { now }).search(
            request = request,
            sources = listOf(SingleFindingSource()),
            checkpointSink = sink,
        )
        assertEquals(DeepSearchStatus.RESOLVED, result.status)
        val photon = Photon(
            id = PhotonId("deep-search-result_${missionId.value.removePrefix(DeepSearchMissionId.PREFIX)}"),
            content = "LIFEOS photon evidence",
            phase = PhotonPhase.CONVERGED,
            confidence = 0.95,
            provenance = Provenance("test-deepsearch-v2", "test", now),
            tags = setOf(
                "deepsearch-answer",
                "deepsearch-status:resolved",
                "deepsearch-mission:${missionId.value}",
            ),
        )
        return DeepSearchMissionProduct(
            photon = photon,
            result = result,
            evidencePhotonIds = result.evidence.mapNotNull { it.sourcePhotonId },
            missionId = missionId,
        )
    }

    private fun definition() = DeepSearchMissionDefinition.create(
        goalPhotonId = PhotonId("goal-deepsearch"),
        sourcePhotonId = PhotonId("source-chat"),
        sourceRevision = 1,
        query = "lifeos photons",
        searchPolicyVersion = "deepsearch-v2-test",
        sourceScopeIds = setOf("test-source"),
        createdAt = now,
    )

    private class SingleFindingSource : DeepSearchSource {
        override val descriptor = DeepSearchSourceDescriptor(
            sourceId = "test-source",
            kind = DeepSearchSourceKind.LOCAL,
            reliability = 1.0,
        )

        override suspend fun expand(
            request: DeepSearchRequest,
            branch: DeepSearchBranch,
        ): List<DeepSearchFindingDraft> {
            if (branch.depth > 0) return emptyList()
            return listOf(
                DeepSearchFindingDraft(
                    statement = "LIFEOS stores evidence as photons.",
                    semanticTerms = setOf("lifeos", "photons"),
                    confidence = 0.95,
                    evidence = listOf(
                        DeepSearchEvidenceDraft(
                            statement = "Photon evidence",
                            confidence = 0.95,
                            sourcePhotonId = PhotonId("evidence-1"),
                        )
                    ),
                )
            )
        }
    }

    private class MemoryMissionRepository : DeepSearchMissionRepository {
        private val events = mutableListOf<DeepSearchMissionEvent>()

        override suspend fun loadReport() = DeepSearchMissionRepositoryLoadReport(events.toList())

        override suspend fun append(expectedRevision: Long, event: DeepSearchMissionEvent): Boolean {
            val current = events.lastOrNull()?.revision ?: 0L
            if (current != expectedRevision) return false
            events += event
            return true
        }
    }

    private class MemoryCheckpointRepository : DeepSearchCheckpointRepository {
        private val values = mutableMapOf<DeepSearchMissionId, DeepSearchStoredCheckpoint>()

        override suspend fun load(missionId: DeepSearchMissionId) =
            DeepSearchCheckpointLoadReport(values[missionId])

        override suspend fun compareAndSet(
            missionId: DeepSearchMissionId,
            expectedRevision: Long,
            updated: DeepSearchStoredCheckpoint,
        ): Boolean {
            val current = values[missionId]?.revision ?: 0L
            if (current != expectedRevision) return false
            values[missionId] = updated
            return true
        }
    }

    private class MemoryResultPhotons : DeepSearchResultPhotonPersistence {
        private val values = mutableMapOf<PhotonId, Photon>()

        override suspend fun save(photon: Photon) {
            values[photon.id] = photon
        }

        override suspend fun load(id: PhotonId): Photon? = values[id]

        override suspend fun findForMission(missionId: DeepSearchMissionId): Photon? {
            val tag = "deepsearch-mission:${missionId.value}"
            val matches = values.values.filter { tag in it.tags }
            require(matches.size <= 1)
            return matches.singleOrNull()
        }
    }
}
