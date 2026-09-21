package app.lifeos.core.runtime.web

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WebAcquisitionRuntimeTest {
    @Test
    fun `direct acquisition binds exact B391 identity payload and receipt without authority`() = runTest {
        val resource = WebResourceIdentity.parse("https://example.com/a/../doc")
        val transport = RecordingTransport(
            mapOf(
                resource.canonicalUrl to WebAcquisitionTransportResponse(
                    statusCode = 200,
                    contentType = "text/plain; charset=utf-8",
                    etag = ""v1"",
                    body = "hello".encodeToByteArray(),
                )
            )
        )
        val request = WebAcquisitionRequest.create(
            resource = resource,
            maxBytes = 64,
            acceptedMediaTypes = listOf("text/plain"),
        )

        val result = WebAcquisitionRuntime(transport).acquire(request)

        assertEquals(WebAcquisitionOutcome.ACQUIRED, result.receipt.outcome)
        assertEquals(resource.id, result.receipt.requestedResourceId)
        assertEquals(resource.id, result.receipt.finalResourceId)
        assertEquals("text/plain", result.receipt.contentType)
        assertEquals(""v1"", result.receipt.etag)
        assertContentEquals("hello".encodeToByteArray(), assertNotNull(result.payload).bytes())
        assertEquals(result.payload?.sha256, result.receipt.payloadSha256)
        assertEquals(listOf(resource.id), transport.requests.map { it.resource.id })
        assertFalse(request.executionAuthority)
        assertFalse(request.permissionAuthority)
        assertFalse(result.receipt.trustAuthority)
        assertFalse(result.receipt.truthAuthority)
        assertFalse(result.receipt.mutationAuthority)
        assertFalse(result.receipt.executionAuthority)
    }

    @Test
    fun `relative redirect is canonicalized through B391 and recorded in order`() = runTest {
        val start = WebResourceIdentity.parse("https://example.com/a/start")
        val finish = WebResourceIdentity.parse("https://example.com/final")
        val transport = RecordingTransport(
            mapOf(
                start.canonicalUrl to WebAcquisitionTransportResponse(
                    statusCode = 302,
                    location = "../final#ignored",
                ),
                finish.canonicalUrl to WebAcquisitionTransportResponse(
                    statusCode = 200,
                    contentType = "application/json",
                    body = "{}".encodeToByteArray(),
                ),
            )
        )

        val result = WebAcquisitionRuntime(transport).acquire(
            WebAcquisitionRequest.create(start)
        )

        assertEquals(finish, result.finalResource)
        assertEquals(1, result.receipt.redirects.size)
        assertEquals(start.id, result.receipt.redirects.single().from.id)
        assertEquals(finish.id, result.receipt.redirects.single().to.id)
        assertEquals(listOf(start.id, finish.id), transport.requests.map { it.resource.id })
    }

    @Test
    fun `redirect downgrade to non https fails closed before second fetch`() = runTest {
        val start = WebResourceIdentity.parse("https://example.com/start")
        val transport = RecordingTransport(
            mapOf(
                start.canonicalUrl to WebAcquisitionTransportResponse(
                    statusCode = 302,
                    location = "http://example.com/insecure",
                )
            )
        )

        assertFailsWith<IllegalArgumentException> {
            WebAcquisitionRuntime(transport).acquire(WebAcquisitionRequest.create(start))
        }
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `redirect loop fails closed`() = runTest {
        val a = WebResourceIdentity.parse("https://example.com/a")
        val b = WebResourceIdentity.parse("https://example.com/b")
        val transport = RecordingTransport(
            mapOf(
                a.canonicalUrl to WebAcquisitionTransportResponse(statusCode = 302, location = "/b"),
                b.canonicalUrl to WebAcquisitionTransportResponse(statusCode = 302, location = "/a"),
            )
        )

        assertFailsWith<IllegalArgumentException> {
            WebAcquisitionRuntime(transport).acquire(WebAcquisitionRequest.create(a))
        }
    }

    @Test
    fun `redirect budget is enforced exactly`() = runTest {
        val a = WebResourceIdentity.parse("https://example.com/a")
        val b = WebResourceIdentity.parse("https://example.com/b")
        val transport = RecordingTransport(
            mapOf(
                a.canonicalUrl to WebAcquisitionTransportResponse(statusCode = 302, location = "/b"),
                b.canonicalUrl to WebAcquisitionTransportResponse(
                    statusCode = 200,
                    contentType = "text/plain",
                    body = "ok".encodeToByteArray(),
                ),
            )
        )

        assertFailsWith<IllegalArgumentException> {
            WebAcquisitionRuntime(transport).acquire(
                WebAcquisitionRequest.create(a, maxRedirects = 0)
            )
        }
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `oversized transport payload fails closed even if transport ignored request budget`() = runTest {
        val resource = WebResourceIdentity.parse("https://example.com/large")
        val transport = RecordingTransport(
            mapOf(
                resource.canonicalUrl to WebAcquisitionTransportResponse(
                    statusCode = 200,
                    contentType = "text/plain",
                    body = ByteArray(65) { 1 },
                )
            )
        )

        assertFailsWith<IllegalArgumentException> {
            WebAcquisitionRuntime(transport).acquire(
                WebAcquisitionRequest.create(resource, maxBytes = 64)
            )
        }
    }

    @Test
    fun `unaccepted successful media type fails closed but wildcard may admit it`() = runTest {
        val resource = WebResourceIdentity.parse("https://example.com/data")
        val response = WebAcquisitionTransportResponse(
            statusCode = 200,
            contentType = "application/octet-stream",
            body = byteArrayOf(1, 2, 3),
        )

        assertFailsWith<IllegalArgumentException> {
            WebAcquisitionRuntime(
                RecordingTransport(mapOf(resource.canonicalUrl to response))
            ).acquire(
                WebAcquisitionRequest.create(resource, acceptedMediaTypes = listOf("text/*"))
            )
        }

        val admitted = WebAcquisitionRuntime(
            RecordingTransport(mapOf(resource.canonicalUrl to response))
        ).acquire(
            WebAcquisitionRequest.create(resource, acceptedMediaTypes = listOf("*/*"))
        )
        assertEquals(WebAcquisitionOutcome.ACQUIRED, admitted.receipt.outcome)
    }

    @Test
    fun `304 is represented without payload and keeps validators`() = runTest {
        val resource = WebResourceIdentity.parse("https://example.com/doc")
        val result = WebAcquisitionRuntime(
            RecordingTransport(
                mapOf(
                    resource.canonicalUrl to WebAcquisitionTransportResponse(
                        statusCode = 304,
                        etag = ""v2"",
                        lastModified = "Sun, 21 Sep 2026 12:00:00 GMT",
                    )
                )
            )
        ).acquire(WebAcquisitionRequest.create(resource))

        assertEquals(WebAcquisitionOutcome.NOT_MODIFIED, result.receipt.outcome)
        assertEquals(null, result.payload)
        assertEquals(null, result.receipt.payloadSha256)
        assertEquals(""v2"", result.receipt.etag)
    }

    @Test
    fun `ordinary HTTP error is observable but its body is not promoted to acquired payload`() = runTest {
        val resource = WebResourceIdentity.parse("https://example.com/missing")
        val result = WebAcquisitionRuntime(
            RecordingTransport(
                mapOf(
                    resource.canonicalUrl to WebAcquisitionTransportResponse(
                        statusCode = 404,
                        contentType = "text/html",
                        body = "<h1>missing</h1>".encodeToByteArray(),
                    )
                )
            )
        ).acquire(WebAcquisitionRequest.create(resource))

        assertEquals(WebAcquisitionOutcome.HTTP_ERROR, result.receipt.outcome)
        assertEquals(null, result.payload)
        assertEquals("<h1>missing</h1>".encodeToByteArray().size, result.receipt.byteCount)
        assertEquals(null, result.receipt.payloadSha256)
    }

    @Test
    fun `request identity includes resource budget redirect policy and canonical media set`() {
        val resource = WebResourceIdentity.parse("https://example.com/doc")
        val first = WebAcquisitionRequest.create(
            resource,
            maxBytes = 100,
            maxRedirects = 2,
            acceptedMediaTypes = listOf("TEXT/PLAIN", "application/json", "text/plain"),
        )
        val reordered = WebAcquisitionRequest.create(
            resource,
            maxBytes = 100,
            maxRedirects = 2,
            acceptedMediaTypes = listOf("application/json", "text/plain"),
        )
        val differentBudget = WebAcquisitionRequest.create(
            resource,
            maxBytes = 101,
            maxRedirects = 2,
            acceptedMediaTypes = listOf("application/json", "text/plain"),
        )

        assertEquals(listOf("application/json", "text/plain"), first.acceptedMediaTypes)
        assertEquals(first, reordered)
        assertNotEquals(first.id, differentBudget.id)
    }

    @Test
    fun `same transport evidence yields deterministic acquisition receipt`() = runTest {
        val resource = WebResourceIdentity.parse("https://example.com/stable")
        val response = WebAcquisitionTransportResponse(
            statusCode = 200,
            contentType = "text/plain",
            body = "stable".encodeToByteArray(),
        )
        val request = WebAcquisitionRequest.create(resource)

        val first = WebAcquisitionRuntime(
            RecordingTransport(mapOf(resource.canonicalUrl to response))
        ).acquire(request)
        val second = WebAcquisitionRuntime(
            RecordingTransport(mapOf(resource.canonicalUrl to response))
        ).acquire(request)

        assertEquals(first.finalResource, second.finalResource)
        assertEquals(first.receipt, second.receipt)
        assertEquals(first.payload, second.payload)
        assertTrue(first.receipt.fingerprint.isNotBlank())
    }

    private class RecordingTransport(
        private val responses: Map<String, WebAcquisitionTransportResponse>,
    ) : WebAcquisitionTransport {
        val requests = mutableListOf<WebAcquisitionTransportRequest>()

        override suspend fun fetch(
            request: WebAcquisitionTransportRequest,
        ): WebAcquisitionTransportResponse {
            requests += request
            return requireNotNull(responses[request.resource.canonicalUrl]) {
                "No fixture response for " + request.resource.canonicalUrl
            }
        }
    }
}
