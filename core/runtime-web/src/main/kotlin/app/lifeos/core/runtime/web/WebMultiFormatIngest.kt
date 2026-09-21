package app.lifeos.core.runtime.web

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

enum class WebIngestFormat {
    HTML,
    PLAIN_TEXT,
    JSON,
    XML,
    RSS_ATOM,
    CSV,
    PDF,
    UNKNOWN,
}

enum class WebIngestState {
    INGESTED_TEXT,
    BINARY_OPAQUE,
    EMPTY,
    UNSUPPORTED,
}

data class WebIngestPolicy(
    val maxTextChars: Int = DEFAULT_MAX_TEXT_CHARS,
) {
    init {
        require(maxTextChars in 1..MAX_TEXT_CHARS)
    }

    fun fingerprint(): String = ingestFingerprint(
        "web-ingest-policy/v1",
        maxTextChars.toString(),
    )

    companion object {
        const val DEFAULT_MAX_TEXT_CHARS = 512 * 1024
        const val MAX_TEXT_CHARS = 2 * 1024 * 1024
    }
}

data class WebIngestDocument(
    val resourceId: WebResourceId,
    val acquisitionReceiptFingerprint: String,
    val payloadSha256: String,
    val payloadByteCount: Int,
    val mediaType: String?,
    val format: WebIngestFormat,
    val state: WebIngestState,
    val rawText: String?,
    val visibleText: String?,
    val truncated: Boolean,
    val policyFingerprint: String,
    val fingerprint: String,
) {
    init {
        require(acquisitionReceiptFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(payloadSha256.matches(Regex("[0-9a-f]{64}")))
        require(payloadByteCount >= 0)
        require(policyFingerprint.matches(Regex("[0-9a-f]{64}")))
        mediaType?.let { require(it == normalizeMediaType(it)) }
        when (state) {
            WebIngestState.INGESTED_TEXT -> {
                require(format in TEXT_FORMATS)
                require(rawText != null)
                require(visibleText != null)
            }
            WebIngestState.BINARY_OPAQUE -> {
                require(format == WebIngestFormat.PDF)
                require(rawText == null && visibleText == null)
                require(payloadByteCount > 0)
            }
            WebIngestState.EMPTY -> {
                require(payloadByteCount == 0)
                require(rawText == null && visibleText == null)
                require(!truncated)
            }
            WebIngestState.UNSUPPORTED -> {
                require(format == WebIngestFormat.UNKNOWN)
                require(rawText == null && visibleText == null)
                require(!truncated)
            }
        }
        require(
            fingerprint == documentFingerprint(
                resourceId = resourceId,
                acquisitionReceiptFingerprint = acquisitionReceiptFingerprint,
                payloadSha256 = payloadSha256,
                payloadByteCount = payloadByteCount,
                mediaType = mediaType,
                format = format,
                state = state,
                rawText = rawText,
                visibleText = visibleText,
                truncated = truncated,
                policyFingerprint = policyFingerprint,
            )
        )
    }

    val truthAuthority: Boolean
        get() = false

    val claimAuthority: Boolean
        get() = false

    val semanticAuthority: Boolean
        get() = false

    val executionAuthority: Boolean
        get() = false
}

/**
 * B393 deterministic multi-format ingest over an already-acquired B392 payload.
 *
 * It never performs network I/O and never treats decoded text as evidence/truth by itself. HTML,
 * text, JSON, XML/RSS/Atom and CSV are decoded as bounded text. PDF is deliberately retained as a
 * typed opaque binary at this layer; no fake PDF text extraction is attempted without a dedicated
 * verified decoder.
 */
class WebMultiFormatIngestor {
    fun ingest(
        acquisition: WebAcquisitionResult,
        policy: WebIngestPolicy = WebIngestPolicy(),
    ): WebIngestDocument {
        require(acquisition.receipt.outcome == WebAcquisitionOutcome.ACQUIRED) {
            "Web ingest requires an acquired B392 payload"
        }
        val payload = requireNotNull(acquisition.payload) {
            "Web ingest requires the exact acquired payload"
        }
        require(payload.sha256 == acquisition.receipt.payloadSha256)
        require(payload.size == acquisition.receipt.byteCount)

        val mediaType = acquisition.receipt.contentType?.let(::normalizeMediaType)
        if (payload.size == 0) {
            return document(
                acquisition = acquisition,
                policy = policy,
                mediaType = mediaType,
                format = mediaType?.let(::formatForMediaType) ?: WebIngestFormat.UNKNOWN,
                state = WebIngestState.EMPTY,
                rawText = null,
                visibleText = null,
                truncated = false,
            )
        }

        val bytes = payload.bytes()
        val format = formatFor(mediaType, bytes)
        if (format == WebIngestFormat.UNKNOWN) {
            return document(
                acquisition = acquisition,
                policy = policy,
                mediaType = mediaType,
                format = WebIngestFormat.UNKNOWN,
                state = WebIngestState.UNSUPPORTED,
                rawText = null,
                visibleText = null,
                truncated = false,
            )
        }
        if (format == WebIngestFormat.PDF) {
            return document(
                acquisition = acquisition,
                policy = policy,
                mediaType = mediaType,
                format = format,
                state = WebIngestState.BINARY_OPAQUE,
                rawText = null,
                visibleText = null,
                truncated = false,
            )
        }

        val decoded = decodeTextStrict(bytes)
        val raw = truncateChars(decoded, policy.maxTextChars)
        val visibleSource = when (format) {
            WebIngestFormat.HTML -> htmlVisibleText(decoded)
            WebIngestFormat.XML,
            WebIngestFormat.RSS_ATOM -> xmlVisibleText(decoded)
            WebIngestFormat.PLAIN_TEXT,
            WebIngestFormat.JSON,
            WebIngestFormat.CSV -> decoded
            WebIngestFormat.PDF,
            WebIngestFormat.UNKNOWN -> error("Non-text format reached text ingest")
        }
        val visible = truncateChars(visibleSource, policy.maxTextChars)

        return document(
            acquisition = acquisition,
            policy = policy,
            mediaType = mediaType,
            format = format,
            state = WebIngestState.INGESTED_TEXT,
            rawText = raw.value,
            visibleText = visible.value,
            truncated = raw.truncated || visible.truncated,
        )
    }

    private fun document(
        acquisition: WebAcquisitionResult,
        policy: WebIngestPolicy,
        mediaType: String?,
        format: WebIngestFormat,
        state: WebIngestState,
        rawText: String?,
        visibleText: String?,
        truncated: Boolean,
    ): WebIngestDocument {
        val payload = requireNotNull(acquisition.payload)
        val policyFingerprint = policy.fingerprint()
        val fingerprint = documentFingerprint(
            resourceId = acquisition.finalResource.id,
            acquisitionReceiptFingerprint = acquisition.receipt.fingerprint,
            payloadSha256 = payload.sha256,
            payloadByteCount = payload.size,
            mediaType = mediaType,
            format = format,
            state = state,
            rawText = rawText,
            visibleText = visibleText,
            truncated = truncated,
            policyFingerprint = policyFingerprint,
        )
        return WebIngestDocument(
            resourceId = acquisition.finalResource.id,
            acquisitionReceiptFingerprint = acquisition.receipt.fingerprint,
            payloadSha256 = payload.sha256,
            payloadByteCount = payload.size,
            mediaType = mediaType,
            format = format,
            state = state,
            rawText = rawText,
            visibleText = visibleText,
            truncated = truncated,
            policyFingerprint = policyFingerprint,
            fingerprint = fingerprint,
        )
    }
}

private data class TruncatedText(
    val value: String,
    val truncated: Boolean,
)

private fun truncateChars(
    value: String,
    maxChars: Int,
): TruncatedText {
    if (value.length <= maxChars) return TruncatedText(value, false)
    var end = maxChars
    if (end > 0 && Character.isHighSurrogate(value[end - 1])) {
        end -= 1
    }
    return TruncatedText(value.substring(0, end), true)
}

private fun formatFor(
    mediaType: String?,
    bytes: ByteArray,
): WebIngestFormat {
    val direct = mediaType?.let(::formatForMediaType) ?: WebIngestFormat.UNKNOWN
    if (direct != WebIngestFormat.XML) return direct
    val prefix = runCatching {
        decodeTextStrict(bytes.copyOfRange(0, minOf(bytes.size, 4096)))
    }.getOrDefault("")
        .trimStart()
        .lowercase()
    return if (
        prefix.startsWith("<rss") ||
        prefix.startsWith("<feed") ||
        prefix.startsWith("<?xml") && (
            "<rss" in prefix ||
                "<feed" in prefix
            )
    ) {
        WebIngestFormat.RSS_ATOM
    } else {
        WebIngestFormat.XML
    }
}

private fun formatForMediaType(mediaType: String): WebIngestFormat = when (mediaType) {
    "text/html",
    "application/xhtml+xml" -> WebIngestFormat.HTML

    "text/plain" -> WebIngestFormat.PLAIN_TEXT

    "application/json",
    "application/ld+json",
    "text/json" -> WebIngestFormat.JSON

    "application/xml",
    "text/xml" -> WebIngestFormat.XML

    "application/rss+xml",
    "application/atom+xml" -> WebIngestFormat.RSS_ATOM

    "text/csv",
    "application/csv" -> WebIngestFormat.CSV

    "application/pdf" -> WebIngestFormat.PDF
    else -> WebIngestFormat.UNKNOWN
}

private fun normalizeMediaType(value: String): String {
    val normalized = value.substringBefore(';').trim().lowercase()
    val parts = normalized.split('/')
    require(parts.size == 2 && parts.none { it.isBlank() }) {
        "Invalid Web ingest media type"
    }
    return normalized
}

private fun decodeTextStrict(bytes: ByteArray): String {
    if (bytes.isEmpty()) return ""
    return when {
        bytes.hasPrefix(UTF8_BOM) ->
            decodeWithCharset(bytes.copyOfRange(UTF8_BOM.size, bytes.size), StandardCharsets.UTF_8)

        bytes.hasPrefix(UTF16_LE_BOM) ->
            decodeWithCharset(bytes.copyOfRange(UTF16_LE_BOM.size, bytes.size), StandardCharsets.UTF_16LE)

        bytes.hasPrefix(UTF16_BE_BOM) ->
            decodeWithCharset(bytes.copyOfRange(UTF16_BE_BOM.size, bytes.size), StandardCharsets.UTF_16BE)

        else -> decodeWithCharset(bytes, StandardCharsets.UTF_8)
    }
}

private fun decodeWithCharset(
    bytes: ByteArray,
    charset: Charset,
): String = charset.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT)
    .onUnmappableCharacter(CodingErrorAction.REPORT)
    .decode(ByteBuffer.wrap(bytes))
    .toString()

