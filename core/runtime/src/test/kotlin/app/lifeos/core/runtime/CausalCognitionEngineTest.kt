package app.lifeos.core.runtime

import app.lifeos.core.model.CognitiveBranchStatus
import app.lifeos.core.model.ModuleIdentity
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.Provenance
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CausalCognitionEngineTest {
    private val source = Photon(
        id = PhotonId("source-1"),
        content = "A payment request is due tomorrow",
        mimeType = "text/plain",
        tags = setOf("finance"),
        provenance = Provenance(
            source = "test",
            actor = "user",
            createdAt = Instant.parse("2026-09-12T00:00:00Z"),
        ),
    )

    private fun module(id: String, content: String, counter: () -> Unit): CognitiveModule = CognitiveModule(
        descriptor = CognitiveModuleDescriptor(
            identity = ModuleIdentity(id, "1.0.0", "impl-$id"),
            acceptedMimeTypes = setOf("text/*"),
            preferredTags = setOf("finance"),
        ),
        processor = CognitiveModuleProcessor { _, _ ->
            counter()
            CognitiveModuleResult(
                outputPhotons = listOf(
                    Photon(
                        content = content,
                        mimeType = "application/vnd.lifeos.fact+text",
                        provenance = Provenance(
                            source = id,
                            actor = "lifeos",
                            createdAt = Instant.parse("2026-09-12T00:00:01Z"),
                        ),
                    ),
                ),
                explanation = "$id extracted one fact",
            )
        },
    )

    @Test
    fun multipleModulesCreateTraceableBranchesAndOneIntegratedPhoton() = runTest {
        var executions = 0
        val modules = listOf(
            module("debt", "due-date=tomorrow") { executions += 1 },
            module("legal", "obligation=payment-request") { executions += 1 },
        )
        val ledger = InMemoryCausalLedgerStore()
        val engine = CausalCognitionEngine(ledger = ledger)

        val first = engine.process(source, modules)
        val replay = engine.process(source, modules)

        assertFalse(first.replayed)
        assertTrue(replay.replayed)
        assertEquals(2, executions)
        assertEquals(2, first.ledgerEntry.branches.size)
        assertTrue(first.ledgerEntry.branches.all { it.status == CognitiveBranchStatus.CONVERGED })
        assertEquals(2, first.ledgerEntry.processingRecords.size)
        assertEquals(3, first.emittedPhotons.size)

        val integration = assertNotNull(first.ledgerEntry.integration)
        assertEquals(2, integration.contributingBranchIds.size)
        val integrated = first.emittedPhotons.single { it.id == integration.integratedPhotonId }
        assertTrue(source.id in integrated.provenance.parentIds)
        val derived = first.emittedPhotons.filterNot { it.id == integrated.id }
        assertTrue(derived.all { source.id in it.provenance.parentIds })
        assertTrue(derived.all { photon -> photon.tags.any { it.startsWith("module:") } })
        assertTrue(derived.all { it.id.value.startsWith("derived-") })
        assertTrue(integrated.id.value.startsWith("integration-"))
        assertTrue(first.emittedPhotons.all { it.id.value.matches(Regex("[A-Za-z0-9_-]{1,128}")) })
    }

    @Test
    fun aFreshLedgerReplaysTheSameSemanticRunToTheSamePhotonIds() = runTest {
        val modules = listOf(
            module("debt", "due-date=tomorrow") {},
            module("legal", "obligation=payment-request") {},
        )

        val first = CausalCognitionEngine().process(source, modules)
        val second = CausalCognitionEngine().process(source, modules)

        assertEquals(
            first.emittedPhotons.map { it.id.value }.sorted(),
            second.emittedPhotons.map { it.id.value }.sorted(),
        )
        assertEquals(first.traceId, second.traceId)
    }
}
