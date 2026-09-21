package app.lifeos.core.runtime.web

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WebCitationGraphBuilderTest {
    @Test
    fun `exact B391 B393 B394 lineage becomes one deterministic citation graph`() = runTest {
        val fixture = fixture("First source sentence. Second source sentence.")
        val graph = WebCitationGraphBuilder().build(
            resource = fixture.resource,
            document = fixture.document,
            extraction = fixture.extraction,
        )

        assertEquals(fixture.resource.id, graph.documentNode.resourceId)
        assertEquals(fixture.resource.canonicalUrl, graph.documentNode.canonicalUrl)
        assertEquals(fixture.document.fingerprint, graph.sourceDocumentFingerprint)
        assertEquals(fixture.extraction.fingerprint, graph.sourceExtractionFingerprint)
        assertEquals(2, graph.claimNodes.size)
        assertEquals(2, graph.edges.size)
        assertTrue(graph.provenanceBound)
        assertFalse(graph.truthAuthority)
        assertFalse(graph.evidenceAuthority)
        assertFalse(graph.trustAuthority)
        assertFalse(graph.executionAuthority)
    }

    @Test
    fun `every citation anchor addresses the exact source substring and canonical URL`() = runTest {
        val fixture = fixture("First source sentence. Second source sentence.")
        val graph = WebCitationGraphBuilder().build(
            fixture.resource,
            fixture.document,
            fixture.extraction,
        )
        val source = requireNotNull(fixture.document.visibleText)

        graph.claimNodes.forEach { node ->
            val anchor = node.anchor
            assertEquals(fixture.resource.canonicalUrl, anchor.canonicalUrl)
            assertEquals(
                node.candidate.text,
                source.substring(anchor.startChar, anchor.endCharExclusive),
            )
            assertFalse(anchor.truthAuthority)
            assertFalse(anchor.evidenceAuthority)
            assertFalse(node.truthAuthority)
            assertFalse(node.evidenceAuthority)
        }
    }

    @Test
    fun `resource substitution fails closed even for same host`() = runTest {
        val fixture = fixture("Stable source sentence.")
        val substituted = WebResourceIdentity.parse("https://example.com/other")

        assertFailsWith<IllegalArgumentException> {
            WebCitationGraphBuilder().build(
                substituted,
                fixture.document,
                fixture.extraction,
            )
        }
    }

    @Test
    fun `document substitution fails closed on same resource`() = runTest {
        val first = fixture("Original source sentence.")
        val second = fixture("Changed source sentence.")

        assertEquals(first.resource.id, second.resource.id)
        assertFailsWith<IllegalArgumentException> {
            WebCitationGraphBuilder().build(
                first.resource,
                first.document,
                second.extraction,
            )
        }
    }

    @Test
    fun `tampered source span fails closed`() = runTest {
        val fixture = fixture("One exact source sentence.")
        val candidate = fixture.extraction.candidates.single()

        assertFailsWith<IllegalArgumentException> {
            candidate.copy(
                text = "tampered",
                fingerprint = candidate.fingerprint,
            )
        }
    }

    @Test
    fun `opaque PDF produces document citation node with no claim edges`() = runTest {
        val fixture = fixture(
            text = "%PDF-1.7 fixture",
            contentType = "application/pdf",
            url = "https://example.com/doc.pdf",
        )
        val graph = WebCitationGraphBuilder().build(
            fixture.resource,
            fixture.document,
            fixture.extraction,
        )

        assertEquals(WebIngestFormat.PDF, graph.documentNode.format)
        assertTrue(graph.claimNodes.isEmpty())
        assertTrue(graph.edges.isEmpty())
        assertTrue(graph.provenanceBound)
    }

    @Test
    fun `same exact inputs yield stable graph identity`() = runTest {
        val fixture = fixture("Stable source sentence.")
        val builder = WebCitationGraphBuilder()

        val first = builder.build(fixture.resource, fixture.document, fixture.extraction)
        val second = builder.build(fixture.resource, fixture.document, fixture.extraction)

        assertEquals(first, second)
        assertTrue(first.fingerprint.isNotBlank())
    }

    @Test
    fun `changed payload changes document claim anchor edge and graph identity`() = runTest {
        val first = fixture("Original source sentence.")
        val second = fixture("Changed source sentence.")

        assertEquals(first.resource.id, second.resource.id)
        assertNotEquals(first.document.fingerprint, second.document.fingerprint)

        val firstGraph = WebCitationGraphBuilder().build(
            first.resource,
            first.document,
            first.extraction,
        )
        val secondGraph = WebCitationGraphBuilder().build(
            second.resource,
            second.document,
            second.extraction,
        )

        assertNotEquals(firstGraph.documentNode.fingerprint, secondGraph.documentNode.fingerprint)
        assertNotEquals(firstGraph.claimNodes.single().fingerprint, secondGraph.claimNodes.single().fingerprint)
        assertNotEquals(firstGraph.edges.single().fingerprint, secondGraph.edges.single().fingerprint)
        assertNotEquals(firstGraph.fingerprint, secondGraph.fingerprint)
    }

    private suspend fun fixture(
        text: String,
        contentType: String = "text/plain",
        url: String = "https://example.com/resource",
    ): Fixture {
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
                maxBytes = text.encodeToByteArray().size + 32,
                acceptedMediaTypes = listOf("*/*"),
            )
        )
        val document = WebMultiFormatIngestor().ingest(acquisition)
        val extraction = WebClaimExtractor().extract(
            document,
            WebClaimExtractionPolicy(minClaimChars = 1),
        )
        return Fixture(resource, document, extraction)
    }

    private data class Fixture(
        val resource: WebResourceIdentity,
        val document: WebIngestDocument,
        val extraction: WebClaimExtractionResult,
    )
}
