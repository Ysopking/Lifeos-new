package app.lifeos.core.runtime.web

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

@JvmInline
value class WebDownloadRequestId(val value: String) {
    init {
        require(value.startsWith(PREFIX))
        require(value.removePrefix(PREFIX).matches(Regex("[0-9a-f]{64}")))
    }

    companion object {
        const val PREFIX = "web-download:"
    }
}

enum class WebDownloadOutcome {
    DOWNLOADED,
    NOT_MODIFIED,
    ACQUISITION_FAILED,
}

data class WebDownloadRequest(
    val id: WebDownloadRequestId,
    val acquisitionRequest: WebAcquisitionRequest,
) {
    init {
        require(id == expectedId())
    }

    val networkAuthority: Boolean get() = false
    val fileWriteAuthority: Boolean get() = false
    val permissionAuthority: Boolean get() = false
    val executionAuthority: Boolean get() = false

    fun fingerprint(): String = webTransferFingerprint(
        "web-download-request/v1",
        acquisitionRequest.id.value,
    )

    private fun expectedId(): WebDownloadRequestId =
        WebDownloadRequestId(WebDownloadRequestId.PREFIX + fingerprint())

    companion object {
        fun create(acquisitionRequest: WebAcquisitionRequest): WebDownloadRequest {
            val fp = webTransferFingerprint(
                "web-download-request/v1",
                acquisitionRequest.id.value,
            )
            return WebDownloadRequest(
                id = WebDownloadRequestId(WebDownloadRequestId.PREFIX + fp),
                acquisitionRequest = acquisitionRequest,
            )
        }
    }
}

data class WebDownloadResult(
    val requestId: WebDownloadRequestId,
    val requestFingerprint: String,
    val finalResource: WebResourceIdentity,
    val acquisitionReceipt: WebAcquisitionReceipt,
    val outcome: WebDownloadOutcome,
    val payload: WebAcquisitionPayload?,
    val fingerprint: String,
) {
    init {
        require(requestFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(acquisitionReceipt.finalResourceId == finalResource.id)
        when (outcome) {
            WebDownloadOutcome.DOWNLOADED -> {
                require(acquisitionReceipt.outcome == WebAcquisitionOutcome.ACQUIRED)
                require(payload != null)
                require(payload.size == acquisitionReceipt.byteCount)
                require(payload.sha256 == acquisitionReceipt.payloadSha256)
            }
            WebDownloadOutcome.NOT_MODIFIED -> {
                require(acquisitionReceipt.outcome == WebAcquisitionOutcome.NOT_MODIFIED)
                require(payload == null)
            }
            WebDownloadOutcome.ACQUISITION_FAILED -> {
                require(acquisitionReceipt.outcome == WebAcquisitionOutcome.HTTP_ERROR)
                require(payload == null)
            }
        }
        require(
            fingerprint == downloadResultFingerprint(
                requestId = requestId,
                requestFingerprint = requestFingerprint,
                finalResource = finalResource,
                acquisitionReceipt = acquisitionReceipt,
                outcome = outcome,
                payload = payload,
            )
        )
    }

    val networkAuthority: Boolean get() = false
    val fileWriteAuthority: Boolean get() = false
    val permissionAuthority: Boolean get() = false
    val executionAuthority: Boolean get() = false
}

/**
 * B403 download composition over B392 only.
 *
 * It returns bounded bytes plus the exact acquisition receipt. Persisting those bytes is a separate
 * FILE_WRITE effect and remains outside this module. Network permission remains outside the runtime
 * and must be enforced around the host transport.
 */
class WebDownloadRuntime(
    private val acquisitionRuntime: WebAcquisitionRuntime,
) {
    suspend fun download(request: WebDownloadRequest): WebDownloadResult {
        val acquisition = acquisitionRuntime.acquire(request.acquisitionRequest)
        val outcome = when (acquisition.receipt.outcome) {
            WebAcquisitionOutcome.ACQUIRED -> WebDownloadOutcome.DOWNLOADED
            WebAcquisitionOutcome.NOT_MODIFIED -> WebDownloadOutcome.NOT_MODIFIED
            WebAcquisitionOutcome.HTTP_ERROR -> WebDownloadOutcome.ACQUISITION_FAILED
        }
        val payload = acquisition.payload
        val fingerprint = downloadResultFingerprint(
            requestId = request.id,
            requestFingerprint = request.fingerprint(),
            finalResource = acquisition.finalResource,
            acquisitionReceipt = acquisition.receipt,
            outcome = outcome,
            payload = payload,
        )
        return WebDownloadResult(
            requestId = request.id,
            requestFingerprint = request.fingerprint(),
            finalResource = acquisition.finalResource,
            acquisitionReceipt = acquisition.receipt,
            outcome = outcome,
            payload = payload,
            fingerprint = fingerprint,
        )
    }
}

@JvmInline
value class WebUploadOperationKey(val value: String) {
    init {
        require(value.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))) {
            "Invalid Web upload operation key"
        }
    }
}

