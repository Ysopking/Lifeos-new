package app.lifeos.core.runtime.web

import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

@JvmInline
value class WebAcquisitionRequestId(val value: String) {
    init {
        require(value.startsWith(PREFIX))
        require(value.removePrefix(PREFIX).matches(Regex("[0-9a-f]{64}")))
    }

    companion object {
        const val PREFIX = "web-acquisition:"
    }
}

enum class WebAcquisitionOutcome {
    ACQUIRED,
    NOT_MODIFIED,
    HTTP_ERROR,
}

data class WebAcquisitionRequest(
    val id: WebAcquisitionRequestId,
    val resource: WebResourceIdentity,
    val maxBytes: Int,
    val maxRedirects: Int,
    val acceptedMediaTypes: List<String>,
) {
    init {
        require(maxBytes in 1..MAX_ACQUISITION_BYTES)
        require(maxRedirects in 0..MAX_REDIRECTS)
        require(acceptedMediaTypes.isNotEmpty())
        require(acceptedMediaTypes == normalizeMediaTypes(acceptedMediaTypes))
        require(id == expectedId())
    }

    val executionAuthority: Boolean
        get() = false

    val permissionAuthority: Boolean
        get() = false

    fun fingerprint(): String = acquisitionFingerprint(
        "web-acquisition-request/v1",
        resource.id.value,
        maxBytes.toString(),
        maxRedirects.toString(),
        *acceptedMediaTypes.toTypedArray(),
    )

    private fun expectedId(): WebAcquisitionRequestId =
        WebAcquisitionRequestId(WebAcquisitionRequestId.PREFIX + fingerprint())

    companion object {
        fun create(
            resource: WebResourceIdentity,
            maxBytes: Int = DEFAULT_MAX_BYTES,
            maxRedirects: Int = DEFAULT_MAX_REDIRECTS,
            acceptedMediaTypes: Collection<String> = DEFAULT_ACCEPTED_MEDIA_TYPES,
        ): WebAcquisitionRequest {
            val mediaTypes = normalizeMediaTypes(acceptedMediaTypes)
            val fingerprint = acquisitionFingerprint(
                "web-acquisition-request/v1",
                resource.id.value,
                maxBytes.toString(),
                maxRedirects.toString(),
                *mediaTypes.toTypedArray(),
            )
            return WebAcquisitionRequest(
                id = WebAcquisitionRequestId(
                    WebAcquisitionRequestId.PREFIX + fingerprint
                ),
                resource = resource,
                maxBytes = maxBytes,
                maxRedirects = maxRedirects,
                acceptedMediaTypes = mediaTypes,
            )
        }

        const val DEFAULT_MAX_BYTES = 2 * 1024 * 1024
        const val DEFAULT_MAX_REDIRECTS = 4
        val DEFAULT_ACCEPTED_MEDIA_TYPES: List<String> = listOf(
            "application/json",
            "application/pdf",
            "application/xhtml+xml",
            "application/xml",
            "text/csv",
            "text/html",
            "text/plain",
            "text/xml",
        )
    }
}

data class WebAcquisitionTransportRequest(
    val acquisitionRequestId: WebAcquisitionRequestId,
    val resource: WebResourceIdentity,
    val maxBytes: Int,
    val acceptedMediaTypes: List<String>,
) {
    init {
        require(maxBytes in 1..MAX_ACQUISITION_BYTES)
        require(acceptedMediaTypes == normalizeMediaTypes(acceptedMediaTypes))
    }
}

data class WebAcquisitionTransportResponse(
    val statusCode: Int,
    val contentType: String? = null,
    val location: String? = null,
    val etag: String? = null,
    val lastModified: String? = null,
    val body: ByteArray = byteArrayOf(),
) {
    init {
        require(statusCode in 100..599)
        contentType?.let { require(it.isNotBlank()) }
        location?.let { require(it.isNotBlank()) }
        etag?.let { require(it.isNotBlank()) }
        lastModified?.let { require(it.isNotBlank()) }
    }
}

