package app.lifeos.core.runtime.web

import java.nio.charset.CharacterCodingException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebMultiFormatIngestorTest {
    @Test
    fun `html ingest keeps raw text but exposes bounded visible text without script or style`() = runTest {
        val acquisition = acquired(
            contentType = "text/html",
            body = """
                <html><head><title>Title</title><style>.x{display:none}</style></head>
                <body><h1>Hello &amp; world</h1><script>secret()</script><p>Second</p></body></html>
            """.trimIndent().encodeToByteArray(),
        )

        val document = WebMultiFormatIngestor().ingest(acquisition)

        assertEquals(WebIngestFormat.HTML, document.format)
        assertEquals(WebIngestState.INGESTED_TEXT, document.state)
        assertTrue(requireNotNull(document.rawText).contains("<script>"))
        assertTrue(requireNotNull(document.visibleText).contains("Hello & world"))
        assertTrue(document.visibleText!!.contains("Second"))
        assertFalse(document.visibleText!!.contains("secret()"))
        assertFalse(document.visibleText!!.contains("display:none"))
        assertFalse(document.truthAuthority)
        assertFalse(document.claimAuthority)
        assertFalse(document.semanticAuthority)
        assertFalse(document.executionAuthority)
    }

    @Test
    fun `json ingest preserves decoded structured text exactly`() = runTest {
        val json = """{"name":"LIFEOS","items":[1,2,3]}"""
        val document = WebMultiFormatIngestor().ingest(
            acquired("application/json", json.encodeToByteArray())
        )

        assertEquals(WebIngestFormat.JSON, document.format)
        assertEquals(json, document.rawText)
        assertEquals(json, document.visibleText)
        assertFalse(document.truncated)
    }

    @Test
    fun `generic XML feed is classified as RSS Atom while ordinary XML stays XML`() = runTest {
        val feed = WebMultiFormatIngestor().ingest(
            acquired(
                "application/xml",
                """<?xml version="1.0"?><rss><channel><title>News</title><item>One</item></channel></rss>"""
                    .encodeToByteArray(),
            )
        )
        val ordinary = WebMultiFormatIngestor().ingest(
            acquired(
                "application/xml",
                """<root><value>42</value></root>""".encodeToByteArray(),
                url = "https://example.com/data.xml",
            )
        )

        assertEquals(WebIngestFormat.RSS_ATOM, feed.format)
        assertTrue(requireNotNull(feed.visibleText).contains("News"))
        assertEquals(WebIngestFormat.XML, ordinary.format)
        assertTrue(requireNotNull(ordinary.visibleText).contains("42"))
    }

    @Test
    fun `plain text CSV and UTF16 BOM are supported without semantic promotion`() = runTest {
        val plain = WebMultiFormatIngestor().ingest(
            acquired("text/plain", "plain text".encodeToByteArray())
        )
        val csv = WebMultiFormatIngestor().ingest(
            acquired(
                "text/csv",
                "a,b\n1,2".encodeToByteArray(),
                url = "https://example.com/data.csv",
            )
        )
        val utf16Bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
            "hello".toByteArray(Charsets.UTF_16LE)
        val utf16 = WebMultiFormatIngestor().ingest(
            acquired(
                "text/plain",
                utf16Bytes,
                url = "https://example.com/utf16.txt",
            )
        )

        assertEquals(WebIngestFormat.PLAIN_TEXT, plain.format)
        assertEquals("plain text", plain.visibleText)
        assertEquals(WebIngestFormat.CSV, csv.format)
        assertEquals("a,b\n1,2", csv.rawText)
        assertEquals("hello", utf16.rawText)
    }

    @Test
    fun `PDF remains typed opaque binary instead of fake extracted text`() = runTest {
        val document = WebMultiFormatIngestor().ingest(
            acquired(
                "application/pdf",
                "%PDF-1.7 fake fixture".encodeToByteArray(),
                url = "https://example.com/doc.pdf",
            )
        )

        assertEquals(WebIngestFormat.PDF, document.format)
        assertEquals(WebIngestState.BINARY_OPAQUE, document.state)
        assertNull(document.rawText)
        assertNull(document.visibleText)
        assertFalse(document.truncated)
    }

    @Test
    fun `unsupported acquired media remains explicit unsupported and textless`() = runTest {
        val document = WebMultiFormatIngestor().ingest(
            acquired(
                "application/octet-stream",
                byteArrayOf(1, 2, 3, 4),
                url = "https://example.com/blob",
            )
        )

        assertEquals(WebIngestFormat.UNKNOWN, document.format)
        assertEquals(WebIngestState.UNSUPPORTED, document.state)
        assertNull(document.rawText)
        assertNull(document.visibleText)
    }

    @Test
    fun `malformed textual bytes fail closed instead of replacement-character decoding`() = runTest {
        val acquisition = acquired(
            "text/plain",
            byteArrayOf(0xC3.toByte(), 0x28),
        )

        assertFailsWith<CharacterCodingException> {
            WebMultiFormatIngestor().ingest(acquisition)
        }
    }

    @Test
    fun `text budget truncation is deterministic and never leaves a dangling high surrogate`() = runTest {
        val acquisition = acquired(
            "text/plain",
            "A😀B".encodeToByteArray(),
        )
        val policy = WebIngestPolicy(maxTextChars = 2)

        val first = WebMultiFormatIngestor().ingest(acquisition, policy)
        val second = WebMultiFormatIngestor().ingest(acquisition, policy)

        assertTrue(first.truncated)
        assertEquals("A", first.rawText)
        assertEquals(first, second)
    }

    @Test
    fun `empty acquired payload becomes EMPTY rather than invented text`() = runTest {
        val document = WebMultiFormatIngestor().ingest(
            acquired("text/plain", byteArrayOf())
        )

        assertEquals(WebIngestState.EMPTY, document.state)
        assertNull(document.rawText)
        assertNull(document.visibleText)
        assertEquals(0, document.payloadByteCount)
    }

    @Test
    fun `HTTP error acquisition cannot enter multi-format ingest`() = runTest {
        val resource = WebResourceIdentity.parse("https://example.com/missing")
        val acquisition = WebAcquisitionRuntime(
            WebAcquisitionTransport {
                WebAcquisitionTransportResponse(
                    statusCode = 404,
                    contentType = "text/plain",
                    body = "missing".encodeToByteArray(),
                )
            }
        ).acquire(
            WebAcquisitionRequest.create(
                resource,
                maxBytes = 128,
                acceptedMediaTypes = listOf("text/plain"),
            )
        )

        assertFailsWith<IllegalArgumentException> {
            WebMultiFormatIngestor().ingest(acquisition)
        }
    }

    @Test
    fun `exact acquisition receipt and payload participate in ingest identity`() = runTest {
        val first = WebMultiFormatIngestor().ingest(
            acquired("text/plain", "same".encodeToByteArray())
        )
        val second = WebMultiFormatIngestor().ingest(
            acquired("text/plain", "changed".encodeToByteArray())
        )

        assertEquals(first.resourceId, second.resourceId)
        assertTrue(first.acquisitionReceiptFingerprint.isNotBlank())
        assertTrue(first.payloadSha256.isNotBlank())
        assertTrue(first.fingerprint.isNotBlank())
        kotlin.test.assertNotEquals(first.fingerprint, second.fingerprint)
    }

    private suspend fun acquired(
        contentType: String,
        body: ByteArray,
        url: String = "https://example.com/resource",
    ): WebAcquisitionResult {
        val resource = WebResourceIdentity.parse(url)
        val response = WebAcquisitionTransportResponse(
            statusCode = 200,
            contentType = contentType,
            body = body,
        )
        return WebAcquisitionRuntime(
            WebAcquisitionTransport { response }
        ).acquire(
            WebAcquisitionRequest.create(
                resource = resource,
                maxBytes = maxOf(1, body.size + 16),
                acceptedMediaTypes = listOf("*/*"),
            )
        )
    }
}