private fun ByteArray.hasPrefix(prefix: ByteArray): Boolean =
    size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

private fun htmlVisibleText(html: String): String {
    val withoutComments = HTML_COMMENT.replace(html, " ")
    val withoutScriptStyle = HTML_SCRIPT_STYLE.replace(withoutComments, " ")
    val withBreaks = HTML_BLOCK_BREAK.replace(withoutScriptStyle, "
")
    val withoutTags = HTML_TAG.replace(withBreaks, " ")
    return normalizeVisibleText(decodeEntities(withoutTags))
}

private fun xmlVisibleText(xml: String): String {
    val out = StringBuilder(xml.length.coerceAtMost(256 * 1024))
    var insideTag = false
    for (char in xml) {
        when {
            char == '<' -> {
                insideTag = true
                out.append(' ')
            }
            char == '>' -> {
                insideTag = false
                out.append(' ')
            }
            insideTag -> Unit
            else -> out.append(char)
        }
    }
    return normalizeVisibleText(decodeEntities(out.toString()))
}

private fun decodeEntities(value: String): String {
    var decoded = value
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", """)
        .replace("&apos;", "'")
        .replace("&nbsp;", " ")
    decoded = NUMERIC_ENTITY.replace(decoded) { match ->
        val raw = match.groupValues[1]
        val codePoint = if (raw.startsWith("x", ignoreCase = true)) {
            raw.substring(1).toIntOrNull(16)
        } else {
            raw.toIntOrNull()
        }
        if (codePoint == null || !Character.isValidCodePoint(codePoint)) {
            match.value
        } else {
            String(Character.toChars(codePoint))
        }
    }
    return decoded
}

