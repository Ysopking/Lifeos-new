package app.lifeos.core.runtime

import app.lifeos.core.model.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ModuleEvidenceRuntimeTest {
    private class MemoryStore(var value: ModuleEvidenceSnapshot? = null) : ModuleEvidenceSnapshotStore {
        override suspend fun load() = value
        override suspend fun save(snapshot: ModuleEvidenceSnapshot) { value = snapshot }
    }

    @Test fun processingPersistsAndRestores() = runTest {
        val store = MemoryStore()
        val first = ModuleEvidenceRuntime(store)
        val record = fixtureRecord()
        first.recordProcessing(record)
        val recovered = ModuleEvidenceRuntime(store)
        recovered.restore()
        assertEquals(record, recovered.processing(record.processingId))
        assertTrue(recovered.verifyReplay(listOf(record)))
    }

    @Test fun restoreRejectsUtilityNotDerivedFromOutcomeEvidence() = runTest {
        val record = fixtureRecord()
        val badUtility = ModuleUtilitySnapshot(record.module, 1, 1, 900_000)
        val store = MemoryStore(ModuleEvidenceSnapshot(
            processing = listOf(record), couplings = emptyList(), outcomes = emptyList(), utilities = listOf(badUtility)))
        assertFailsWith<IllegalArgumentException> { ModuleEvidenceRuntime(store).restore() }
    }

    private fun fixtureRecord(): ModuleProcessingRecord {
        val module = ModuleIdentity("test-module", "1", "impl", setOf("test"))
        val trace = CausalTraceId("trace")
        val branch = PhotonBranchId("branch")
        val input = PhotonId("input")
        val state = StableCognitiveIds.stateHash("test-state")
        return ModuleProcessingRecord(
            processingId = ModuleProcessingId("processing"), traceId = trace, branchId = branch,
            module = module, inputPhotonId = input, inputRevision = 1,
            context = DeterminismContext(trace, LogicalTick(1), state, state, "runtime", "policy", 1, state),
            outputPhotonIds = emptyList(), outputStateHash = state,
        )
    }
}
