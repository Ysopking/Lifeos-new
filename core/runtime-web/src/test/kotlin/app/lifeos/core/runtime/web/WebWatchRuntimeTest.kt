package app.lifeos.core.runtime.web

import java.time.Duration
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebWatchRuntimeTest {
    @Test
    fun `first acquired cycle initializes revisioned watch state without claiming authority`() = runTest {
        val fixture = runtimeWithResponses(
            response(
                200,
                "text/plain",
                "initial".encodeToByteArray(),
            )
        )
        val definition = definition()

        val result = fixture.runtime.runOnce(definition)

        assertEquals(WebWatchCycleOutcome.INITIALIZED, result.outcome)
        assertEquals(1L, result.nextState.cycleRevision)
        assertEquals("initial", result.nextState.latestDocument?.visibleText)
        assertNull(result.change)
        assertFalse(definition.schedulingAuthority)
        assertFalse(definition.permissionAuthority)
        assertFalse(result.truthAuthority)
        assertFalse(result.notificationAuthority)
        assertFalse(result.schedulingAuthority)
        assertFalse(result.permissionAuthority)
    }

    @Test
    fun `second equal payload is unchanged and advances cycle revision`() = runTest {
        val fixture = runtimeWithResponses(
            response(200, "text/plain", "same".encodeToByteArray()),
            response(200, "text/plain", "same".encodeToByteArray()),
        )
        val definition = definition()

        val first = fixture.runtime.runOnce(definition)
        val second = fixture.runtime.runOnce(definition, first.nextState)

        assertEquals(WebWatchCycleOutcome.UNCHANGED, second.outcome)
        assertEquals(WebChangeKind.UNCHANGED, second.change?.kind)
        assertEquals(2L, second.nextState.cycleRevision)
        assertEquals(first.nextState.latestDocument, second.nextState.latestDocument)
    }

    @Test
    fun `changed payload becomes content changed watch outcome`() = runTest {
        val fixture = runtimeWithResponses(
            response(200, "text/plain", "old".encodeToByteArray()),
            response(200, "text/plain", "new".encodeToByteArray()),
        )
        val definition = definition()

        val first = fixture.runtime.runOnce(definition)
        val second = fixture.runtime.runOnce(definition, first.nextState)

        assertEquals(WebWatchCycleOutcome.CONTENT_CHANGED, second.outcome)
        assertEquals(WebChangeKind.CONTENT_CHANGED, second.change?.kind)
        assertEquals("new", second.nextState.latestDocument?.visibleText)
    }

    @Test
    fun `same payload with changed content type is representation changed`() = runTest {
        val body = "a,b\n1,2".encodeToByteArray()
        val fixture = runtimeWithResponses(
            response(200, "text/plain", body),
            response(200, "text/csv", body),
        )
        val definition = definition()

        val first = fixture.runtime.runOnce(definition)
        val second = fixture.runtime.runOnce(definition, first.nextState)

        assertEquals(WebWatchCycleOutcome.REPRESENTATION_CHANGED, second.outcome)
        assertEquals(WebChangeKind.REPRESENTATION_ONLY, second.change?.kind)
    }

    @Test
    fun `redirect target change is resource changed and resets comparison baseline`() = runTest {
        val fixture = runtimeWithResponses(
            WebAcquisitionTransportResponse(
                statusCode = 302,
                location = "https://example.com/final-a",
            ),
            response(200, "text/plain", "first".encodeToByteArray()),
            WebAcquisitionTransportResponse(
                statusCode = 302,
                location = "https://example.com/final-b",
            ),
            response(200, "text/plain", "second".encodeToByteArray()),
        )
        val definition = definition("https://example.com/start")

        val first = fixture.runtime.runOnce(definition)
        val second = fixture.runtime.runOnce(definition, first.nextState)

        assertEquals(WebWatchCycleOutcome.RESOURCE_CHANGED, second.outcome)
        assertNull(second.change)
        assertEquals(
            WebResourceIdentity.parse("https://example.com/final-b").id,
            second.nextState.latestDocument?.resourceId,
        )
    }

    @Test
    fun `not modified preserves baseline and requires initialized state`() = runTest {
        val fixture = runtimeWithResponses(
            response(200, "text/plain", "baseline".encodeToByteArray()),
            WebAcquisitionTransportResponse(statusCode = 304, etag = "\"v2\""),
        )
        val definition = definition()
        val first = fixture.runtime.runOnce(definition)

        val second = fixture.runtime.runOnce(definition, first.nextState)

        assertEquals(WebWatchCycleOutcome.NOT_MODIFIED, second.outcome)
        assertEquals(first.nextState.latestDocument, second.nextState.latestDocument)
        assertNull(second.change)

        val freshFixture = runtimeWithResponses(
            WebAcquisitionTransportResponse(statusCode = 304, etag = "\"v2\""),
        )
        assertFailsWith<IllegalArgumentException> {
            freshFixture.runtime.runOnce(definition)
        }
    }

    @Test
    fun `HTTP error advances cycle but preserves last successful document`() = runTest {
        val fixture = runtimeWithResponses(
            response(200, "text/plain", "baseline".encodeToByteArray()),
            response(503, "text/plain", "unavailable".encodeToByteArray()),
        )
        val definition = definition()
        val first = fixture.runtime.runOnce(definition)

        val second = fixture.runtime.runOnce(definition, first.nextState)

        assertEquals(WebWatchCycleOutcome.ACQUISITION_FAILED, second.outcome)
        assertEquals(2L, second.nextState.cycleRevision)
        assertEquals(first.nextState.latestDocument, second.nextState.latestDocument)
        assertNull(second.change)
    }

    @Test
    fun `state from another watch definition fails closed before acquisition`() = runTest {
        val fixture = runtimeWithResponses(
            response(200, "text/plain", "unused".encodeToByteArray()),
        )
        val firstDefinition = definition("https://example.com/a")
        val secondDefinition = definition("https://example.com/b")
        val foreignState = WebWatchState.initial(firstDefinition)

        assertFailsWith<IllegalArgumentException> {
            fixture.runtime.runOnce(secondDefinition, foreignState)
        }
        assertEquals(0, fixture.transport.requests.size)
    }

    @Test
    fun `poll interval is identity significant but never grants scheduling authority`() {
        val request = WebAcquisitionRequest.create(
            WebResourceIdentity.parse("https://example.com/resource")
        )
        val fast = WebWatchDefinition.create(
            acquisitionRequest = request,
            pollInterval = Duration.ofMinutes(5),
        )
        val slow = WebWatchDefinition.create(
            acquisitionRequest = request,
            pollInterval = Duration.ofMinutes(15),
        )

        kotlin.test.assertNotEquals(fast.id, slow.id)
        assertFalse(fast.schedulingAuthority)
        assertFalse(slow.schedulingAuthority)
    }

    private fun definition(
        url: String = "https://example.com/resource",
    ): WebWatchDefinition = WebWatchDefinition.create(
        acquisitionRequest = WebAcquisitionRequest.create(
            resource = WebResourceIdentity.parse(url),
            maxBytes = 1024,
            maxRedirects = 4,
            acceptedMediaTypes = listOf("*/*"),
        ),
        ingestPolicy = WebIngestPolicy(maxTextChars = 1024),
        pollInterval = Duration.ofMinutes(15),
    )

    private fun response(
        status: Int,
        contentType: String,
        body: ByteArray,
    ): WebAcquisitionTransportResponse =
        WebAcquisitionTransportResponse(
            statusCode = status,
            contentType = contentType,
            body = body,
        )

    private fun runtimeWithResponses(
        vararg responses: WebAcquisitionTransportResponse,
    ): RuntimeFixture {
        val transport = SequencedTransport(responses.toList())
        return RuntimeFixture(
            runtime = WebWatchRuntime(WebAcquisitionRuntime(transport)),
            transport = transport,
        )
    }

    private data class RuntimeFixture(
        val runtime: WebWatchRuntime,
        val transport: SequencedTransport,
    )

    private class SequencedTransport(
        responses: List<WebAcquisitionTransportResponse>,
    ) : WebAcquisitionTransport {
        private val queue = ArrayDeque(responses)
        val requests = mutableListOf<WebAcquisitionTransportRequest>()

        override suspend fun fetch(
            request: WebAcquisitionTransportRequest,
        ): WebAcquisitionTransportResponse {
            requests += request
            return queue.removeFirst()
        }
    }
}
