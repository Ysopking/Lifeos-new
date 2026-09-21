package app.lifeos.core.runtime.web

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

@JvmInline
value class BrowserReadRequestId(val value: String) {
    init {
        require(value.startsWith(PREFIX))
        require(value.removePrefix(PREFIX).matches(Regex("[0-9a-f]{64}")))
    }

    companion object {
        const val PREFIX = "browser-read:"
    }
}

enum class BrowserReadOutcome {
    READ,
    NOT_MODIFIED,
    ACQUISITION_FAILED,
}

data class BrowserReadRequest(
    val id: BrowserReadRequestId,
    val acquisitionRequest: WebAcquisitionRequest,
    val ingestPolicy: WebIngestPolicy,
    val claimPolicy: WebClaimExtractionPolicy,
) {
    init {
        require(id == expectedId())
    }

    val navigationAuthority: Boolean get() = false
    val formSubmissionAuthority: Boolean get() = false
    val downloadAuthority: Boolean get() = false
    val uploadAuthority: Boolean get() = false
    val permissionAuthority: Boolean get() = false
    val executionAuthority: Boolean get() = false

    fun fingerprint(): String = browserReadFingerprint(
        "browser-read-request/v1",
        acquisitionRequest.id.value,
        ingestPolicy.fingerprint(),
        claimPolicy.fingerprint(),
    )

    private fun expectedId(): BrowserReadRequestId =
        BrowserReadRequestId(BrowserReadRequestId.PREFIX + fingerprint())

    companion object {
        fun create(
            acquisitionRequest: WebAcquisitionRequest,
            ingestPolicy: WebIngestPolicy = WebIngestPolicy(),
            claimPolicy: WebClaimExtractionPolicy = WebClaimExtractionPolicy(),
        ): BrowserReadRequest {
            val fingerprint = browserReadFingerprint(
                "browser-read-request/v1",
                acquisitionRequest.id.value,
                ingestPolicy.fingerprint(),
                claimPolicy.fingerprint(),
            )
            return BrowserReadRequest(
                id = BrowserReadRequestId(BrowserReadRequestId.PREFIX + fingerprint),
                acquisitionRequest = acquisitionRequest,
                ingestPolicy = ingestPolicy,
                claimPolicy = claimPolicy,
            )
        }
    }
}