fun interface WebAcquisitionTransport {
    suspend fun fetch(request: WebAcquisitionTransportRequest): WebAcquisitionTransportResponse
}

data class WebAcquisitionRedirect(
    val from: WebResourceIdentity,
    val to: WebResourceIdentity,
    val statusCode: Int,
    val responseFingerprint: String,
) {
    init {
        require(statusCode in REDIRECT_STATUS_CODES)
        require(from.id != to.id)
        require(responseFingerprint.matches(Regex("[0-9a-f]{64}")))
    }

    fun fingerprint(): String = acquisitionFingerprint(
        "web-acquisition-redirect/v1",
        from.id.value,
        to.id.value,
        statusCode.toString(),
        responseFingerprint,
    )
}

class WebAcquisitionPayload private constructor(
    bytes: ByteArray,
    val sha256: String,
) {
    private val content: ByteArray = bytes.copyOf()

    val size: Int
        get() = content.size

    fun bytes(): ByteArray = content.copyOf()

    override fun equals(other: Any?): Boolean =
        other is WebAcquisitionPayload &&
            sha256 == other.sha256 &&
            content.contentEquals(other.content)

    override fun hashCode(): Int =
        31 * sha256.hashCode() + content.contentHashCode()

    companion object {
        fun create(bytes: ByteArray): WebAcquisitionPayload =
            WebAcquisitionPayload(
                bytes = bytes,
                sha256 = sha256(bytes),
            )
    }
}

