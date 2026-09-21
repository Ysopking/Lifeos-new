package app.lifeos.core.runtime.web

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WebChangeDetectorTest {
    @Test
    fun `identical B393 document is unchanged and grants no authority`() = runTest {
        val document = document("same content")
        val result = WebChangeDetector().detect(document, document)

        assertEquals(WebChangeKind.UNCHANGED, result.kind)
        assertTrue(result.dimensions.isEmpty())
        assertFalse(result.contentChanged)
        assertFalse(result.representationOnlyChanged)
        assertFalse(result.truthAuthority)
        assertFalse(result.watchSchedulingAuthority)
        assertFalse(result.mutationAuthority)
        assertFalse(result.executionAuthority)
    }

    @Test
    fun `changed payload on same resource is explicit content change`() = runTest {
        val previous = document("old content")
        val current = document("new content")
        val result = WebChangeDetector().detect(previous, current)

        assertEquals(WebChangeKind.CONTENT_CHANGED, result.kind)
        assertTrue(WebChangeDimension.PAYLOAD in result.dimensions)
        assertTrue(WebChangeDimension.RAW_TEXT in result.dimensions)
        assertTrue(WebChangeDimension.VISIBLE_TEXT in result.dimensions)
        assertTrue(result.contentChanged)
        assertFalse(result.representationOnlyChanged)
    }

    @Test
    fun `same payload under another ingest policy is representation only`() = runTest {
        val acquisition = acquired("ABCDEFGHIJKL")
        val previous = WebMultiFormatIngestor().ingest(
            acquisition,
            WebIngestPolicy(maxTextChars = 5),
        )
        val current = WebMultiFormatIngestor().ingest(
            acquisition,
            WebIngestPolicy(maxTextChars = 10),
        )

        val result = WebChangeDetector().detect(previous, current)

        assertEquals(WebChangeKind.REPRESENTATION_ONLY, result.kind)
        assertFalse(result.contentChanged)
        assertTrue(result.representationOnlyChanged)
        assertTrue(WebChangeDimension.INGEST_POLICY in result.dimensions)
        assertTrue(WebChangeDimension.RAW_TEXT in result.dimensions)
        assertTrue(WebChangeDimension.VISIBLE_TEXT in result.dimensions)
        assertFalse(WebChangeDimension.PAYLOAD in result.dimensions)
    }

    @Test
    fun `same payload with different media typing is representation only`() = runTest {
        val previous = document(
            text = "a,b\n1,2",
            contentType = "text/plain",
        )
        val current = document(
            text = "a,b\n1,2",
            contentType = "text/csv",
        )

        val result = WebChangeDetector().detect(previous, current)

        assertEquals(WebChangeKind.REPRESENTATION_ONLY, result.kind)
        assertTrue(WebChangeDimension.MEDIA_TYPE in result.dimensions)
        assertTrue(WebChangeDimension.FORMAT in result.dimensions)
        assertFalse(WebChangeDimension.PAYLOAD in result.dimensions)
    }

    @Test
    fun `different B391 resources cannot be compared`() = runTest {
        val previous = document(
            text = "same",
            url = "https://example.com/a",
        )
        val current = document(
            text = "same",
            url = "https://example.com/b",
        )

        assertFailsWith<IllegalArgumentException> {
            WebChangeDetector().detect(previous, current)
        }
    }

    @Test
    fun `direction is provenance significant`() = runTest {
        val previous = document("old content")
        val current = document("new content")
        val detector = WebChangeDetector()

        val forward = detector.detect(previous, current)
        val reverse = detector.detect(current, previous)

        assertEquals(WebChangeKind.CONTENT_CHANGED, forward.kind)
        assertEquals(WebChangeKind.CONTENT_CHANGED, reverse.kind)
        assertNotEquals(forward.fingerprint, reverse.fingerprint)
    }

    @Test
    fun `same exact transition yields stable detection identity`() = runTest {
        val previous = document("old content")
        val current = document("new content")
        val detector = WebChangeDetector()

        assertEquals(
            detector.detect(previous, current),
            detector.detect(previous, current),
        )
    }

    private suspend fun document(
        text: String,
        contentType: String = "text/plain",
        url: String = "https://example.com/resource",
    ): WebIngestDocument =
        WebMultiFormatIngestor().ingest(
            acquired(
                text = text,
                contentType = contentType,
                url = url,
            )
        )

    private suspend fun acquired(
        text: String,
        contentType: String = "text/plain",
        url: String = "https://example.com/resource",
    ): WebAcquisitionResult {
        val resource = WebResourceIdentity.parse(url)
        return WebAcquisitionRuntime(
            WebAcquisitionTransport {
                WebAcquisitionTransportResponse(
                    statusCode = 200,
                    contentType = contentType,
                    body = text.encodeToByteArray(),
                )
            }
        ).acquire(
            WebAcquisitionRequest.create(
                resource = resource,
                maxBytes = text.encodeToByteArray().size + 32,
                acceptedMediaTypes = listOf("*/*"),
            )
        )
    }
}
