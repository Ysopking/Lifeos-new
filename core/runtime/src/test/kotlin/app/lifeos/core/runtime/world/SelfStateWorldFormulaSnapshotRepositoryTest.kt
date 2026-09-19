package app.lifeos.core.runtime.world

import app.lifeos.core.field.world.WorldFieldVector
import app.lifeos.core.field.world.WorldNodeKind
import app.lifeos.core.field.world.WorldTargetRef
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SelfStateWorldFormulaSnapshotRepositoryTest {
    @Test
    fun selfObservationSnapshotsStayInsideEphemeralAnalysisRepository() = runTest {
        val repository = SelfStateWorldFormulaSnapshotRepository()
        val profile = SelfStateWorldEquationProfile()
        val coordinator = WorldFormulaCoordinator(
            equations = InMemoryWorldEquationRegistry(listOf(profile.spec)),
            snapshots = repository,
            captureCognitiveSnapshots = false,
        )
        val projection = app.lifeos.core.runtime.self.SelfStateProjectionResult(
            snapshot = app.lifeos.core.runtime.self.LifeOsSelfStateSnapshot(
                capturedAt = Instant.parse("2026-09-19T12:00:00Z"),
                photon = app.lifeos.core.runtime.self.SelfPhotonState(1, 1, 0, "index"),
                memory = app.lifeos.core.runtime.self.SelfMemoryState(1, 1, 0, "memory"),
                world = app.lifeos.core.runtime.self.SelfWorldState(
                    1, "world", 1, "eq-v1", "eq", "boot", "boot-fp", "cog"
                ),
                runtime = app.lifeos.core.runtime.self.SelfRuntimeState(
                    "topology", setOf("runtime"), setOf("runtime"), emptySet(), emptySet(), emptySet()
                ),
                resource = app.lifeos.core.runtime.self.SelfResourceState("hardware", 1.0, 1.0, 1.0, 1.0, 1.0),
                health = app.lifeos.core.runtime.self.SelfHealthState(1, 0, 0, 0, 0, 0),
                recovery = app.lifeos.core.runtime.self.SelfRecoveryState(emptySet(), "recovery"),
                tools = app.lifeos.core.runtime.self.SelfToolState(0, 0, 0, 0, 0),
                liveSources = app.lifeos.core.runtime.self.SelfLiveSourceState(0, 0, 0, 0, "sources"),
            ),
            issues = emptyList(),
        )
        val prepared = profile.request(projection)
        val execution = coordinator.evaluate(prepared.request)

        assertEquals(WorldFormulaExecutionState.COMPLETED, execution.state)
        assertEquals(execution.snapshot, repository.loadLatest())
        assertEquals(1, repository.loadReport().snapshots.size)
    }
}
