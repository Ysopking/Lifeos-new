package app.lifeos.core.runtime.web

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

enum class WebCitationEdgeKind {
    DOCUMENT_CONTAINS_CLAIM,
}

data class WebCitationAnchor(
    val resourceId: WebResourceId,
    val canonicalUrl: String,
    val sourceDocumentFingerprint: String,
    val sourceTextFingerprint: String,
    val startChar: Int,
    val endCharExclusive: Int,
    val quoteFingerprint: String,
    val fingerprint: String,
) {
    init {
        require(canonicalUrl.isNotBlank())
        require(sourceDocumentFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(sourceTextFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(startChar >= 0)
        require(endCharExclusive > startChar)
        require(quoteFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(
            fingerprint == citationFingerprint(
                "web-citation-anchor/v1",
                resourceId.value,
                canonicalUrl,
                sourceDocumentFingerprint,
                sourceTextFingerprint,
                startChar.toString(),
                endCharExclusive.toString(),
                quoteFingerprint,
            )
        )
    }

    val truthAuthority: Boolean
        get() = false

    val evidenceAuthority: Boolean
        get() = false
}

data class WebCitationDocumentNode(
    val resourceId: WebResourceId,
    val canonicalUrl: String,
    val sourceDocumentFingerprint: String,
    val acquisitionReceiptFingerprint: String,
    val payloadSha256: String,
    val format: WebIngestFormat,
    val fingerprint: String,
) {
    init {
        require(canonicalUrl.isNotBlank())
        require(sourceDocumentFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(acquisitionReceiptFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(payloadSha256.matches(Regex("[0-9a-f]{64}")))
        require(
            fingerprint == citationFingerprint(
                "web-citation-document-node/v1",
                resourceId.value,
                canonicalUrl,
                sourceDocumentFingerprint,
                acquisitionReceiptFingerprint,
                payloadSha256,
                format.name,
            )
        )
    }
}

data class WebCitationClaimNode(
    val candidate: WebClaimCandidate,
    val anchor: WebCitationAnchor,
    val fingerprint: String,
) {
    init {
        require(candidate.resourceId == anchor.resourceId)
        require(candidate.sourceDocumentFingerprint == anchor.sourceDocumentFingerprint)
        require(candidate.sourceTextFingerprint == anchor.sourceTextFingerprint)
        require(candidate.startChar == anchor.startChar)
        require(candidate.endCharExclusive == anchor.endCharExclusive)
        require(anchor.quoteFingerprint == textSha256(candidate.text))
        require(
            fingerprint == citationFingerprint(
                "web-citation-claim-node/v1",
                candidate.id,
                candidate.fingerprint,
                anchor.fingerprint,
            )
        )
    }

    val truthAuthority: Boolean
        get() = false

    val evidenceAuthority: Boolean
        get() = false
}

data class WebCitationEdge(
    val documentNodeFingerprint: String,
    val claimCandidateId: String,
    val kind: WebCitationEdgeKind,
    val fingerprint: String,
) {
    init {
        require(documentNodeFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(claimCandidateId.startsWith(WebClaimCandidate.ID_PREFIX))
        require(
            fingerprint == citationFingerprint(
                "web-citation-edge/v1",
                documentNodeFingerprint,
                claimCandidateId,
                kind.name,
            )
        )
    }
}

data class WebCitationGraph(
    val resource: WebResourceIdentity,
    val sourceDocumentFingerprint: String,
    val sourceExtractionFingerprint: String,
    val documentNode: WebCitationDocumentNode,
    val claimNodes: List<WebCitationClaimNode>,
    val edges: List<WebCitationEdge>,
    val fingerprint: String,
) {
    init {
        require(sourceDocumentFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(sourceExtractionFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(documentNode.resourceId == resource.id)
        require(documentNode.canonicalUrl == resource.canonicalUrl)
        require(documentNode.sourceDocumentFingerprint == sourceDocumentFingerprint)
        require(claimNodes == claimNodes.distinctBy { it.candidate.id }
            .sortedWith(citationClaimOrder()))
        require(edges == edges.distinctBy { it.fingerprint }
            .sortedWith(citationEdgeOrder()))
        require(claimNodes.all { it.candidate.resourceId == resource.id })
        require(claimNodes.all {
            it.candidate.sourceDocumentFingerprint == sourceDocumentFingerprint
        })
        require(edges.size == claimNodes.size)
        require(
            edges.map { it.claimCandidateId }.toSet() ==
                claimNodes.map { it.candidate.id }.toSet()
        )
        require(edges.all {
            it.documentNodeFingerprint == documentNode.fingerprint &&
                it.kind == WebCitationEdgeKind.DOCUMENT_CONTAINS_CLAIM
        })
        require(
            fingerprint == graphFingerprint(
                resource = resource,
                sourceDocumentFingerprint = sourceDocumentFingerprint,
                sourceExtractionFingerprint = sourceExtractionFingerprint,
                documentNode = documentNode,
                claimNodes = claimNodes,
                edges = edges,
            )
        )
    }

    val provenanceBound: Boolean
        get() = true

    val truthAuthority: Boolean
        get() = false

    val evidenceAuthority: Boolean
        get() = false

    val trustAuthority: Boolean
        get() = false

    val executionAuthority: Boolean
        get() = false
}

/**
 * B395 binds B394 claim candidates to exact B391/B393 source identity and character spans.
 *
 * The graph proves only provenance/addressability: which exact acquired document and source span
 * produced a candidate. It does not decide truth, source reliability, evidentiary weight,
 * semantic equivalence, citation sufficiency, or execution permission.
 */
class WebCitationGraphBuilder {
    fun build(
        resource: WebResourceIdentity,
        document: WebIngestDocument,
        extraction: WebClaimExtractionResult,
    ): WebCitationGraph {
        require(resource.id == document.resourceId) {
            "Web citation resource does not match B393 document"
        }
        require(extraction.resourceId == resource.id) {
            "Web citation extraction belongs to another resource"
        }
        require(extraction.sourceDocumentFingerprint == document.fingerprint) {
            "Web citation extraction belongs to another B393 document"
        }

        val sourceText = sourceText(document)
        val sourceTextFingerprint = sourceText?.let(::textSha256)
        require(extraction.sourceTextFingerprint == sourceTextFingerprint) {
            "Web citation extraction source text does not match B393 document"
        }
        if (sourceText == null) {
            require(extraction.candidates.isEmpty()) {
                "Textless B393 document cannot carry B394 claim candidates"
            }
        }

        val documentNode = documentNode(resource, document)
        val claimNodes = extraction.candidates.map { candidate ->
            requireNotNull(sourceText) {
                "Web citation candidate requires source text"
            }
            require(candidate.endCharExclusive <= sourceText.length) {
                "Web citation candidate span is outside exact source text"
            }
            require(
                sourceText.substring(
                    candidate.startChar,
                    candidate.endCharExclusive,
                ) == candidate.text
            ) {
                "Web citation candidate text does not match exact source span"
            }
            claimNode(resource, candidate)
        }.sortedWith(citationClaimOrder())

        val edges = claimNodes.map { node ->
            val fingerprint = citationFingerprint(
                "web-citation-edge/v1",
                documentNode.fingerprint,
                node.candidate.id,
                WebCitationEdgeKind.DOCUMENT_CONTAINS_CLAIM.name,
            )
            WebCitationEdge(
                documentNodeFingerprint = documentNode.fingerprint,
                claimCandidateId = node.candidate.id,
                kind = WebCitationEdgeKind.DOCUMENT_CONTAINS_CLAIM,
                fingerprint = fingerprint,
            )
        }.sortedWith(citationEdgeOrder())

        val fingerprint = graphFingerprint(
            resource = resource,
            sourceDocumentFingerprint = document.fingerprint,
            sourceExtractionFingerprint = extraction.fingerprint,
            documentNode = documentNode,
            claimNodes = claimNodes,
            edges = edges,
        )
        return WebCitationGraph(
            resource = resource,
            sourceDocumentFingerprint = document.fingerprint,
            sourceExtractionFingerprint = extraction.fingerprint,
            documentNode = documentNode,
            claimNodes = claimNodes,
            edges = edges,
            fingerprint = fingerprint,
        )
    }

    private fun sourceText(document: WebIngestDocument): String? =
        if (document.state == WebIngestState.INGESTED_TEXT) {
            when (document.format) {
                WebIngestFormat.JSON,
                WebIngestFormat.CSV -> document.rawText
                else -> document.visibleText
            }
        } else {
            null
        }

    private fun documentNode(
        resource: WebResourceIdentity,
        document: WebIngestDocument,
    ): WebCitationDocumentNode {
        val fingerprint = citationFingerprint(
            "web-citation-document-node/v1",
            resource.id.value,
            resource.canonicalUrl,
            document.fingerprint,
            document.acquisitionReceiptFingerprint,
            document.payloadSha256,
            document.format.name,
        )
        return WebCitationDocumentNode(
            resourceId = resource.id,
            canonicalUrl = resource.canonicalUrl,
            sourceDocumentFingerprint = document.fingerprint,
            acquisitionReceiptFingerprint = document.acquisitionReceiptFingerprint,
            payloadSha256 = document.payloadSha256,
            format = document.format,
            fingerprint = fingerprint,
        )
    }

    private fun claimNode(
        resource: WebResourceIdentity,
        candidate: WebClaimCandidate,
    ): WebCitationClaimNode {
        val quoteFingerprint = textSha256(candidate.text)
        val anchorFingerprint = citationFingerprint(
            "web-citation-anchor/v1",
            resource.id.value,
            resource.canonicalUrl,
            candidate.sourceDocumentFingerprint,
            candidate.sourceTextFingerprint,
            candidate.startChar.toString(),
            candidate.endCharExclusive.toString(),
            quoteFingerprint,
        )
        val anchor = WebCitationAnchor(
            resourceId = resource.id,
            canonicalUrl = resource.canonicalUrl,
            sourceDocumentFingerprint = candidate.sourceDocumentFingerprint,
            sourceTextFingerprint = candidate.sourceTextFingerprint,
            startChar = candidate.startChar,
            endCharExclusive = candidate.endCharExclusive,
            quoteFingerprint = quoteFingerprint,
            fingerprint = anchorFingerprint,
        )
        val fingerprint = citationFingerprint(
            "web-citation-claim-node/v1",
            candidate.id,
            candidate.fingerprint,
            anchor.fingerprint,
        )
        return WebCitationClaimNode(
            candidate = candidate,
            anchor = anchor,
            fingerprint = fingerprint,
        )
    }
}

private fun citationClaimOrder(): Comparator<WebCitationClaimNode> =
    compareBy<WebCitationClaimNode> { it.candidate.startChar }
        .thenBy { it.candidate.endCharExclusive }
        .thenBy { it.candidate.id }

private fun citationEdgeOrder(): Comparator<WebCitationEdge> =
    compareBy<WebCitationEdge> { it.claimCandidateId }
        .thenBy { it.kind.name }
        .thenBy { it.fingerprint }

private fun graphFingerprint(
    resource: WebResourceIdentity,
    sourceDocumentFingerprint: String,
    sourceExtractionFingerprint: String,
    documentNode: WebCitationDocumentNode,
    claimNodes: List<WebCitationClaimNode>,
    edges: List<WebCitationEdge>,
): String = citationFingerprint(
    "web-citation-graph/v1",
    resource.id.value,
    resource.canonicalUrl,
    sourceDocumentFingerprint,
    sourceExtractionFingerprint,
    documentNode.fingerprint,
    *claimNodes.map { "claim:" + it.fingerprint }.toTypedArray(),
    *edges.map { "edge:" + it.fingerprint }.toTypedArray(),
)

private fun textSha256(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

private fun citationFingerprint(
    domain: String,
    vararg parts: String,
): String {
    val digest = MessageDigest.getInstance("SHA-256")
    fun update(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        digest.update(
            byteArrayOf(
                (bytes.size ushr 24).toByte(),
                (bytes.size ushr 16).toByte(),
                (bytes.size ushr 8).toByte(),
                bytes.size.toByte(),
            )
        )
        digest.update(bytes)
    }
    update(domain)
    parts.forEach(::update)
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
