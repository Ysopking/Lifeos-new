package app.lifeos.core.runtime.artifact

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonLoadReport
import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.model.Provenance
import app.lifeos.core.runtime.trace.DecisionTrace
import app.lifeos.core.runtime.trace.DecisionTraceId
import app.lifeos.core.runtime.trace.DecisionTraceLedger
import app.lifeos.core.runtime.trace.DecisionTraceRepository
import app.lifeos.core.runtime.trace.DecisionTraceRepositoryLoadReport
import app.lifeos.core.runtime.trace.LifecycleDecisionTraceRecorder
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArtifactDecisionTraceWiringTest {
    private val requestedAt = Instant.parse("2026-09-14T10:00:00Z")
    private val finalizedAt = requestedAt.plusSeconds(10)

    @Test
    fun `canonical durable finalization records lifecycle trace`() = runTest {
        val traces = DecisionTraceLedger(MemoryTraceRepository())
        val photons = MemoryPhotonRepository()
        val coordinator = ArtifactCoordinator(
            photons = photons,
            ingress = ArtifactPhotonIngress { photon ->
                photons.save(photon)
                ArtifactReentryReceipt(accepted = true, durableTaskId = "artifact-task-1")
            },
            lifecycleTraceRecorder = LifecycleDecisionTraceRecorder(traces),
        )

        val result = coordinator.finalize(
            request = request("durable-artifact"),
            contributions = contributions(),
            finalizedAt = finalizedAt,
        )

        val trace = assertNotNull(
            traces.snapshot(DecisionTraceId.create("artifact", result.artifact.request.id.value))
        )
        assertTrue(trace.nodes.any {
            it.sourceType == "artifact-photon" && it.sourceId == result.artifact.photon.id.value
        })
        assertTrue(trace.nodes.any {
            it.sourceType == "artifact-cognition-reentry" && it.sourceId == "artifact-task-1"
        })
    }

    @Test
    fun `staged non durable artifact does not record lifecycle trace`() = runTest {
        val traces = DecisionTraceLedger(MemoryTraceRepository())
        val photons = MemoryPhotonRepository()
        val coordinator = ArtifactCoordinator(
            photons = photons,
            ingress = ArtifactPhotonIngress {
                ArtifactReentryReceipt(accepted = false, durableTaskId = null)
            },
            lifecycleTraceRecorder = LifecycleDecisionTraceRecorder(traces),
        )

        val result = coordinator.finalize(
            request = request("staged-artifact"),
            contributions = contributions(),
            finalizedAt = finalizedAt,
        )

        assertNull(photons.load(result.artifact.photon.id))
        assertNull(
            traces.snapshot(DecisionTraceId.create("artifact", result.artifact.request.id.value))
        )
    }

    private fun request(id: String) = CollaborativeArtifactRequest(
        id = ArtifactId(id),
        kind = ArtifactKind.REPORT,
        title = "Decision trace wiring",
        targetMimeType = "text/markdown",
        requestedAt = requestedAt,
        requiredFields = setOf("analysis", "validation"),
    )

    private fun contributions() = listOf(
        ArtifactContribution.create(
            module = "analysis-module",
            field = "analysis",
            source = "analysis-source",
            provenance = Provenance(
                source = "analysis-evidence",
                actor = "analysis-module",
                createdAt = requestedAt.plusSeconds(1),
                parentIds = setOf(PhotonId("source-a")),
            ),
            confidence = 0.92,
            content = "analysis",
            contributedAt = requestedAt.plusSeconds(2),
        ),
        ArtifactContribution.create(
            module = "validation-module",
            field = "validation",
            source = "validation-source",
            provenance = Provenance(
                source = "validation-evidence",
                actor = "validation-module",
                createdAt = requestedAt.plusSeconds(3),
                parentIds = setOf(PhotonId("source-b")),
            ),
            confidence = 0.95,
            content = "validated",
            contributedAt = requestedAt.plusSeconds(4),
        ),
    )

    private class MemoryPhotonRepository : PhotonRepository {
        private val values = linkedMapOf<PhotonId, Photon>()

        override suspend fun save(photon: Photon) {
            values[photon.id] = photon
        }

        override suspend fun load(id: PhotonId): Photon? = values[id]
        override suspend fun loadAll(): List<Photon> = values.values.toList()
        override suspend fun loadReport(): PhotonLoadReport = PhotonLoadReport(values.values.toList(), emptyList())
        override suspend fun delete(id: PhotonId) {
            values.remove(id)
        }
    }

    private class MemoryTraceRepository : DecisionTraceRepository {
        private val traces = mutableListOf<DecisionTrace>()

        override suspend fun loadReport() = DecisionTraceRepositoryLoadReport(traces.toList())

        override suspend fun save(expectedRevision: Long, trace: DecisionTrace): Boolean {
            val current = traces.filter { it.id == trace.id }.maxOfOrNull { it.revision } ?: 0L
            if (current != expectedRevision) return false
            require(trace.revision == expectedRevision + 1L)
            traces += trace
            return true
        }
    }
}
