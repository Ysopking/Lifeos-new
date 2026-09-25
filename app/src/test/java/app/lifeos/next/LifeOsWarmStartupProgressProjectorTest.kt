package app.lifeos.next

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LifeOsWarmStartupProgressProjectorTest {
    @Test
    fun `critical events do not fabricate a warm report`() {
        val result = LifeOsWarmStartupProgressProjector.project(
            current = null,
            event = LifeOsStartupStageEvent.Started(
                stage = LifeOsStartupStage.KERNEL_BOOT,
                layerIndex = 3,
            ),
        )

        assertNull(result)
    }

    @Test
    fun `warm completion becomes observable immediately`() {
        val result = LifeOsWarmStartupProgressProjector.project(
            current = null,
            event = completed(LifeOsStartupStage.DURABLE_GOALS),
        )

        assertEquals(
            setOf(LifeOsStartupStage.DURABLE_GOALS),
            result?.completedStages,
        )
        assertEquals(emptyList(), result?.failures)
    }

    @Test
    fun `independent warm stages accumulate without waiting for whole dag`() {
        val first = LifeOsWarmStartupProgressProjector.project(
            current = null,
            event = completed(LifeOsStartupStage.DURABLE_GOALS),
        )
        val second = LifeOsWarmStartupProgressProjector.project(
            current = first,
            event = completed(LifeOsStartupStage.DEEP_SEARCH),
        )

        assertEquals(
            setOf(
                LifeOsStartupStage.DEEP_SEARCH,
                LifeOsStartupStage.DURABLE_GOALS,
            ),
            second?.completedStages,
        )
    }

    @Test
    fun `warm failure is attributed without erasing successful siblings`() {
        val first = LifeOsWarmStartupProgressProjector.project(
            current = null,
            event = completed(LifeOsStartupStage.DURABLE_GOALS),
        )
        val second = LifeOsWarmStartupProgressProjector.project(
            current = first,
            event = LifeOsStartupStageEvent.Failed(
                stage = LifeOsStartupStage.SELF_HEALING,
                layerIndex = 6,
                diagnosticCode = LifeOsStartupStage.SELF_HEALING.diagnosticCode,
                durationNanos = 10L,
                causeType = "TestFailure",
                message = "healing-unavailable",
            ),
        )

        assertEquals(
            setOf(LifeOsStartupStage.DURABLE_GOALS),
            second?.completedStages,
        )
        assertEquals(
            listOf(LifeOsStartupStage.SELF_HEALING),
            second?.failures?.map { it.stage },
        )
    }

    private fun completed(stage: LifeOsStartupStage) =
        LifeOsStartupStageEvent.Completed(
            evidence = LifeOsStartupStageEvidence(
                stage = stage,
                layerIndex = 6,
                manifestGraphFingerprint = "manifest",
                ownedManifestFingerprints = emptyList(),
            ),
            durationNanos = 1L,
        )
}
