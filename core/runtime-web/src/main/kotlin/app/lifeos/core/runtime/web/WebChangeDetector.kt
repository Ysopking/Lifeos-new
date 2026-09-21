package app.lifeos.core.runtime.web

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

enum class WebChangeKind {
    UNCHANGED,
    REPRESENTATION_ONLY,
    CONTENT_CHANGED,
}

enum class WebChangeDimension {
    ACQUISITION_RECEIPT,
    PAYLOAD,
    MEDIA_TYPE,
    FORMAT,
    INGEST_STATE,
    RAW_TEXT,
    VISIBLE_TEXT,
    TRUNCATION,
    INGEST_POLICY,
}

data class WebChangeDetection(
    val resourceId: WebResourceId,
    val previousDocumentFingerprint: String,
    val currentDocumentFingerprint: String,
    val previousPayloadSha256: String,
    val currentPayloadSha256: String,
    val dimensions: List<WebChangeDimension>,
    val kind: WebChangeKind,
    val fingerprint: String,
) {
    init {
        require(previousDocumentFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(currentDocumentFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(previousPayloadSha256.matches(Regex("[0-9a-f]{64}")))
        require(currentPayloadSha256.matches(Regex("[0-9a-f]{64}")))
        require(dimensions == dimensions.distinct().sortedBy { it.name })
        when (kind) {
            WebChangeKind.UNCHANGED -> {
                require(dimensions.isEmpty())
                require(previousDocumentFingerprint == currentDocumentFingerprint)
                require(previousPayloadSha256 == currentPayloadSha256)
            }
            WebChangeKind.REPRESENTATION_ONLY -> {
                require(dimensions.isNotEmpty())
                require(previousPayloadSha256 == currentPayloadSha256)
                require(WebChangeDimension.PAYLOAD !in dimensions)
            }
            WebChangeKind.CONTENT_CHANGED -> {
                require(WebChangeDimension.PAYLOAD in dimensions)
                require(previousPayloadSha256 != currentPayloadSha256)
            }
        }
        require(
            fingerprint == detectionFingerprint(
                resourceId = resourceId,
                previousDocumentFingerprint = previousDocumentFingerprint,
                currentDocumentFingerprint = currentDocumentFingerprint,
                previousPayloadSha256 = previousPayloadSha256,
                currentPayloadSha256 = currentPayloadSha256,
                dimensions = dimensions,
                kind = kind,
            )
        )
    }

    val contentChanged: Boolean
        get() = kind == WebChangeKind.CONTENT_CHANGED

    val representationOnlyChanged: Boolean
        get() = kind == WebChangeKind.REPRESENTATION_ONLY

    val truthAuthority: Boolean
        get() = false

    val watchSchedulingAuthority: Boolean
        get() = false

    val mutationAuthority: Boolean
        get() = false

    val executionAuthority: Boolean
        get() = false
}

/**
 * B397 deterministic change detection between two B393 documents for one exact B391 resource.
 *
 * Payload hash changes are treated as remote content changes. Differences caused only by receipt,
 * media typing, ingest state/text projection/truncation or ingest policy are represented separately
 * as REPRESENTATION_ONLY. No watch scheduling, truth decision, mutation or execution is performed.
 */
class WebChangeDetector {
    fun detect(
        previous: WebIngestDocument,
        current: WebIngestDocument,
    ): WebChangeDetection {
        require(previous.resourceId == current.resourceId) {
            "Web change detection requires the same B391 resource identity"
        }

        val dimensions = buildList {
            if (
                previous.acquisitionReceiptFingerprint !=
                    current.acquisitionReceiptFingerprint
            ) add(WebChangeDimension.ACQUISITION_RECEIPT)
            if (previous.payloadSha256 != current.payloadSha256) {
                add(WebChangeDimension.PAYLOAD)
            }
            if (previous.mediaType != current.mediaType) {
                add(WebChangeDimension.MEDIA_TYPE)
            }
            if (previous.format != current.format) {
                add(WebChangeDimension.FORMAT)
            }
            if (previous.state != current.state) {
                add(WebChangeDimension.INGEST_STATE)
            }
            if (previous.rawText != current.rawText) {
                add(WebChangeDimension.RAW_TEXT)
            }
            if (previous.visibleText != current.visibleText) {
                add(WebChangeDimension.VISIBLE_TEXT)
            }
            if (previous.truncated != current.truncated) {
                add(WebChangeDimension.TRUNCATION)
            }
            if (previous.policyFingerprint != current.policyFingerprint) {
                add(WebChangeDimension.INGEST_POLICY)
            }
        }.distinct().sortedBy { it.name }

        val kind = when {
            dimensions.isEmpty() -> WebChangeKind.UNCHANGED
            WebChangeDimension.PAYLOAD in dimensions -> WebChangeKind.CONTENT_CHANGED
            else -> WebChangeKind.REPRESENTATION_ONLY
        }
        if (dimensions.isEmpty()) {
            require(previous.fingerprint == current.fingerprint) {
                "Equal B393 change dimensions require identical document fingerprints"
            }
        }

        val fingerprint = detectionFingerprint(
            resourceId = previous.resourceId,
            previousDocumentFingerprint = previous.fingerprint,
            currentDocumentFingerprint = current.fingerprint,
            previousPayloadSha256 = previous.payloadSha256,
            currentPayloadSha256 = current.payloadSha256,
            dimensions = dimensions,
            kind = kind,
        )
        return WebChangeDetection(
            resourceId = previous.resourceId,
            previousDocumentFingerprint = previous.fingerprint,
            currentDocumentFingerprint = current.fingerprint,
            previousPayloadSha256 = previous.payloadSha256,
            currentPayloadSha256 = current.payloadSha256,
            dimensions = dimensions,
            kind = kind,
            fingerprint = fingerprint,
        )
    }
}

private fun detectionFingerprint(
    resourceId: WebResourceId,
    previousDocumentFingerprint: String,
    currentDocumentFingerprint: String,
    previousPayloadSha256: String,
    currentPayloadSha256: String,
    dimensions: List<WebChangeDimension>,
    kind: WebChangeKind,
): String = changeFingerprint(
    "web-change-detection/v1",
    resourceId.value,
    previousDocumentFingerprint,
    currentDocumentFingerprint,
    previousPayloadSha256,
    currentPayloadSha256,
    kind.name,
    *dimensions.sortedBy { it.name }.map { it.name }.toTypedArray(),
)

private fun changeFingerprint(
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
