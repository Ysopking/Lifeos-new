package app.lifeos.next

import app.lifeos.core.runtime.topology.LifeOsProcessTopology
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest

class LifeOsWarmStageReadinessTest {
    @Test
    fun `stage completion becomes visible before aggregate warm report exists`() = runTest {
        val readiness = LifeOsWarmStageReadiness()
        val waiting = async { readiness.await(LifeOsStartupStage.DURABLE_GOALS) }

        readiness.onEvent(completed(LifeOsStartupStage.DURABLE_GOALS))
        waiting.await()

        assertTrue(
            LifeOsStartupStage.DURABLE_GOALS in readiness.state.value.completedStages
        )
        assertTrue(readiness.state.value.failures.isEmpty())
    }

    @Test
    fun `unrelated warm stage completion does not release requested stage`() = runTest {
        val readiness = LifeOsWarmStageReadiness()
        readiness.onEvent(completed(LifeOsStartupStage.DEEP_SEARCH))

        assertFalse(
            readiness.state.value.terminal(LifeOsStartupStage.DURABLE_GOALS)
        )
    }

    @Test
    fun `warm stage failure is terminal and await fails closed`() = runTest {
        val readiness = LifeOsWarmStageReadiness()
        readiness.onEvent(
            LifeOsStartupStageEvent.Failed(
                stage = LifeOsStartupStage.DURABLE_GOALS,
                layerIndex = 6,
                diagnosticCode = LifeOsStartupStage.DURABLE_GOALS.diagnosticCode,
                durationNanos = 1L,
                causeType = "TestFailure",
                message = "goal-restore-failed",
            )
        )

        val error = assertFailsWith<IllegalStateException> {
            readiness.await(LifeOsStartupStage.DURABLE_GOALS)
        }
        assertTrue(error.message.orEmpty().contains("BOOT-DG-001"))
    }

    @Test
    fun `critical stages cannot be awaited through warm readiness`() = runTest {
        val readiness = LifeOsWarmStageReadiness()

        assertFailsWith<IllegalArgumentException> {
            readiness.await(LifeOsStartupStage.KERNEL_BOOT)
        }
    }

    @Test
    fun `completion replaces prior failure for deterministic retry publication`() {
        val readiness = LifeOsWarmStageReadiness()
        readiness.onEvent(
            LifeOsStartupStageEvent.Failed(
                stage = LifeOsStartupStage.SELF_HEALING,
                layerIndex = 6,
                diagnosticCode = LifeOsStartupStage.SELF_HEALING.diagnosticCode,
                durationNanos = 1L,
                causeType = "TestFailure",
                message = "first-attempt",
            )
        )
        readiness.onEvent(completed(LifeOsStartupStage.SELF_HEALING))

        assertEquals(
            setOf(LifeOsStartupStage.SELF_HEALING),
            readiness.state.value.completedStages,
        )
        assertTrue(readiness.state.value.failures.isEmpty())
    }

    private fun completed(stage: LifeOsStartupStage) =
        LifeOsStartupStageEvent.Completed(
            evidence = LifeOsStartupStageEvidence(
                stage = stage,
                layerIndex = 6,
                manifestGraphFingerprint = LifeOsProcessTopology.manifestFingerprint,
                ownedManifestFingerprints = emptyList(),
            ),
            durationNanos = 1L,
        )
}
