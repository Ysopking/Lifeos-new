package app.lifeos.next.kernel

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.runtime.deepsearch.DeepSearchBranch
import app.lifeos.core.runtime.deepsearch.DeepSearchBranchId
import app.lifeos.core.runtime.deepsearch.DeepSearchEvidenceId
import app.lifeos.core.runtime.deepsearch.DeepSearchHypothesis
import app.lifeos.core.runtime.deepsearch.DeepSearchHypothesisId
import app.lifeos.core.runtime.deepsearch.DeepSearchPermissionState
import app.lifeos.core.runtime.deepsearch.DeepSearchRequest
import app.lifeos.core.runtime.deepsearch.DeepSearchScore
import app.lifeos.core.runtime.policy.OwnerPolicyEvent
import app.lifeos.core.runtime.policy.OwnerPolicyLedger
import app.lifeos.core.runtime.policy.OwnerPolicyRepository
import app.lifeos.core.runtime.policy.OwnerPolicyRepositoryLoadReport
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AndroidWebDeepSearchSourceTest {
    @Test
    fun htmlParserExtractsBoundedHttpsEvidence() {
        val html = """
            <html><body>
              <a class="result__a" href="https://example.org/lifeos">LIFEOS evidence</a>
              <a class="result__snippet">LIFEOS web research result with provenance.</a>
              <a class="result__a" href="http://insecure.example/nope">LIFEOS insecure</a>
            </body></html>
        """.trimIndent()

        val results = WebSearchHtmlParser.parse(html, "LIFEOS evidence")

        assertEquals(1, results.size)
        assertEquals("https://example.org/lifeos", results.single().url)
        assertTrue(results.single().snippet.contains("web research"))
    }

    @Test
    fun ownerPermissionIsExplicitDurableAndRevocable() = runTest {
        val now = Instant.parse("2026-09-14T12:00:00Z")
        val ledger = OwnerPolicyLedger(MemoryOwnerPolicyRepository()) { now }
        val gate = OwnerPolicyDeepSearchPermissionGate(ledger)
        val descriptor = source(ledger, now = now).descriptor

        assertEquals(DeepSearchPermissionState.DENIED, gate.permissionFor(descriptor))

        WebDeepSearchOwnerPolicy.enable(ledger, now.minusSeconds(1))
        assertEquals(DeepSearchPermissionState.GRANTED, gate.permissionFor(descriptor))

        WebDeepSearchOwnerPolicy.disable(ledger)
        assertEquals(DeepSearchPermissionState.DENIED, gate.permissionFor(descriptor))
    }

    @Test
    fun adoptedWebHitBecomesIdempotentEvidencePhotonReferencedByDeepSearch() = runTest {
        val now = Instant.parse("2026-09-14T12:00:00Z")
        val ledger = OwnerPolicyLedger(MemoryOwnerPolicyRepository()) { now }
        WebDeepSearchOwnerPolicy.enable(ledger, now.minusSeconds(1))
        val photons = linkedMapOf<PhotonId, Photon>()
        var persisted = 0
        var networkCalls = 0
        val source = source(
            ledger = ledger,
            now = now,
            transport = WebSearchTransport { _, _ ->
                networkCalls += 1
                listOf(
                    WebSearchHit(
                        title = "LIFEOS Web evidence",
                        url = "https://example.org/evidence",
                        snippet = "A bounded external fact used by DeepSearch.",
                        confidence = 0.82,
                    )
                )
            },
            loadPhoton = photons::get,
            persistPhoton = { photon ->
                persisted += 1
                photons[photon.id] = photon
                photon
            },
        )
        val request = DeepSearchRequest("LIFEOS Web evidence")
        val branch = rootBranch(request)

        val first = source.expand(request, branch).single()
        val evidenceId = assertNotNull(first.evidence.single().sourcePhotonId)
        val photon = assertNotNull(photons[evidenceId])
        assertEquals("https://example.org/evidence", photon.provenance.source)
        assertEquals(WebDeepSearchOwnerPolicy.SOURCE_ID, photon.provenance.actor)
        assertTrue("web-evidence" in photon.tags)
        assertTrue(photon.content.contains("Source: https://example.org/evidence"))
        assertEquals(1, persisted)

        val second = source.expand(request, branch).single()
        assertEquals(evidenceId, second.evidence.single().sourcePhotonId)
        assertEquals(1, persisted, "identical Web content must reuse its immutable Photon")
        assertEquals(2, networkCalls, "a new search may re-observe the same persisted evidence")
    }

    @Test
    fun revocationWinsImmediatelyBeforeNetworkEffect() = runTest {
        val now = Instant.parse("2026-09-14T12:00:00Z")
        val ledger = OwnerPolicyLedger(MemoryOwnerPolicyRepository()) { now }
        WebDeepSearchOwnerPolicy.enable(ledger, now.minusSeconds(1))
        var networkCalls = 0
        val source = source(
            ledger = ledger,
            now = now,
            transport = WebSearchTransport { _, _ ->
                networkCalls += 1
                emptyList()
            },
        )
        WebDeepSearchOwnerPolicy.disable(ledger)
        val request = DeepSearchRequest("revoked web query")

        assertFailsWith<IllegalStateException> {
            source.expand(request, rootBranch(request))
        }
        assertEquals(0, networkCalls)
    }

    private fun source(
        ledger: OwnerPolicyLedger,
        now: Instant,
        transport: WebSearchTransport = WebSearchTransport { _, _ -> emptyList() },
        loadPhoton: suspend (PhotonId) -> Photon? = { null },
        persistPhoton: suspend (Photon) -> Photon = { it },
    ) = AndroidWebDeepSearchSource(
        ownerPolicy = ledger,
        transport = transport,
        loadPhoton = loadPhoton,
        persistPhoton = persistPhoton,
        now = { now },
    )

    private fun rootBranch(request: DeepSearchRequest) = DeepSearchBranch(
        id = DeepSearchBranchId("web-test-root"),
        requestId = request.id,
        parentId = null,
        sourceId = "root",
        depth = 0,
        hypothesis = DeepSearchHypothesis(
            id = DeepSearchHypothesisId("web-test-hypothesis"),
            requestId = request.id,
            statement = request.query,
            semanticTerms = request.queryTerms,
            confidence = 0.5,
            evidenceIds = setOf(DeepSearchEvidenceId("web-test-seed")),
        ),
        score = DeepSearchScore(
            relevance = 0.5,
            evidenceStrength = 0.5,
            sourceReliability = 0.5,
            novelty = 0.5,
            depthCost = 0.0,
            contradictionPenalty = 0.0,
            total = 0.5,
        ),
    )

    private class MemoryOwnerPolicyRepository : OwnerPolicyRepository {
        private val events = mutableListOf<OwnerPolicyEvent>()

        override suspend fun loadReport() = OwnerPolicyRepositoryLoadReport(events.toList())

        override suspend fun append(expectedRevision: Long, event: OwnerPolicyEvent): Boolean {
            val current = events.lastOrNull()?.revision ?: 0L
            if (current != expectedRevision) return false
            require(event.revision == expectedRevision + 1L)
            events += event
            return true
        }
    }
}