@JvmInline
value class WebUploadRequestId(val value: String) {
    init {
        require(value.startsWith(PREFIX))
        require(value.removePrefix(PREFIX).matches(Regex("[0-9a-f]{64}")))
    }

    companion object {
        const val PREFIX = "web-upload:"
    }
}

enum class WebUploadMethod {
    POST,
    PUT,
}

class WebUploadPayload private constructor(
    bytes: ByteArray,
    val sha256: String,
) {
    private val content = bytes.copyOf()

    val size: Int
        get() = content.size

    fun bytes(): ByteArray = content.copyOf()

    override fun equals(other: Any?): Boolean =
        other is WebUploadPayload &&
            sha256 == other.sha256 &&
            content.contentEquals(other.content)

    override fun hashCode(): Int =
        31 * sha256.hashCode() + content.contentHashCode()

    override fun toString(): String = "WebUploadPayload(REDACTED," + content.size + "B)"

    companion object {
        fun create(bytes: ByteArray): WebUploadPayload {
            require(bytes.isNotEmpty()) { "Web upload payload must not be empty" }
            require(bytes.size <= MAX_WEB_UPLOAD_BYTES) {
                "Web upload payload exceeds bounded size"
            }
            val copy = bytes.copyOf()
            return WebUploadPayload(copy, sha256(copy))
        }
    }
}

data class WebUploadRequest(
    val id: WebUploadRequestId,
    val operationKey: WebUploadOperationKey,
    val target: WebResourceIdentity,
    val method: WebUploadMethod,
    val contentType: String,
    val payload: WebUploadPayload,
    val maxResponseBytes: Int,
) {
    init {
        require(contentType == normalizeUploadContentType(contentType))
        require(maxResponseBytes in 0..MAX_WEB_UPLOAD_RESPONSE_BYTES)
        require(id == expectedId())
    }

    val networkAuthority: Boolean get() = false
    val permissionAuthority: Boolean get() = false
    val executionAuthority: Boolean get() = false

    fun fingerprint(): String = webTransferFingerprint(
        "web-upload-request-fingerprint/v1",
        id.value,
        payload.sha256,
    )

    private fun expectedId(): WebUploadRequestId =
        WebUploadRequestId(
            WebUploadRequestId.PREFIX +
                webTransferFingerprint(
                    "web-upload-request/v1",
                    operationKey.value,
                    target.id.value,
                    method.name,
                    contentType,
                    payload.size.toString(),
                    maxResponseBytes.toString(),
                )
        )

    companion object {
        fun create(
            operationKey: WebUploadOperationKey,
            target: WebResourceIdentity,
            method: WebUploadMethod,
            contentType: String,
            payload: WebUploadPayload,
            maxResponseBytes: Int = DEFAULT_MAX_UPLOAD_RESPONSE_BYTES,
        ): WebUploadRequest {
            val normalizedType = normalizeUploadContentType(contentType)
            val id = WebUploadRequestId(
                WebUploadRequestId.PREFIX +
                    webTransferFingerprint(
                        "web-upload-request/v1",
                        operationKey.value,
                        target.id.value,
                        method.name,
                        normalizedType,
                        payload.size.toString(),
                        maxResponseBytes.toString(),
                    )
            )
            return WebUploadRequest(
                id = id,
                operationKey = operationKey,
                target = target,
                method = method,
                contentType = normalizedType,
                payload = payload,
                maxResponseBytes = maxResponseBytes,
            )
        }
    }
}

