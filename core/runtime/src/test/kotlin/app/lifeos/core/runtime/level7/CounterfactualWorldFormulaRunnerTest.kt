package app.lifeos.core.runtime.level7

import app.lifeos.core.field.world.WorldFieldVector
import app.lifeos.core.field.world.WorldNodeKind
import app.lifeos.core.field.world.WorldTargetRef
import app.lifeos.core.runtime.world.WorldFormulaExecution
import app.lifeos.core.runtime.world.WorldFormulaExecutionScope
import app.lifeos.core.runtime.world.WorldFormulaExecutionState
import app.lifeos.core.runtime.world.WorldFormulaInputSnapshot
import app.lifeos.core.runtime.world.WorldFormulaRequest
import app.lifeos.core.runtime.world.WorldFormulaStatus
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

class CounterfactualWorldFormulaRunnerTest {
    @Test
    fun productiveExecutionScopeIsRejected() = runTest {
        val request = WorldFormulaRequest(
            inputs = listOf(
                WorldFormulaInputSnapshot(
                    target = WorldTargetRef(WorldNodeKind.THOUGHT, "counterfactual-test"),
                    vector = WorldFieldVector.EMPTY,
                    sourceSnapshotFingerprint = "counterfactual-source",
                )
            ),
            interactions = emptyList(),
            equationVersion = "counterfactual-v1",
            observedAt = Instant.parse("2026-09-19T00:00:00Z"),
        )
        val runner = CounterfactualWorldFormulaRunner {
            WorldFormulaExecution(
                state = WorldFormulaExecutionState.INVALID,
                status = WorldFormulaStatus.INVALID_EQUATION,
                snapshot = null,
                persisted = false,
                message = "test",
                scope = WorldFormulaExecutionScope.PRODUCTIVE_COGNITIVE,
            )
        }

        assertFailsWith<IllegalArgumentException> {
            runner.run(
                CounterfactualWorldFormulaInput(
                    baseProductiveSnapshotId = "world-snapshot:base",
                    baseEquationVersion = request.equationVersion,
                    interventionFingerprint = "intervention-test",
                    request = request,
                )
            )
        }
    }
}
