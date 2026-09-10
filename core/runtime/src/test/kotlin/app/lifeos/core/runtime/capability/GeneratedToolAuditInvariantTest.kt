package app.lifeos.core.runtime.capability

import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeneratedToolAuditInvariantTest {
    private val t0 = Instant.parse("2026-09-10T15:00:00Z")

    @Test
    fun `audit chain preserves exact state continuity across ordinary lifecycle transitions`() = runTest {
        val registry = GeneratedToolRegistry(now = { t0 })
        registry.register(record(GeneratedToolState.GENERATED))
        registry.transition(TOOL_ID, GeneratedToolState.BUILT, message = "built")
        registry.transition(TOOL_ID, GeneratedToolState.TESTED, message = "tested")
        registry.transition(TOOL_ID, GeneratedToolState.VERIFIED, confidence = 0.95, message = "verified")
        registry.transition(TOOL_ID, GeneratedToolState.TRIAL, message = "trial")

        val audit = registry.auditSnapshot(TOOL_ID)
        assertEquals(5, audit.size)
        audit.zipWithNext().forEach { (previous, next) ->
            assertEquals(previous.id, next.previousEntryId)
            assertEquals(previous.toState, next.fromState)
            assertEquals(previous.afterRecordFingerprint, next.beforeRecordFingerprint)
        }
        assertTrue(registry.verifyAuditChain(TOOL_ID))
    }

    private fun record(state: GeneratedToolState) = GeneratedToolRecord(
        manifest = GeneratedToolManifest(
            toolId = TOOL_ID,
            sourceCapability = CapabilityId("audit.capability"),
            sourceHash = "source-hash",
            buildHash = "a".repeat(64),
            permissions = emptySet(),
            generatedAt = t0,
        ),
        state = state,
    )

    companion object {
        private const val TOOL_ID = "audit-tool"
    }
}