private fun normalizeVisibleText(value: String): String =
    value.replace(Regex("[\t\x0B\f\r ]+"), " ")
        .replace(Regex(" *\n+ *"), "
")
        .replace(Regex("\n{3,}"), "

")
        .trim()

private fun documentFingerprint(
    resourceId: WebResourceId,
    acquisitionReceiptFingerprint: String,
    payloadSha256: String,
    payloadByteCount: Int,
    mediaType: String?,
    format: WebIngestFormat,
    state: WebIngestState,
    rawText: String?,
    visibleText: String?,
    truncated: Boolean,
    policyFingerprint: String,
): String = ingestFingerprint(
    "web-ingest-document/v1",
    resourceId.value,
    acquisitionReceiptFingerprint,
    payloadSha256,
    payloadByteCount.toString(),
    mediaType.orEmpty(),
    format.name,
    state.name,
    rawText?.let(::sha256Text).orEmpty(),
    visibleText?.let(::sha256Text).orEmpty(),
    truncated.toString(),
    policyFingerprint,
)

private fun ingestFingerprint(
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

private fun sha256Text(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

private val TEXT_FORMATS = setOf(
    WebIngestFormat.HTML,
    WebIngestFormat.PLAIN_TEXT,
    WebIngestFormat.JSON,
    WebIngestFormat.XML,
    WebIngestFormat.RSS_ATOM,
    WebIngestFormat.CSV,
)
private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
private val UTF16_LE_BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
private val UTF16_BE_BOM = byteArrayOf(0xFE.toByte(), 0xFF.toByte())
private val HTML_COMMENT = Regex("(?s)<!--.*?-->")
private val HTML_SCRIPT_STYLE = Regex(
    "(?is)<(script|style)\\b[^>]*>.*?</\\1\\s*>"
)
private val HTML_BLOCK_BREAK = Regex(
    "(?i)</?(p|div|section|article|header|footer|main|nav|aside|li|ul|ol|h[1-6]|br|tr|table)\\b[^>]*>"
)
private val HTML_TAG = Regex("(?s)<[^>]+>")
private val NUMERIC_ENTITY = Regex("&#(x[0-9a-fA-F]+|[0-9]+);")
