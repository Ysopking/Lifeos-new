package app.lifeos.core.runtime.web

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

enum class WebClaimKind {
    SENTENCE,
    STRUCTURED_RECORD,
}

data class WebClaimExtractionPolicy(
    val maxClaims: Int = 128,
    val minClaimChars: Int = 12,
    val maxClaimChars: Int = 800,
) {
    init {
        require(maxClaims in 1..4_096)
        require(minClaimChars in 1..4_096)
        require(maxClaimChars in minClaimChars..16_384)
    }

    fun fingerprint(): String = claimFingerprint(
        "web-claim-extraction-policy/v1",
        maxClaims.toString(),
        minClaimChars.toString(),
        maxClaimChars.toString(),
    )
}

data class WebClaimCandidate(
    val id: String,
    val resourceId: WebResourceId,
    val sourceDocumentFingerprint: String,
    val sourceTextFingerprint: String,
    val kind: WebClaimKind,
    val startChar: Int,
    val endCharExclusive: Int,
    val text: String,
    val truncated: Boolean,
    val fingerprint: String,
) {
    init {
        require(id.startsWith(ID_PREFIX))
        require(sourceDocumentFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(sourceTextFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(startChar >= 0)
        require(endCharExclusive > startChar)
        require(text.isNotBlank())
        require(
            fingerprint == candidateFingerprint(
                resourceId = resourceId,
                sourceDocumentFingerprint = sourceDocumentFingerprint,
                sourceTextFingerprint = sourceTextFingerprint,
                kind = kind,
                startChar = startChar,
                endCharExclusive = endCharExclusive,
                text = text,
                truncated = truncated,
            )
        )
        require(id == ID_PREFIX + fingerprint)
    }

    val truthAuthority: Boolean
        get() = false

    val evidenceAuthority: Boolean
        get() = false

    val citationAuthority: Boolean
        get() = false

    val executionAuthority: Boolean
        get() = false

    companion object {
        const val ID_PREFIX = "web-claim:"
    }
}

data class WebClaimExtractionResult(
    val resourceId: WebResourceId,
    val sourceDocumentFingerprint: String,
    val sourceTextFingerprint: String?,
    val candidates: List<WebClaimCandidate>,
    val truncated: Boolean,
    val policyFingerprint: String,
    val fingerprint: String,
) {
    init {
        require(sourceDocumentFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(policyFingerprint.matches(Regex("[0-9a-f]{64}")))
        sourceTextFingerprint?.let {
            require(it.matches(Regex("[0-9a-f]{64}")))
        }
        require(candidates == candidates.distinctBy { it.id }
            .sortedWith(candidateOrder()))
        require(candidates.all { it.resourceId == resourceId })
        require(candidates.all {
            it.sourceDocumentFingerprint == sourceDocumentFingerprint
        })
        require(
            sourceTextFingerprint == null ||
                candidates.all { it.sourceTextFingerprint == sourceTextFingerprint }
        )
        require(
            fingerprint == resultFingerprint(
                resourceId = resourceId,
                sourceDocumentFingerprint = sourceDocumentFingerprint,
                sourceTextFingerprint = sourceTextFingerprint,
                candidates = candidates,
                truncated = truncated,
                policyFingerprint = policyFingerprint,
            )
        )
    }

    val truthAuthority: Boolean
        get() = false

    val evidenceAuthority: Boolean
        get() = false
}

/**
 * B394 deterministic candidate extraction from B393 ingest text.
 *
 * A candidate is only a bounded source span. This engine does not decide that a span is true,
 * evidence, supported, citable, or semantically equivalent to any other claim. B395+ may attach
 * citation/evidence relationships after independent provenance checks.
 */
class WebClaimExtractor {
    fun extract(
        document: WebIngestDocument,
        policy: WebClaimExtractionPolicy = WebClaimExtractionPolicy(),
    ): WebClaimExtractionResult {
        val sourceText = if (document.state == WebIngestState.INGESTED_TEXT) {
            requireNotNull(
                when (document.format) {
                    WebIngestFormat.JSON,
                    WebIngestFormat.CSV -> document.rawText
                    else -> document.visibleText
                }
            )
        } else {
            null
        }
        if (sourceText == null || sourceText.isBlank()) {
            return result(
                document = document,
                sourceTextFingerprint = sourceText?.let(::textFingerprint),
                candidates = emptyList(),
                truncated = false,
                policy = policy,
            )
        }

        val sourceFingerprint = textFingerprint(sourceText)
        val rawSpans = when (document.format) {
            WebIngestFormat.JSON,
            WebIngestFormat.CSV -> structuredSpans(sourceText)
            else -> sentenceSpans(sourceText)
        }
        val eligible = rawSpans.mapNotNull { span ->
            canonicalCandidateSpan(
                sourceText = sourceText,
                start = span.first,
                endExclusive = span.last + 1,
                minChars = policy.minClaimChars,
                maxChars = policy.maxClaimChars,
            )
        }
        val selected = eligible.take(policy.maxClaims)
        val candidates = selected.map { span ->
            createCandidate(
                document = document,
                sourceTextFingerprint = sourceFingerprint,
                span = span,
            )
        }.sortedWith(candidateOrder())

        return result(
            document = document,
            sourceTextFingerprint = sourceFingerprint,
            candidates = candidates,
            truncated = eligible.size > selected.size,
            policy = policy,
        )
    }

    private fun result(
        document: WebIngestDocument,
        sourceTextFingerprint: String?,
        candidates: List<WebClaimCandidate>,
        truncated: Boolean,
        policy: WebClaimExtractionPolicy,
    ): WebClaimExtractionResult {
        val canonical = candidates.distinctBy { it.id }.sortedWith(candidateOrder())
        val policyFingerprint = policy.fingerprint()
        val fingerprint = resultFingerprint(
            resourceId = document.resourceId,
            sourceDocumentFingerprint = document.fingerprint,
            sourceTextFingerprint = sourceTextFingerprint,
            candidates = canonical,
            truncated = truncated,
            policyFingerprint = policyFingerprint,
        )
        return WebClaimExtractionResult(
            resourceId = document.resourceId,
            sourceDocumentFingerprint = document.fingerprint,
            sourceTextFingerprint = sourceTextFingerprint,
            candidates = canonical,
            truncated = truncated,
            policyFingerprint = policyFingerprint,
            fingerprint = fingerprint,
        )
    }

    private fun createCandidate(
        document: WebIngestDocument,
        sourceTextFingerprint: String,
        span: CandidateSpan,
    ): WebClaimCandidate {
        val kind = when (document.format) {
            WebIngestFormat.JSON,
            WebIngestFormat.CSV -> WebClaimKind.STRUCTURED_RECORD
            else -> WebClaimKind.SENTENCE
        }
        val fingerprint = candidateFingerprint(
            resourceId = document.resourceId,
            sourceDocumentFingerprint = document.fingerprint,
            sourceTextFingerprint = sourceTextFingerprint,
            kind = kind,
            startChar = span.start,
            endCharExclusive = span.endExclusive,
            text = span.text,
            truncated = span.truncated,
        )
        return WebClaimCandidate(
            id = WebClaimCandidate.ID_PREFIX + fingerprint,
            resourceId = document.resourceId,
            sourceDocumentFingerprint = document.fingerprint,
            sourceTextFingerprint = sourceTextFingerprint,
            kind = kind,
            startChar = span.start,
            endCharExclusive = span.endExclusive,
            text = span.text,
            truncated = span.truncated,
            fingerprint = fingerprint,
        )
    }
}

private data class CandidateSpan(
    val start: Int,
    val endExclusive: Int,
    val text: String,
    val truncated: Boolean,
)

private fun structuredSpans(source: String): List<IntRange> {
    val spans = mutableListOf<IntRange>()
    var lineStart = 0
    for (index in source.indices) {
        if (source[index] == '
') {
            if (index > lineStart) spans += lineStart until index
            lineStart = index + 1
        }
    }
    if (lineStart < source.length) spans += lineStart until source.length
    if (spans.isEmpty() && source.isNotBlank()) spans += source.indices
    return spans
}

private fun sentenceSpans(source: String): List<IntRange> {
    val spans = mutableListOf<IntRange>()
    var start = 0
    var index = 0
    while (index < source.length) {
        val char = source[index]
        val newline = char == '
'
        val punctuationBoundary =
            char in SENTENCE_TERMINATORS &&
                (index + 1 == source.length || source[index + 1].isWhitespace())
        if (newline || punctuationBoundary) {
            val endExclusive = if (newline) index else index + 1
            if (endExclusive > start) spans += start until endExclusive
            start = index + 1
        }
        index += 1
    }
    if (start < source.length) spans += start until source.length
    return spans
}

private fun canonicalCandidateSpan(
    sourceText: String,
    start: Int,
    endExclusive: Int,
    minChars: Int,
    maxChars: Int,
): CandidateSpan? {
    var from = start.coerceIn(0, sourceText.length)
    var to = endExclusive.coerceIn(from, sourceText.length)
    while (from < to && sourceText[from].isWhitespace()) from += 1
    while (to > from && sourceText[to - 1].isWhitespace()) to -= 1
    if (to - from < minChars) return null

    var truncated = false
    if (to - from > maxChars) {
        to = from + maxChars
        if (to > from && Character.isHighSurrogate(sourceText[to - 1])) {
            to -= 1
        }
        truncated = true
    }
    if (to <= from) return null
    val text = sourceText.substring(from, to)
    if (text.isBlank() || text.length < minChars) return null
    return CandidateSpan(
        start = from,
        endExclusive = to,
        text = text,
        truncated = truncated,
    )
}

private fun candidateOrder(): Comparator<WebClaimCandidate> =
    compareBy<WebClaimCandidate> { it.startChar }
        .thenBy { it.endCharExclusive }
        .thenBy { it.kind.name }
        .thenBy { it.id }

private fun candidateFingerprint(
    resourceId: WebResourceId,
    sourceDocumentFingerprint: String,
    sourceTextFingerprint: String,
    kind: WebClaimKind,
    startChar: Int,
    endCharExclusive: Int,
    text: String,
    truncated: Boolean,
): String = claimFingerprint(
    "web-claim-candidate/v1",
    resourceId.value,
    sourceDocumentFingerprint,
    sourceTextFingerprint,
    kind.name,
    startChar.toString(),
    endCharExclusive.toString(),
    textFingerprint(text),
    truncated.toString(),
)

private fun resultFingerprint(
    resourceId: WebResourceId,
    sourceDocumentFingerprint: String,
    sourceTextFingerprint: String?,
    candidates: List<WebClaimCandidate>,
    truncated: Boolean,
    policyFingerprint: String,
): String = claimFingerprint(
    "web-claim-extraction-result/v1",
    resourceId.value,
    sourceDocumentFingerprint,
    sourceTextFingerprint.orEmpty(),
    truncated.toString(),
    policyFingerprint,
    *candidates.map { it.id }.toTypedArray(),
)

private fun textFingerprint(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

private fun claimFingerprint(
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

private val SENTENCE_TERMINATORS = setOf('.', '!', '?')
