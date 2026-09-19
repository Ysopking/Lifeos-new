package app.lifeos.core.runtime.world

import app.lifeos.core.runtime.self.LifeOsSelfStateSnapshot
import app.lifeos.core.runtime.self.SelfHealthState
import app.lifeos.core.runtime.self.SelfLiveSourceState
import app.lifeos.core.runtime.self.SelfMemoryState
import app.lifeos.core.runtime.self.SelfObservationIssue
import app.lifeos.core.runtime.self.SelfObservationIssueKind
import app.lifeos.core.runtime.self.SelfObservationDomain
import app.lifeos.core.runtime.self.SelfPhotonState
import app.lifeos.core.runtime.self.SelfRecoveryState
import app.lifeos.core.runtime.self.SelfResourceState
import app.lifeos.core.runtime.self.SelfRuntimeState
import app.lifeos.core.runtime.self.SelfStateProjectionResult
import app.lifeos.core.runtime.self.SelfToolState
import app.lifeos.core.runtime.self.SelfWorldState
import app.lifeos.core.field.world.WorldSignalDimension
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SelfStateWorldEquationProfileTest {
    @Test
    fun unsupportedTemporalAndConflictDimensionsRemainAbsent() {
        val profile = SelfStateWorldEquationProfile()
        val prepared = profile.request(projection())

        val source = prepared.request.inputs.single { it.target.key == SelfStateWorldEquationProfile.SOURCES_KEY }
        val cognition = prepared.request.inputs.single { it.target.key == SelfStateWorldEquationProfile.COGNITION_KEY }

        assertNull(source.vector[WorldSignalDimension.TEMPORAL_FRESHNESS])
        assertNull(cognition.vector[WorldSignalDimension.CONFLICT_PRESSURE])
        assertEquals(SelfStateWorldEquationProfile.VERSION, prepared.request.equationVersion)
    }

    @Test
    fun realWorldFormulaClassifiesCriticalHealthAsCritical() = runTest {
        val profile = SelfStateWorldEquationProfile()
        val repo = InMemoryWorldSnapshotRepository()
        val evaluator = SelfStateWorldFormulaEvaluator(
            profile,
            WorldFormulaCoordinator(
                equations = InMemoryWorldEquationRegistry(listOf(profile.spec)),
                snapshots = repo,
                captureCognitiveSnapshots = false,
            )
        )

        val assessment = evaluator.evaluate(
            projection(
                health = SelfHealthState(0, 0, 1, 0, 0, 0)
            )
        )

        assertEquals(SelfStateWorldBand.CRITICAL, assessment.band)
        assertEquals(WorldFormulaExecutionState.COMPLETED, assessment.execution.state)
        assertTrue(assessment.execution.persisted)
    }

    @Test
    fun unknownHealthEvidenceRemainsObservableInsteadOfBeingCountedHealthy() = runTest {
        val profile = SelfStateWorldEquationProfile()
        val repo = InMemoryWorldSnapshotRepository()
        val evaluator = SelfStateWorldFormulaEvaluator(
            profile,
            WorldFormulaCoordinator(
                equations = InMemoryWorldEquationRegistry(listOf(profile.spec)),
                snapshots = repo,
                captureCognitiveSnapshots = false,
            )
        )

        val assessment = evaluator.evaluate(
            projection(
                health = SelfHealthState(
                    healthy = 1,
                    degraded = 0,
                    unhealthy = 0,
                    recovering = 0,
                    quarantined = 0,
                    disabled = 0,
                    unknown = 1,
                )
            )
        )

        assertTrue("HEALTH_UNKNOWN_NODES" in assessment.reasonCodes)
        assertEquals(SelfStateWorldBand.OBSERVE, assessment.band)
    }

    @Test
    fun missingAuthorityEvidenceRaisesExplicitUncertaintyWithoutInventingZeroes() = runTest {
        val profile = SelfStateWorldEquationProfile()
        val repo = InMemoryWorldSnapshotRepository()
        val evaluator = SelfStateWorldFormulaEvaluator(
            profile,
            WorldFormulaCoordinator(
                equations = InMemoryWorldEquationRegistry(listOf(profile.spec)),
                snapshots = repo,
                captureCognitiveSnapshots = false,
            )
        )
        val projection = projection(
            world = SelfWorldState(null, null, null, null, null, null, null, null),
            issues = listOf(
                SelfObservationIssue(
                    SelfObservationDomain.WORLD,
                    SelfObservationIssueKind.UNAVAILABLE,
                    "world-head-unavailable",
                )
            ),
        )

        val assessment = evaluator.evaluate(projection)

        val uncertainty = assessment.controllerVector?.get(WorldSignalDimension.UNCERTAINTY)
        assertTrue((uncertainty?.value ?: 0.0) > 0.0)
        assertTrue("UNCERTAINTY_ELEVATED" in assessment.reasonCodes)
    }

    private fun projection(
        health: SelfHealthState = SelfHealthState(1, 0, 0, 0, 0, 0),
        world: SelfWorldState = SelfWorldState(1, "world", 1, "eq-v1", "eq", "boot", "boot-fp", "cog"),
        issues: List<SelfObservationIssue> = emptyList(),
    ) = SelfStateProjectionResult(
        snapshot = LifeOsSelfStateSnapshot(
            capturedAt = Instant.parse("2026-09-19T12:00:00Z"),
            photon = SelfPhotonState(1, 1, 0, "index"),
            memory = SelfMemoryState(1, 1, 0, "memory"),
            world = world,
            runtime = SelfRuntimeState(
                topologyFingerprint = "topology",
                registeredSubsystems = setOf("runtime"),
                operationalSubsystems = setOf("runtime"),
                degradedSubsystems = emptySet(),
                unavailableSubsystems = emptySet(),
                unboundSubsystems = emptySet(),
            ),
            resource = SelfResourceState("hardware", 1.0, 1.0, 1.0, 1.0, 1.0),
            health = health,
            recovery = SelfRecoveryState(emptySet(), "recovery"),
            tools = SelfToolState(0, 0, 0, 0, 0),
            liveSources = SelfLiveSourceState(0, 0, 0, 0, "sources"),
        ),
        issues = issues,
    )

    private class InMemoryWorldSnapshotRepository : WorldFormulaSnapshotRepository {
        private val byId = linkedMapOf<String, WorldFormulaSnapshot>()

        override suspend fun save(snapshot: WorldFormulaSnapshot) {
            byId[snapshot.id] = snapshot
        }

        override suspend fun load(id: String): WorldFormulaSnapshot? = byId[id]

        override suspend fun loadLatest(): WorldFormulaSnapshot? = byId.values.lastOrNull()

        override suspend fun loadReport(): WorldFormulaSnapshotLoadReport =
            WorldFormulaSnapshotLoadReport(byId.values.toList(), emptyList())

        override suspend fun delete(id: String) {
            byId.remove(id)
        }
    }
}
