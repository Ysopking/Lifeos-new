package app.lifeos.core.runtime

import app.lifeos.core.model.CognitiveBranchSemanticOutcome
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
import kotlin.test.assertNotEquals
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

    private fun module(
        id: String,
        content: String,
        version: String = "1.0.0",
        semanticOutcome: CognitiveBranchSemanticOutcome = CognitiveBranchSemanticOutcome.UNSPECIFIED,
        counter: () -> Unit,
    ): CognitiveModule = CognitiveModule(
        descriptor = CognitiveModuleDescriptor(
            identity = ModuleIdentity(id, version, "impl-$id-$version"),
            acceptedMimeTypes = setOf("text/*"),
            preferredTags = setOf("finance"),
            semanticHints = setOf("payment", "request", "due"),
            expectedInformationGain = 0.7,
            estimatedCost = 0.2,
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
                semanticOutcome = semanticOutcome,
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
    fun semanticOutcomeSurvivesCausalLedgerPersistence() = runTest {
        val repository = object : app.lifeos.core.model.PhotonRepository {
            private val data = linkedMapOf<PhotonId, Photon>()
            override suspend fun save(photon: Photon) { data[photon.id] = photon }
            override suspend fun load(id: PhotonId): Photon? = data[id]
            override suspend fun loadReport() = app.lifeos.core.model.PhotonLoadReport(data.values.toList(), emptyList())
            override suspend fun loadAll(): List<Photon> = data.values.toList()
            override suspend fun delete(id: PhotonId) { data.remove(id) }
        }
        val durableLedger = PhotonBackedCausalLedgerStore(repository)
        val first = CausalCognitionEngine(ledger = durableLedger).process(
            source,
            listOf(
                module(
                    id = "legal",
                    content = "supported-obligation",
                    semanticOutcome = CognitiveBranchSemanticOutcome.SUPPORTED,
                ) {},
            ),
        )
        val recovered = PhotonBackedCausalLedgerStore(repository).load(first.traceId)

        assertNotNull(recovered)
        assertEquals(
            CognitiveBranchSemanticOutcome.SUPPORTED,
            recovered.branches.single().semanticOutcome,
        )
    }

    @Test
    fun aFreshLedgerReplaysTheSameSemanticRunToTheSamePhotonIds() = runTest {
        val modules = listOf(
            module("debt", "due-date=tomorrow") {},
            module("legal", "obligation=payment-request") {},
        )

        val first = CausalCognitionEngine().process(source, modules)
        val second = CausalCognitionEngine().process(source, modules.reversed())

        assertEquals(
            first.emittedPhotons.map { it.id.value }.sorted(),
            second.emittedPhotons.map { it.id.value }.sorted(),
        )
        assertEquals(first.traceId, second.traceId)
    }

    @Test
    fun moduleVersionChangeCreatesNewTraceForHistoricalReinterpretation() = runTest {
        val ledger = InMemoryCausalLedgerStore()
        val engine = CausalCognitionEngine(ledger = ledger)

        val first = engine.process(source, listOf(module("legal", "v1", version = "1") {}))
        val second = engine.process(source, listOf(module("legal", "v2", version = "2") {}))

        assertFalse(first.replayed)
        assertFalse(second.replayed)
        assertNotEquals(first.traceId, second.traceId)
    }

    @Test
    fun policyVersionChangeCreatesNewTrace() = runTest {
        val modules = listOf(module("legal", "fact") {})
        val first = CausalCognitionEngine(
            config = CausalCognitionEngineConfig(policyVersion = "policy-v1"),
        ).process(source, modules)
        val second = CausalCognitionEngine(
            config = CausalCognitionEngineConfig(policyVersion = "policy-v2"),
        ).process(source, modules)

        assertNotEquals(first.traceId, second.traceId)
    }
}
