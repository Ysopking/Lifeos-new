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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeepSearchMissionRecoveryAuditTest {
    private val now = Instant.parse("2026-09-11T20:00:00Z")

    @Test
    fun `healthy terminal mission passes boot recovery audit without source execution`() = runTest {
        val fixture = Fixture()
        val product = fixture.completeMission()

        val report = fixture.auditor().audit()

        assertTrue(report.healthy)
        assertEquals(1, report.missionsChecked)
        assertEquals(0, report.activeMissions)
        assertEquals(1, report.terminalMissions)
        assertEquals(product.photon, fixture.photons.load(product.photon.id))
    }

    @Test
    fun `terminal mission missing result photon is reported deterministically`() = runTest {
        val fixture = Fixture()
        val product = fixture.completeMission()
        fixture.photons.remove(product.photon.id)

        val report = fixture.auditor().audit()

        assertFalse(report.healthy)
        assertEquals(
            listOf("terminal-deepsearch-result-photon-is-missing"),
            report.issues.map { it.code },
        )
    }

    @Test
    fun `terminal mission missing checkpoint is reported deterministically`() = runTest {
        val fixture = Fixture()
        fixture.completeMission()
        fixture.checkpointRepo.clear()

        val report = fixture.auditor().audit()

        assertFalse(report.healthy)
        assertEquals(
            listOf("terminal-deepsearch-mission-is-missing-final-checkpoint"),
            report.issues.map { it.code },
        )
    }

    @Test
    fun `planned mission remains auditable without checkpoint or result photon`() = runTest {
        val fixture = Fixture()
        fixture.ledger.create(fixture.definition)

        val report = fixture.auditor().audit()

        assertTrue(report.healthy)
        assertEquals(1, report.missionsChecked)
        assertEquals(1, report.activeMissions)
        assertEquals(0, report.terminalMissions)
    }

    private inner class Fixture {
        val definition = definition()
        val missionRepo = MemoryMissionRepository()
        val checkpointRepo = MemoryCheckpointRepository()
        val photons = MemoryResultPhotons()
        val ledger = DeepSearchMissionLedger(missionRepo, now = { now })
        val checkpoints = DeepSearchCheckpointStore(checkpointRepo, now = { now })
        val coordinator = DeepSearchMissionCoordinator(ledger, checkpoints, photons)

        suspend fun completeMission(): DeepSearchMissionProduct = coordinator.run(definition) { _, sink, missionId ->
            searchProduct(missionId, sink)
        }

        fun auditor() = DeepSearchMissionRecoveryAuditor(
            ledger = DeepSearchMissionLedger(missionRepo, now = { now.plusSeconds(1) }),
            checkpoints = DeepSearchCheckpointStore(checkpointRepo, now = { now.plusSeconds(1) }),
            resultPhotons = photons,
        )
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
        val evidenceIds = result.evidence.mapNotNull { it.sourcePhotonId }
        val photon = Photon(
            id = PhotonId("deep-search-result_${missionId.value.removePrefix(DeepSearchMissionId.PREFIX)}"),
            content = "LIFEOS photon evidence",
            phase = PhotonPhase.CONVERGED,
            confidence = 0.95,
            provenance = Provenance(
                source = "test-deepsearch-v2",
                actor = "test",
                createdAt = now,
                parentIds = setOf(
                    PhotonId("source-chat"),
                    PhotonId("goal-deepsearch"),
                ) + evidenceIds,
            ),
            tags = setOf(
                "deepsearch-answer",
                "deepsearch-status:resolved",
                "deepsearch-work:${result.workUnitsUsed}",
                "deepsearch-mission:${missionId.value}",
            ),
        )
        return DeepSearchMissionProduct(
            photon = photon,
            result = result,
            evidencePhotonIds = evidenceIds,
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
        sourceSnapshotFingerprint = "a".repeat(64),
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

        fun clear() {
            values.clear()
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

        fun remove(id: PhotonId) {
            values.remove(id)
        }
    }
}
