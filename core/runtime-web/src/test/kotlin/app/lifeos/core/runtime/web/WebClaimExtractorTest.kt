package app.lifeos.core.runtime.web

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebClaimExtractorTest {
    @Test
    fun `HTML visible sentences become exact source-span candidates without authority`() = runTest {
        val document = document(
            "text/html",
            "<p>First verified-looking sentence.</p><p>Second candidate sentence!</p>",
        )
        val result = WebClaimExtractor().extract(
            document,
            WebClaimExtractionPolicy(minClaimChars = 5),
        )

        assertEquals(2, result.candidates.size)
        val source = requireNotNull(document.visibleText)
        result.candidates.forEach { candidate ->
            assertEquals(
                candidate.text,
                source.substring(candidate.startChar, candidate.endCharExclusive),
            )
            assertEquals(WebClaimKind.SENTENCE, candidate.kind)
            assertFalse(candidate.truthAuthority)
            assertFalse(candidate.evidenceAuthority)
            assertFalse(candidate.citationAuthority)
            assertFalse(candidate.executionAuthority)
        }
        assertFalse(result.truthAuthority)
        assertFalse(result.evidenceAuthority)
    }

    @Test
    fun `compact JSON remains one structured record candidate instead of invented semantics`() = runTest {
        val json = """{"value":42,"unit":"ms"}"""
        val document = document("application/json", json)
        val result = WebClaimExtractor().extract(
            document,
            WebClaimExtractionPolicy(minClaimChars = 1),
        )

        assertEquals(1, result.candidates.size)
        assertEquals(WebClaimKind.STRUCTURED_RECORD, result.candidates.single().kind)
        assertEquals(json, result.candidates.single().text)
    }

    @Test
    fun `CSV lines become ordered structured record candidates`() = runTest {
        val document = document("text/csv", "name,value\na,1\nb,2")
        val result = WebClaimExtractor().extract(
            document,
            WebClaimExtractionPolicy(minClaimChars = 1),
        )

        assertEquals(
            listOf("name,value", "a,1", "b,2"),
            result.candidates.map { it.text },
        )
        assertTrue(result.candidates.all { it.kind == WebClaimKind.STRUCTURED_RECORD })
    }

    @Test
    fun `opaque PDF produces no claim candidates`() = runTest {
        val document = document(
            "application/pdf",
            "%PDF-1.7 fixture",
            url = "https://example.com/doc.pdf",
        )
        val result = WebClaimExtractor().extract(document)

        assertTrue(result.candidates.isEmpty())
        assertNull(result.sourceTextFingerprint)
        assertFalse(result.truncated)
    }

    @Test
    fun `short source fragments are filtered without creating claims`() = runTest {
        val document = document("text/plain", "No. Tiny. This is long enough.")
        val result = WebClaimExtractor().extract(
            document,
            WebClaimExtractionPolicy(minClaimChars = 10),
        )

        assertEquals(listOf("This is long enough."), result.candidates.map { it.text })
    }

    @Test
    fun `max claim count truncates deterministically and explicitly`() = runTest {
        val document = document(
            "text/plain",
            "First sentence here. Second sentence here. Third sentence here.",
        )
        val policy = WebClaimExtractionPolicy(
            maxClaims = 2,
            minClaimChars = 1,
            maxClaimChars = 100,
        )
        val extractor = WebClaimExtractor()

        val first = extractor.extract(document, policy)
        val second = extractor.extract(document, policy)

        assertEquals(2, first.candidates.size)
        assertTrue(first.truncated)
        assertEquals(first, second)
    }

    @Test
    fun `claim length budget truncates exact source span without changing provenance`() = runTest {
        val document = document(
            "text/plain",
            "A very long candidate sentence for bounded extraction.",
        )
        val result = WebClaimExtractor().extract(
            document,
            WebClaimExtractionPolicy(
                minClaimChars = 5,
                maxClaimChars = 12,
            ),
        )

        val candidate = result.candidates.single()
        assertTrue(candidate.truncated)
        assertEquals(12, candidate.text.length)
        assertEquals(
            candidate.text,
            requireNotNull(document.visibleText)
                .substring(candidate.startChar, candidate.endCharExclusive),
        )
        assertEquals(document.fingerprint, candidate.sourceDocumentFingerprint)
    }

    @Test
    fun `same exact B393 document yields stable claim identities`() = runTest {
        val document = document("text/plain", "Stable source sentence.")
        val policy = WebClaimExtractionPolicy(minClaimChars = 1)
        val extractor = WebClaimExtractor()

        val first = extractor.extract(document, policy)
        val second = extractor.extract(document, policy)

        assertEquals(first, second)
        assertTrue(first.fingerprint.isNotBlank())
        assertTrue(first.candidates.single().id.startsWith(WebClaimCandidate.ID_PREFIX))
    }

    @Test
    fun `changed acquired payload changes document and claim identity even on same resource`() = runTest {
        val first = WebClaimExtractor().extract(
            document("text/plain", "Original source sentence."),
            WebClaimExtractionPolicy(minClaimChars = 1),
        )
        val second = WebClaimExtractor().extract(
            document("text/plain", "Changed source sentence."),
            WebClaimExtractionPolicy(minClaimChars = 1),
        )

        assertEquals(first.resourceId, second.resourceId)
        assertNotEquals(first.sourceDocumentFingerprint, second.sourceDocumentFingerprint)
        assertNotEquals(first.candidates.single().id, second.candidates.single().id)
        assertNotEquals(first.fingerprint, second.fingerprint)
    }

    private suspend fun document(
        contentType: String,
        text: String,
        url: String = "https://example.com/resource",
    ): WebIngestDocument {
        val resource = WebResourceIdentity.parse(url)
        val acquisition = WebAcquisitionRuntime(
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
                maxBytes = text.encodeToByteArray().size + 16,
                acceptedMediaTypes = listOf("*/*"),
            )
        )
        return WebMultiFormatIngestor().ingest(acquisition)
    }
}
