package app.lifeos.core.runtime.level7

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoundedRehydrationScaleTest {
    @Test
    fun startupWorkDependsOnActiveHeadsNotLifetimeHistory() = runTest {
        val lifetime = HistoricalScale(
            photonRevisions = 100_000,
            outcomes = 10_000,
            strategies = 5_000,
            worldSnapshots = 1_000,
        )
        val exact = RehydrationStepKind.entries.associateWith { kind ->
            ExactRehydrationResolver { ref ->
                lifetime.exactReads += 1
                "$kind:$ref:fingerprint"
            }
        }
        val plan = BoundedRehydrationPlan.create(
            RehydrationStepKind.entries.map { kind ->
                RehydrationStep(kind, "active-${kind.name.lowercase()}")
            }
        )

        val result = BoundedRehydrationExecutor(exact).execute(plan) { resolved ->
            ProcessDeathSemanticCheckpoint(
                worldHeadFingerprint = resolved.getValue(
                    RehydrationStepKind.PRODUCTIVE_WORLD_HEAD
                ).resolvedFingerprint,
                equationVersion = resolved.getValue(
                    RehydrationStepKind.WORLD_EQUATION_HEAD
                ).resolvedFingerprint,
                cycleFingerprint = resolved.getValue(
                    RehydrationStepKind.ACTIVE_BOOTENGINE_CYCLE
                ).resolvedFingerprint,
                decisionSemanticFingerprint = "decision-semantic-head",
                learningLedgerHeadFingerprint = resolved.getValue(
                    RehydrationStepKind.LEARNING_WATERMARK
                ).resolvedFingerprint,
            )
        }

        assertEquals(RehydrationStepKind.entries.size, result.states.size)
        assertEquals(RehydrationStepKind.entries.size, lifetime.exactReads)
        assertFalse(lifetime.fullHistoryScanCalled)
        assertTrue(lifetime.photonRevisions == 100_000)
        assertTrue(lifetime.outcomes == 10_000)
        assertTrue(lifetime.strategies == 5_000)
        assertTrue(lifetime.worldSnapshots == 1_000)
        assertFalse(plan.fullVaultScanAllowed)
    }

    private data class HistoricalScale(
        val photonRevisions: Int,
        val outcomes: Int,
        val strategies: Int,
        val worldSnapshots: Int,
        var exactReads: Int = 0,
        var fullHistoryScanCalled: Boolean = false,
    )
}