data class WebAcquisitionReceipt(
    val requestId: WebAcquisitionRequestId,
    val requestFingerprint: String,
    val requestedResourceId: WebResourceId,
    val finalResourceId: WebResourceId,
    val redirects: List<WebAcquisitionRedirect>,
    val outcome: WebAcquisitionOutcome,
    val statusCode: Int,
    val contentType: String?,
    val byteCount: Int,
    val payloadSha256: String?,
    val etag: String?,
    val lastModified: String?,
    val responseFingerprint: String,
    val fingerprint: String,
) {
    init {
        require(requestFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(redirects == redirects.distinctBy { it.fingerprint() })
        require(statusCode in 100..599)
        require(byteCount >= 0)
        require(responseFingerprint.matches(Regex("[0-9a-f]{64}")))
        when (outcome) {
            WebAcquisitionOutcome.ACQUIRED -> require(payloadSha256 != null)
            WebAcquisitionOutcome.NOT_MODIFIED,
            WebAcquisitionOutcome.HTTP_ERROR -> require(payloadSha256 == null)
        }
        require(
            fingerprint == receiptFingerprint(
                requestId = requestId,
                requestFingerprint = requestFingerprint,
                requestedResourceId = requestedResourceId,
                finalResourceId = finalResourceId,
                redirects = redirects,
                outcome = outcome,
                statusCode = statusCode,
                contentType = contentType,
                byteCount = byteCount,
                payloadSha256 = payloadSha256,
                etag = etag,
                lastModified = lastModified,
                responseFingerprint = responseFingerprint,
            )
        )
    }

    val trustAuthority: Boolean
        get() = false

    val truthAuthority: Boolean
        get() = false

    val mutationAuthority: Boolean
        get() = false

    val executionAuthority: Boolean
        get() = false
}

data class WebAcquisitionResult(
    val finalResource: WebResourceIdentity,
    val receipt: WebAcquisitionReceipt,
    val payload: WebAcquisitionPayload?,
) {
    init {
        require(receipt.finalResourceId == finalResource.id)
        when (receipt.outcome) {
            WebAcquisitionOutcome.ACQUIRED -> {
                require(payload != null)
                require(payload.size == receipt.byteCount)
                require(payload.sha256 == receipt.payloadSha256)
            }
            WebAcquisitionOutcome.NOT_MODIFIED,
            WebAcquisitionOutcome.HTTP_ERROR -> require(payload == null)
        }
    }
}

/**
 * B392 bounded read-only Web acquisition orchestration.
 *
 * The runtime canonicalizes every redirect through B391 identity, enforces byte/media/redirect
 * budgets, and returns an immutable acquisition receipt. It performs no DNS/private-network check
 * and grants no network permission by itself: the host transport remains responsible for network
 * admission and must be invoked behind the existing Owner Policy effect gate.
 */
class WebAcquisitionRuntime(
    private val transport: WebAcquisitionTransport,
) {
    suspend fun acquire(request: WebAcquisitionRequest): WebAcquisitionResult {
        var current = request.resource
        val visited = linkedSetOf(current.id)
        val redirects = mutableListOf<WebAcquisitionRedirect>()

        while (true) {
            val response = transport.fetch(
                WebAcquisitionTransportRequest(
                    acquisitionRequestId = request.id,
                    resource = current,
                    maxBytes = request.maxBytes,
                    acceptedMediaTypes = request.acceptedMediaTypes,
                )
            )
            require(response.body.size <= request.maxBytes) {
                "Web acquisition response exceeded maxBytes"
            }
            val responseFingerprint = transportResponseFingerprint(response)

            if (response.statusCode in REDIRECT_STATUS_CODES) {
                require(redirects.size < request.maxRedirects) {
                    "Web acquisition redirect budget exhausted"
                }
                val location = requireNotNull(response.location) {
                    "Web acquisition redirect requires Location"
                }
                val nextRaw = URI(current.canonicalUrl).resolve(location).toString()
                val next = WebResourceIdentity.parse(nextRaw)
                require(next.id !in visited) {
                    "Web acquisition redirect loop detected"
                }
                redirects += WebAcquisitionRedirect(
                    from = current,
                    to = next,
                    statusCode = response.statusCode,
                    responseFingerprint = responseFingerprint,
                )
                visited += next.id
                current = next
                continue
            }

            val normalizedContentType = response.contentType
                ?.let(::normalizeResponseContentType)
            return when {
                response.statusCode == 304 -> {
                    require(response.body.isEmpty()) {
                        "304 Web acquisition response must not carry a payload"
                    }
                    result(
                        request = request,
                        finalResource = current,
                        redirects = redirects,
                        outcome = WebAcquisitionOutcome.NOT_MODIFIED,
                        response = response,
                        normalizedContentType = normalizedContentType,
                        responseFingerprint = responseFingerprint,
                        payload = null,
                    )
                }
                response.statusCode in 200..299 -> {
                    if (response.body.isNotEmpty()) {
                        val type = requireNotNull(normalizedContentType) {
                            "Acquired Web payload requires Content-Type"
                        }
                        require(mediaTypeAccepted(type, request.acceptedMediaTypes)) {
                            "Web acquisition Content-Type is not accepted: $type"
                        }
                    }
                    val payload = WebAcquisitionPayload.create(response.body)
                    result(
                        request = request,
                        finalResource = current,
                        redirects = redirects,
                        outcome = WebAcquisitionOutcome.ACQUIRED,
                        response = response,
                        normalizedContentType = normalizedContentType,
                        responseFingerprint = responseFingerprint,
                        payload = payload,
                    )
                }
                else -> result(
                    request = request,
                    finalResource = current,
                    redirects = redirects,
                    outcome = WebAcquisitionOutcome.HTTP_ERROR,
                    response = response,
                    normalizedContentType = normalizedContentType,
                    responseFingerprint = responseFingerprint,
                    payload = null,
                )
            }
        }
    }

    private fun result(
        request: WebAcquisitionRequest,
        finalResource: WebResourceIdentity,
        redirects: List<WebAcquisitionRedirect>,
        outcome: WebAcquisitionOutcome,
        response: WebAcquisitionTransportResponse,
        normalizedContentType: String?,
        responseFingerprint: String,
        payload: WebAcquisitionPayload?,
    ): WebAcquisitionResult {
        val receiptFingerprint = receiptFingerprint(
            requestId = request.id,
            requestFingerprint = request.fingerprint(),
            requestedResourceId = request.resource.id,
            finalResourceId = finalResource.id,
            redirects = redirects,
            outcome = outcome,
            statusCode = response.statusCode,
            contentType = normalizedContentType,
            byteCount = response.body.size,
            payloadSha256 = payload?.sha256,
            etag = response.etag,
            lastModified = response.lastModified,
            responseFingerprint = responseFingerprint,
        )
        val receipt = WebAcquisitionReceipt(
            requestId = request.id,
            requestFingerprint = request.fingerprint(),
            requestedResourceId = request.resource.id,
            finalResourceId = finalResource.id,
            redirects = redirects.toList(),
            outcome = outcome,
            statusCode = response.statusCode,
            contentType = normalizedContentType,
            byteCount = response.body.size,
            payloadSha256 = payload?.sha256,
            etag = response.etag,
            lastModified = response.lastModified,
            responseFingerprint = responseFingerprint,
            fingerprint = receiptFingerprint,
        )
        return WebAcquisitionResult(
            finalResource = finalResource,
            receipt = receipt,
            payload = payload,
        )
    }
}

private fun normalizeMediaTypes(values: Collection<String>): List<String> =
    values.map(::normalizeMediaTypePattern)
        .distinct()
        .sorted()

private fun normalizeMediaTypePattern(value: String): String {
    val normalized = value.trim().lowercase()
    require(normalized.isNotBlank())
    require(';' !in normalized) {
        "Accepted media types must not carry parameters"
    }
    val parts = normalized.split('/')
    require(parts.size == 2 && parts.none { it.isBlank() })
    require(parts[0] != "*" || parts[1] == "*") {
        "Wildcard top-level media type requires */*"
    }
    return normalized
}

private fun normalizeResponseContentType(value: String): String {
    val normalized = value.substringBefore(';').trim().lowercase()
    val parts = normalized.split('/')
    require(parts.size == 2 && parts.none { it.isBlank() }) {
        "Invalid Web acquisition Content-Type"
    }
    return normalized
}

private fun mediaTypeAccepted(
    contentType: String,
    accepted: List<String>,
): Boolean {
    val (type, subtype) = contentType.split('/', limit = 2)
    return accepted.any { pattern ->
        val (acceptedType, acceptedSubtype) = pattern.split('/', limit = 2)
        (acceptedType == "*" || acceptedType == type) &&
            (acceptedSubtype == "*" || acceptedSubtype == subtype)
    }
}

private fun transportResponseFingerprint(
    response: WebAcquisitionTransportResponse,
): String = acquisitionFingerprint(
    "web-acquisition-transport-response/v1",
    response.statusCode.toString(),
    response.contentType.orEmpty(),
    response.location.orEmpty(),
    response.etag.orEmpty(),
    response.lastModified.orEmpty(),
    sha256(response.body),
)

private fun receiptFingerprint(
    requestId: WebAcquisitionRequestId,
    requestFingerprint: String,
    requestedResourceId: WebResourceId,
    finalResourceId: WebResourceId,
    redirects: List<WebAcquisitionRedirect>,
    outcome: WebAcquisitionOutcome,
    statusCode: Int,
    contentType: String?,
    byteCount: Int,
    payloadSha256: String?,
    etag: String?,
    lastModified: String?,
    responseFingerprint: String,
): String = acquisitionFingerprint(
    "web-acquisition-receipt/v1",
    requestId.value,
    requestFingerprint,
    requestedResourceId.value,
    finalResourceId.value,
    outcome.name,
    statusCode.toString(),
    contentType.orEmpty(),
    byteCount.toString(),
    payloadSha256.orEmpty(),
    etag.orEmpty(),
    lastModified.orEmpty(),
    responseFingerprint,
    *redirects.map { it.fingerprint() }.toTypedArray(),
)

private fun acquisitionFingerprint(
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

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

private const val MAX_ACQUISITION_BYTES = 8 * 1024 * 1024
private const val MAX_REDIRECTS = 8
private val REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)
