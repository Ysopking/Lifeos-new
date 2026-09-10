package app.lifeos.core.runtime.capability

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest

class GeneratedToolBootStateRehydratorTest {
    @Test
    fun `first boot restores and retry verifies exact durable state`() = runTest {
        val durable = registeredState("tool-boot")
        val repository = StaticStateRepository(listOf(durable))
        val tools = GeneratedToolRegistry()
        val ledger = GeneratedToolTrialLedger()
        val rehydrator = GeneratedToolBootStateRehydrator(repository, tools, ledger)

        val first = rehydrator.rehydrateOrVerify()
        val retry = rehydrator.rehydrateOrVerify()

        assertEquals(1, first.restoredTools)
        assertEquals(first, retry)
        assertEquals(listOf(durable.record), tools.snapshot())
        assertEquals(durable.auditEntries, tools.auditSnapshot("tool-boot"))
        assertEquals(durable.trialEvidence, ledger.evidence("tool-boot"))
    }

    @Test
    fun `retry fails closed when in-memory lifecycle drifted after restore`() = runTest {
        val durable = registeredState("tool-drift")
        val repository = StaticStateRepository(listOf(durable))
        val tools = GeneratedToolRegistry()
        val ledger = GeneratedToolTrialLedger()
        val rehydrator = GeneratedToolBootStateRehydrator(repository, tools, ledger)

        rehydrator.rehydrateOrVerify()
        tools.transition("tool-drift", GeneratedToolState.REJECTED, message = "runtime-drift")

        val failure = try {
            rehydrator.rehydrateOrVerify()
            null
        } catch (error: IllegalArgumentException) {
            error
        }
        assertNotNull(failure)
    }

    private suspend fun registeredState(toolId: String): GeneratedToolPersistentState {
        val createdAt = Instant.parse("2026-09-10T12:00:00Z")
        val tools = GeneratedToolRegistry(now = { createdAt })
        val record = GeneratedToolRecord(
            manifest = GeneratedToolManifest(
                toolId = toolId,
                sourceCapability = CapabilityId("text.normalize"),
                sourceHash = "source-$toolId",
                buildHash = null,
                permissions = emptySet(),
                generatedAt = createdAt,
                requiredInputs = setOf("text"),
                requiredOutputs = setOf("normalized-text"),
            ),
            state = GeneratedToolState.GENERATED,
        )
        tools.register(record)
        return GeneratedToolPersistentState(
            record = record,
            auditEntries = tools.auditSnapshot(toolId),
            trialEvidence = GeneratedToolTrialEvidence(toolId, emptyList()),
        )
    }

    private class StaticStateRepository(
        private val states: List<GeneratedToolPersistentState>,
    ) : GeneratedToolStateRepository {
        override suspend fun loadAll(): List<GeneratedToolPersistentState> = states

        override suspend fun persistLifecycle(
            record: GeneratedToolRecord,
            auditEntries: List<GeneratedToolAuditEntry>,
            promotionEvidence: GeneratedToolPromotionEvidence?,
        ) = error("test repository is read-only")

        override suspend fun persistTrialEvidence(evidence: GeneratedToolTrialEvidence) =
            error("test repository is read-only")
    }
}
