package app.lifeos.core.runtime

import app.lifeos.core.model.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModuleWorkspaceServiceTest {
    @Test fun outcomeFlowsIntoWorkspaceUtilityAndReplay() = runTest {
        val evidence = ModuleEvidenceRuntime()
        val record = fixtureRecord()
        evidence.recordProcessing(record)
        val learning = ModuleOutcomeLearning(evidence)
        learning.observe(record.processingId, 500_000, accepted = true)
        val workspace = evidence.workspace(listOf(record.module))
        assertEquals(1, workspace.entries.single().utility.observations)
        assertEquals(500_000, workspace.entries.single().utility.meanUtilityMicros)
        assertTrue(evidence.verifyReplay(listOf(record)))
    }

    private fun fixtureRecord(): ModuleProcessingRecord {
        val module = ModuleIdentity("workspace-module", "1", "impl", setOf("workspace"))
        val trace = CausalTraceId("workspace-trace")
        val branch = PhotonBranchId("workspace-branch")
        val state = CognitiveStateHash("workspace-state")
        return ModuleProcessingRecord(
            ModuleProcessingId("workspace-processing"), trace, branch, module, PhotonId("workspace-input"), 1,
            DeterminismContext(trace, LogicalTick(1), state, state, "runtime", "policy", 1, state),
            emptyList(), state,
        )
    }
}
