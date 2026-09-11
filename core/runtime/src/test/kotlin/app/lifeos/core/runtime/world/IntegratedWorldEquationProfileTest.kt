package app.lifeos.core.runtime.world

import app.lifeos.core.field.FieldConvergenceEngine
import app.lifeos.core.field.world.WorldFieldEquation
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.Provenance
import app.lifeos.core.runtime.field.DefaultPhotonFieldRequestFactory
import app.lifeos.core.runtime.field.FieldWorldSignalProjector
import app.lifeos.core.runtime.field.FieldWorldTopologyProjector
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class IntegratedWorldEquationProfileTest {
    private val time = Instant.parse("2026-09-11T08:30:00Z")

    @Test
    fun `typed field links become a valid deterministic multi-field world request`() = runTest {
        val photon = photon()
        val fieldRequest = DefaultPhotonFieldRequestFactory().create(photon)
        val fieldResult = FieldConvergenceEngine().converge(fieldRequest)
        val signals = FieldWorldSignalProjector().project(photon, fieldRequest, fieldResult)
        val links = FieldWorldTopologyProjector().project(fieldRequest, fieldResult)
        val profile = IntegratedWorldEquationProfile()
        val request = profile.request(signals, links, observedAt = time, photonId = photon.id)
        val equation = WorldFieldEquation(profile.spec)

        assertTrue(links.isNotEmpty())
        assertTrue(request.interactions.isNotEmpty())
        assertEquals(emptyList(), equation.validate(request.buildGraph()))
        assertEquals(
            request.interactions.map { it.fingerprint() },
            request.interactions.sortedBy { it.fingerprint() }.map { it.fingerprint() },
        )

        val repository = RecordingRepository()
        val execution = WorldFormulaCoordinator(
            equations = InMemoryWorldEquationRegistry(listOf(profile.spec)),
            snapshots = repository,
        ).evaluate(request)

        assertEquals(WorldFormulaExecutionState.COMPLETED, execution.state)
        assertTrue(execution.persisted)
        assertNotNull(execution.snapshot)
        assertTrue(execution.snapshot!!.iterations.isNotEmpty())
        assertEquals(execution.snapshot, repository.load(execution.snapshot!!.id))
    }

    private fun photon(): Photon = Photon(
        id = PhotonId("v4-integrated-world-photon"),
        revision = 1,
        content = "combine typed field signals",
        semanticMass = 1.0,
        energy = 0.8,
        confidence = 0.88,
        provenance = Provenance(
            source = "v4-integration-test",
            actor = "test",
            createdAt = time,
        ),
    )

    private class RecordingRepository : WorldFormulaSnapshotRepository {
        private val values = linkedMapOf<String, WorldFormulaSnapshot>()

        override suspend fun save(snapshot: WorldFormulaSnapshot) {
            values[snapshot.id] = snapshot
        }

        override suspend fun load(id: String): WorldFormulaSnapshot? = values[id]

        override suspend fun loadLatest(): WorldFormulaSnapshot? = values.values.lastOrNull()

        override suspend fun loadReport(): WorldFormulaSnapshotLoadReport =
            WorldFormulaSnapshotLoadReport(values.values.toList(), emptyList())

        override suspend fun delete(id: String) {
            values.remove(id)
        }
    }
}
