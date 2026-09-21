package app.lifeos.core.runtime.web

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrowserReadRuntimeTest {
    @Test
    fun `HTML read composes acquisition ingest claims and citations without action authority`() = runTest {
        val resource = WebResourceIdentity.parse("https://example.com/page")
        val transport = SequencedTransport(
            WebAcquisitionTransportResponse(
                statusCode = 200,
                contentType = "text/html",
                body = "<p>First source sentence.</p><p>Second source sentence.</p>".encodeToByteArray(),
            )
        )
        val request = request(resource)

        val result = BrowserReadRuntime(WebAcquisitionRuntime(transport)).read(request)

        assertEquals(BrowserReadOutcome.READ, result.outcome)
        assertEquals(resource, result.finalResource)
        val document = assertNotNull(result.document)
        val claims = assertNotNull(result.claims)
        val citations = assertNotNull(result.citations)
        assertEquals(WebIngestState.INGESTED_TEXT, document.state)
        assertEquals(2, claims.candidates.size)
        assertEquals(2, citations.claimNodes.size)
        assertEquals(document.fingerprint, citations.sourceDocumentFingerprint)
        assertEquals(claims.fingerprint, citations.sourceExtractionFingerprint)
        assertFalse(request.navigationAuthority)
        assertFalse(request.formSubmissionAuthority)
        assertFalse(request.downloadAuthority)
        assertFalse(request.uploadAuthority)
        assertFalse(request.permissionAuthority)
        assertFalse(request.executionAuthority)
        assertFalse(result.truthAuthority)
        assertFalse(result.evidenceAuthority)
        assertFalse(result.navigationAuthority)
        assertFalse(result.executionAuthority)
    }

    @Test
    fun `redirect final resource becomes exact read and citation identity`() = runTest {
        val start = WebResourceIdentity.parse("https://example.com/start")
        val finish = WebResourceIdentity.parse("https://example.com/final")
        val transport = SequencedTransport(
            WebAcquisitionTransportResponse(
                statusCode = 302,
                location = "/final#ignored",
            ),
            WebAcquisitionTransportResponse(
                statusCode = 200,
                contentType = "text/plain",
                body = "Redirected source sentence.".encodeToByteArray(),
            ),
        )

        val result = BrowserReadRuntime(WebAcquisitionRuntime(transport)).read(request(start))

        assertEquals(BrowserReadOutcome.READ, result.outcome)
        assertEquals(finish, result.finalResource)
        assertEquals(finish.id, result.document?.resourceId)
        assertEquals(finish.canonicalUrl, result.citations?.documentNode?.canonicalUrl)
        assertEquals(listOf(start.id, finish.id), transport.requests.map { it.resource.id })
    }

    @Test
    fun `not modified is explicit and does not invent a document`() = runTest {
        val resource = WebResourceIdentity.parse("https://example.com/page")
        val result = BrowserReadRuntime(
            WebAcquisitionRuntime(
                SequencedTransport(
                    WebAcquisitionTransportResponse(
                        statusCode = 304,
                        etag = "\"v2\"",
                    )
                )
            )
        ).read(request(resource))

        assertEquals(BrowserReadOutcome.NOT_MODIFIED, result.outcome)
        assertNull(result.document)
        assertNull(result.claims)
        assertNull(result.citations)
        assertEquals(WebAcquisitionOutcome.NOT_MODIFIED, result.acquisitionReceipt.outcome)
    }

    @Test
    fun `HTTP error remains acquisition failure and error body is not promoted`() = runTest {
        val resource = WebResourceIdentity.parse("https://example.com/missing")
        val result = BrowserReadRuntime(
            WebAcquisitionRuntime(
                SequencedTransport(
                    WebAcquisitionTransportResponse(
                        statusCode = 404,
                        contentType = "text/html",
                        body = "<h1>missing</h1>".encodeToByteArray(),
                    )
                )
            )
        ).read(request(resource))

        assertEquals(BrowserReadOutcome.ACQUISITION_FAILED, result.outcome)
        assertNull(result.document)
        assertNull(result.claims)
        assertNull(result.citations)
        assertEquals(WebAcquisitionOutcome.HTTP_ERROR, result.acquisitionReceipt.outcome)
    }

    @Test
    fun `PDF read preserves opaque binary and produces no fabricated claims`() = runTest {
        val resource = WebResourceIdentity.parse("https://example.com/doc.pdf")
        val result = BrowserReadRuntime(
            WebAcquisitionRuntime(
                SequencedTransport(
                    WebAcquisitionTransportResponse(
                        statusCode = 200,
                        contentType = "application/pdf",
                        body = "%PDF-1.7 fixture".encodeToByteArray(),
                    )
                )
            )
        ).read(request(resource))

        assertEquals(BrowserReadOutcome.READ, result.outcome)
        assertEquals(WebIngestState.BINARY_OPAQUE, result.document?.state)
        assertTrue(assertNotNull(result.claims).candidates.isEmpty())
        assertTrue(assertNotNull(result.citations).claimNodes.isEmpty())
        assertTrue(result.citations!!.edges.isEmpty())
    }

    @Test
    fun `request identity includes acquisition ingest and claim policies`() {
        val resource = WebResourceIdentity.parse("https://example.com/page")
        val acquisition = WebAcquisitionRequest.create(resource)
        val first = BrowserReadRequest.create(
            acquisitionRequest = acquisition,
            ingestPolicy = WebIngestPolicy(maxTextChars = 100),
            claimPolicy = WebClaimExtractionPolicy(maxClaims = 10, minClaimChars = 2),
        )
        val changedIngest = BrowserReadRequest.create(
            acquisitionRequest = acquisition,
            ingestPolicy = WebIngestPolicy(maxTextChars = 101),
            claimPolicy = WebClaimExtractionPolicy(maxClaims = 10, minClaimChars = 2),
        )
        val changedClaims = BrowserReadRequest.create(
            acquisitionRequest = acquisition,
            ingestPolicy = WebIngestPolicy(maxTextChars = 100),
            claimPolicy = WebClaimExtractionPolicy(maxClaims = 11, minClaimChars = 2),
        )

        assertNotEquals(first.id, changedIngest.id)
        assertNotEquals(first.id, changedClaims.id)
    }

    @Test
    fun `malformed textual payload fails closed in B393 instead of creating browser read`() = runTest {
        val resource = WebResourceIdentity.parse("https://example.com/page")
        val runtime = BrowserReadRuntime(
            WebAcquisitionRuntime(
                SequencedTransport(
                    WebAcquisitionTransportResponse(
                        statusCode = 200,
                        contentType = "text/plain",
                        body = byteArrayOf(0xC3.toByte(), 0x28),
                    )
                )
            )
        )

        assertFailsWith<java.nio.charset.CharacterCodingException> {
            runtime.read(request(resource))
        }
    }

    @Test
    fun `same exact read evidence yields deterministic result identity`() = runTest {
        val resource = WebResourceIdentity.parse("https://example.com/stable")
        val response = WebAcquisitionTransportResponse(
            statusCode = 200,
            contentType = "text/plain",
            body = "Stable source sentence.".encodeToByteArray(),
        )
        val request = request(resource)

        val first = BrowserReadRuntime(
            WebAcquisitionRuntime(SequencedTransport(response))
        ).read(request)
        val second = BrowserReadRuntime(
            WebAcquisitionRuntime(SequencedTransport(response))
        ).read(request)

        assertEquals(first, second)
        assertTrue(first.fingerprint.isNotBlank())
    }

    private fun request(resource: WebResourceIdentity): BrowserReadRequest =
        BrowserReadRequest.create(
            acquisitionRequest = WebAcquisitionRequest.create(
                resource = resource,
                maxBytes = 4096,
                acceptedMediaTypes = listOf("*/*"),
            ),
            ingestPolicy = WebIngestPolicy(maxTextChars = 4096),
            claimPolicy = WebClaimExtractionPolicy(
                maxClaims = 64,
                minClaimChars = 1,
                maxClaimChars = 512,
            ),
        )

    private class SequencedTransport(
        vararg responses: WebAcquisitionTransportResponse,
    ) : WebAcquisitionTransport {
        private val queue = ArrayDeque(responses.toList())
        val requests = mutableListOf<WebAcquisitionTransportRequest>()

        override suspend fun fetch(
            request: WebAcquisitionTransportRequest,
        ): WebAcquisitionTransportResponse {
            requests += request
            return queue.removeFirst()
        }
    }
}