data class WebUploadTransportRequest(
    val requestId: WebUploadRequestId,
    val operationKey: WebUploadOperationKey,
    val target: WebResourceIdentity,
    val method: WebUploadMethod,
    val contentType: String,
    val body: ByteArray,
    val maxResponseBytes: Int,
) {
    init {
        require(contentType == normalizeUploadContentType(contentType))
        require(body.isNotEmpty() && body.size <= MAX_WEB_UPLOAD_BYTES)
        require(maxResponseBytes in 0..MAX_WEB_UPLOAD_RESPONSE_BYTES)
    }
}

data class WebUploadTransportResponse(
    val statusCode: Int,
    val contentType: String? = null,
    val body: ByteArray = byteArrayOf(),
) {
    init {
        require(statusCode in 100..599)
        contentType?.let { require(it.isNotBlank()) }
    }
}

fun interface WebUploadTransport {
    suspend fun send(request: WebUploadTransportRequest): WebUploadTransportResponse
}

enum class WebUploadOutcome {
    ACCEPTED,
    HTTP_ERROR,
}

data class WebUploadReceipt(
    val requestId: WebUploadRequestId,
    val requestFingerprint: String,
    val targetResourceId: WebResourceId,
    val method: WebUploadMethod,
    val contentType: String,
    val payloadBytes: Int,
    val payloadSha256: String,
    val outcome: WebUploadOutcome,
    val statusCode: Int,
    val responseContentType: String?,
    val responseBytes: Int,
    val responseSha256: String,
    val fingerprint: String,
) {
    init {
        require(requestFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(contentType == normalizeUploadContentType(contentType))
        require(payloadBytes in 1..MAX_WEB_UPLOAD_BYTES)
        require(payloadSha256.matches(Regex("[0-9a-f]{64}")))
        require(statusCode in 100..599)
        require(responseBytes in 0..MAX_WEB_UPLOAD_RESPONSE_BYTES)
        require(responseSha256.matches(Regex("[0-9a-f]{64}")))
        require(
            fingerprint == uploadReceiptFingerprint(
                requestId = requestId,
                requestFingerprint = requestFingerprint,
                targetResourceId = targetResourceId,
                method = method,
                contentType = contentType,
                payloadBytes = payloadBytes,
                payloadSha256 = payloadSha256,
                outcome = outcome,
                statusCode = statusCode,
                responseContentType = responseContentType,
                responseBytes = responseBytes,
                responseSha256 = responseSha256,
            )
        )
    }

    val truthAuthority: Boolean get() = false
    val permissionAuthority: Boolean get() = false
    val executionAuthority: Boolean get() = false
}

class WebUploadResult(
    val target: WebResourceIdentity,
    val receipt: WebUploadReceipt,
    responseBody: ByteArray,
) {
    private val responseContent = responseBody.copyOf()

    init {
        require(receipt.targetResourceId == target.id)
        require(responseContent.size == receipt.responseBytes)
        require(sha256(responseContent) == receipt.responseSha256)
    }

    fun responseBytes(): ByteArray = responseContent.copyOf()

    val permissionAuthority: Boolean get() = false
    val executionAuthority: Boolean get() = false
}

/**
 * B403 exact-target bounded upload orchestration.
 *
 * Redirects are deliberately not followed because replaying POST/PUT bodies across origins or paths
 * is security-sensitive. Authentication/session material is not attached here. The host transport
 * remains responsible for JIT NETWORK_ACCESS authorization and any later browser-action block may
 * explicitly compose B402 session material only after separate policy checks.
 */
class WebUploadRuntime(
    private val transport: WebUploadTransport,
) {
    suspend fun upload(request: WebUploadRequest): WebUploadResult {
        val response = transport.send(
            WebUploadTransportRequest(
                requestId = request.id,
                operationKey = request.operationKey,
                target = request.target,
                method = request.method,
                contentType = request.contentType,
                body = request.payload.bytes(),
                maxResponseBytes = request.maxResponseBytes,
            )
        )
        require(response.body.size <= request.maxResponseBytes) {
            "Web upload response exceeded maxResponseBytes"
        }
        val normalizedResponseType = response.contentType?.let(::normalizeUploadContentType)
        val outcome =
            if (response.statusCode in 200..299) WebUploadOutcome.ACCEPTED
            else WebUploadOutcome.HTTP_ERROR
        val responseSha = sha256(response.body)
        val receiptFingerprint = uploadReceiptFingerprint(
            requestId = request.id,
            requestFingerprint = request.fingerprint(),
            targetResourceId = request.target.id,
            method = request.method,
            contentType = request.contentType,
            payloadBytes = request.payload.size,
            payloadSha256 = request.payload.sha256,
            outcome = outcome,
            statusCode = response.statusCode,
            responseContentType = normalizedResponseType,
            responseBytes = response.body.size,
            responseSha256 = responseSha,
        )
        val receipt = WebUploadReceipt(
            requestId = request.id,
            requestFingerprint = request.fingerprint(),
            targetResourceId = request.target.id,
            method = request.method,
            contentType = request.contentType,
            payloadBytes = request.payload.size,
            payloadSha256 = request.payload.sha256,
            outcome = outcome,
            statusCode = response.statusCode,
            responseContentType = normalizedResponseType,
            responseBytes = response.body.size,
            responseSha256 = responseSha,
            fingerprint = receiptFingerprint,
        )
        return WebUploadResult(
            target = request.target,
            receipt = receipt,
            responseBody = response.body,
        )
    }
}

private fun downloadResultFingerprint(
    requestId: WebDownloadRequestId,
    requestFingerprint: String,
    finalResource: WebResourceIdentity,
    acquisitionReceipt: WebAcquisitionReceipt,
    outcome: WebDownloadOutcome,
    payload: WebAcquisitionPayload?,
): String = webTransferFingerprint(
    "web-download-result/v1",
    requestId.value,
    requestFingerprint,
    finalResource.id.value,
    acquisitionReceipt.fingerprint,
    outcome.name,
    payload?.sha256.orEmpty(),
)

private fun uploadReceiptFingerprint(
    requestId: WebUploadRequestId,
    requestFingerprint: String,
    targetResourceId: WebResourceId,
    method: WebUploadMethod,
    contentType: String,
    payloadBytes: Int,
    payloadSha256: String,
    outcome: WebUploadOutcome,
    statusCode: Int,
    responseContentType: String?,
    responseBytes: Int,
    responseSha256: String,
): String = webTransferFingerprint(
    "web-upload-receipt/v1",
    requestId.value,
    requestFingerprint,
    targetResourceId.value,
    method.name,
    contentType,
    payloadBytes.toString(),
    payloadSha256,
    outcome.name,
    statusCode.toString(),
    responseContentType.orEmpty(),
    responseBytes.toString(),
    responseSha256,
)

private fun normalizeUploadContentType(value: String): String {
    val normalized = value.trim().lowercase()
    require(normalized.isNotBlank())
    require('\r' !in normalized && '\n' !in normalized)
    val media = normalized.substringBefore(';').trim()
    val parts = media.split('/')
    require(parts.size == 2 && parts.none { it.isBlank() }) {
        "Invalid Web upload content type"
    }
    return normalized
}

private fun webTransferFingerprint(
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

private const val MAX_WEB_UPLOAD_BYTES = 8 * 1024 * 1024
private const val MAX_WEB_UPLOAD_RESPONSE_BYTES = 512 * 1024
private const val DEFAULT_MAX_UPLOAD_RESPONSE_BYTES = 64 * 1024