data class BrowserReadResult(
    val requestId: BrowserReadRequestId,
    val requestFingerprint: String,
    val finalResource: WebResourceIdentity,
    val acquisitionReceipt: WebAcquisitionReceipt,
    val outcome: BrowserReadOutcome,
    val document: WebIngestDocument?,
    val claims: WebClaimExtractionResult?,
    val citations: WebCitationGraph?,
    val fingerprint: String,
) {
    init {
        require(requestFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(acquisitionReceipt.finalResourceId == finalResource.id)
        when (outcome) {
            BrowserReadOutcome.READ -> {
                require(acquisitionReceipt.outcome == WebAcquisitionOutcome.ACQUIRED)
                require(document != null && claims != null && citations != null)
                require(document.resourceId == finalResource.id)
                require(claims.resourceId == finalResource.id)
                require(claims.sourceDocumentFingerprint == document.fingerprint)
                require(citations.resource.id == finalResource.id)
                require(citations.sourceDocumentFingerprint == document.fingerprint)
                require(citations.sourceExtractionFingerprint == claims.fingerprint)
            }
            BrowserReadOutcome.NOT_MODIFIED -> {
                require(acquisitionReceipt.outcome == WebAcquisitionOutcome.NOT_MODIFIED)
                require(document == null && claims == null && citations == null)
            }
            BrowserReadOutcome.ACQUISITION_FAILED -> {
                require(acquisitionReceipt.outcome == WebAcquisitionOutcome.HTTP_ERROR)
                require(document == null && claims == null && citations == null)
            }
        }
        require(
            fingerprint == resultFingerprint(
                requestId = requestId,
                requestFingerprint = requestFingerprint,
                finalResource = finalResource,
                acquisitionReceipt = acquisitionReceipt,
                outcome = outcome,
                document = document,
                claims = claims,
                citations = citations,
            )
        )
    }

    val truthAuthority: Boolean get() = false
    val evidenceAuthority: Boolean get() = false
    val navigationAuthority: Boolean get() = false
    val formSubmissionAuthority: Boolean get() = false
    val downloadAuthority: Boolean get() = false
    val uploadAuthority: Boolean get() = false
    val permissionAuthority: Boolean get() = false
    val executionAuthority: Boolean get() = false
}

/**
 * B401 composes the existing bounded read pipeline only:
 * B392 acquisition -> B393 ingest -> B394 claim candidates -> B395 citation provenance.
 *
 * It does not navigate a browser, submit forms, upload/download files, mutate browser storage,
 * grant network permission, or treat source text/claims as truth. Browser actions remain B404+.
 */
class BrowserReadRuntime(
    private val acquisitionRuntime: WebAcquisitionRuntime,
    private val ingestor: WebMultiFormatIngestor = WebMultiFormatIngestor(),
    private val claimExtractor: WebClaimExtractor = WebClaimExtractor(),
    private val citationGraphBuilder: WebCitationGraphBuilder = WebCitationGraphBuilder(),
) {
    suspend fun read(request: BrowserReadRequest): BrowserReadResult {
        val acquisition = acquisitionRuntime.acquire(request.acquisitionRequest)
        return when (acquisition.receipt.outcome) {
            WebAcquisitionOutcome.ACQUIRED -> {
                val document = ingestor.ingest(acquisition, request.ingestPolicy)
                val claims = claimExtractor.extract(document, request.claimPolicy)
                val citations = citationGraphBuilder.build(
                    resource = acquisition.finalResource,
                    document = document,
                    extraction = claims,
                )
                result(
                    request = request,
                    acquisition = acquisition,
                    outcome = BrowserReadOutcome.READ,
                    document = document,
                    claims = claims,
                    citations = citations,
                )
            }
            WebAcquisitionOutcome.NOT_MODIFIED -> result(
                request = request,
                acquisition = acquisition,
                outcome = BrowserReadOutcome.NOT_MODIFIED,
                document = null,
                claims = null,
                citations = null,
            )
            WebAcquisitionOutcome.HTTP_ERROR -> result(
                request = request,
                acquisition = acquisition,
                outcome = BrowserReadOutcome.ACQUISITION_FAILED,
                document = null,
                claims = null,
                citations = null,
            )
        }
    }

    private fun result(
        request: BrowserReadRequest,
        acquisition: WebAcquisitionResult,
        outcome: BrowserReadOutcome,
        document: WebIngestDocument?,
        claims: WebClaimExtractionResult?,
        citations: WebCitationGraph?,
    ): BrowserReadResult {
        val fingerprint = resultFingerprint(
            requestId = request.id,
            requestFingerprint = request.fingerprint(),
            finalResource = acquisition.finalResource,
            acquisitionReceipt = acquisition.receipt,
            outcome = outcome,
            document = document,
            claims = claims,
            citations = citations,
        )
        return BrowserReadResult(
            requestId = request.id,
            requestFingerprint = request.fingerprint(),
            finalResource = acquisition.finalResource,
            acquisitionReceipt = acquisition.receipt,
            outcome = outcome,
            document = document,
            claims = claims,
            citations = citations,
            fingerprint = fingerprint,
        )
    }
}

private fun resultFingerprint(
    requestId: BrowserReadRequestId,
    requestFingerprint: String,
    finalResource: WebResourceIdentity,
    acquisitionReceipt: WebAcquisitionReceipt,
    outcome: BrowserReadOutcome,
    document: WebIngestDocument?,
    claims: WebClaimExtractionResult?,
    citations: WebCitationGraph?,
): String = browserReadFingerprint(
    "browser-read-result/v1",
    requestId.value,
    requestFingerprint,
    finalResource.id.value,
    finalResource.canonicalUrl,
    acquisitionReceipt.fingerprint,
    outcome.name,
    document?.fingerprint.orEmpty(),
    claims?.fingerprint.orEmpty(),
    citations?.fingerprint.orEmpty(),
)

private fun browserReadFingerprint(
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
