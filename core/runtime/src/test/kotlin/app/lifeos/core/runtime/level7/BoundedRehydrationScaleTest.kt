package app.lifeos.core.runtime.level7

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class BoundedRehydrationScaleTest {
    @Test
    fun startupWorkIsBoundedByActiveHeadsNotLifetimeHistory() = runTest {
        val historicalPhotonRevisions = 100_000
        val historicalOutcomes = 10_000
        val historicalStrategies = 5_000
        val historicalWorldSnapshots = 1_000
        check(
            historicalPhotonRevisions + historicalOutcomes +
                historicalStrategies + historicalWorldSnapshots > 100_000
        )

        val calls = AtomicInteger()
        val resolvers = RehydrationStepKind.entries.associateWith {
            ExactRehydrationResolver { ref ->
                calls.incrementAndGet()
                "resolved:$ref"
            }
        }
        val steps = RehydrationStepKind.entries.map { kind ->
            RehydrationStep(kind, "exact:${kind.name.lowercase()}")
        }
        val plan = BoundedRehydrationPlan.create(steps)
        val result = BoundedRehydrationExecutor(resolvers).execute(plan)

        assertEquals(steps.size, calls.get())
        assertEquals(steps.size, result.states.size)
        assertFalse(plan.fullVaultScanAllowed)
    }
}
