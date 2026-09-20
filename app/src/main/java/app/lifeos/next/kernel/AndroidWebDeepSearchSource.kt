package app.lifeos.next.kernel

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.language.SemanticSearchTerms
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonPhase
import app.lifeos.core.model.Provenance
import app.lifeos.core.runtime.deepsearch.DeepSearchBranch
import app.lifeos.core.runtime.deepsearch.DeepSearchClaimCompatibility
import app.lifeos.core.runtime.deepsearch.DeepSearchEvidenceDraft
import app.lifeos.core.runtime.deepsearch.DeepSearchFindingDraft
import app.lifeos.core.runtime.deepsearch.DeepSearchPermissionState
import app.lifeos.core.runtime.deepsearch.DeepSearchRequest
import app.lifeos.core.runtime.deepsearch.DeepSearchSource
import app.lifeos.core.runtime.deepsearch.DeepSearchSourceDescriptor
import app.lifeos.core.runtime.deepsearch.DeepSearchSourceKind
import app.lifeos.core.runtime.policy.OwnerEffectExposureResult
import app.lifeos.core.runtime.policy.OwnerPolicyEffectGate
import app.lifeos.core.runtime.policy.OwnerPolicyLedger
import java.io.InputStream
import java.net.InetAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class WebSearchHit(
    val title: String,
    val url: String,
    val snippet: String,
    val confidence: Double = 0.50,
) {
    init {
        require(title.isNotBlank())
        require(url.isNotBlank())
        require(confidence in 0.0..1.0)
    }
}

internal data class WebDocumentHit(
    val title: String,
    val url: String,
    val passage: String,
) {
    init {
        require(url.isNotBlank())
        require(passage.isNotBlank())
    }
}

internal fun interface WebSearchTransport {
    suspend fun search(query: String, maxBytes: Int): List<WebSearchHit>
}

internal fun interface WebDocumentTransport {
    suspend fun fetch(url: String, query: String, maxBytes: Int): WebDocumentHit?
}

internal class DuckDuckGoHtmlSearchTransport : WebSearchTransport {
    override suspend fun search(query: String, maxBytes: Int): List<WebSearchHit> = withContext(Dispatchers.IO) {
        require(query.isNotBlank())
        require(maxBytes in 1..MAX_RESPONSE_BYTES)
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        val url = URI("https://html.duckduckgo.com/html/?q=$encoded").toURL()
        val connection = (url.openConnection() as HttpsURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            instanceFollowRedirects = false
            setRequestProperty("Accept", "text/html;charset=UTF-8")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            val code = connection.responseCode
            require(code == HttpsURLConnection.HTTP_OK) { "web-search-http-$code" }
            val body = connection.inputStream.use { input -> readBoundedUtf8(input, maxBytes) }
            WebSearchHtmlParser.parse(body, query)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 3_000
        const val READ_TIMEOUT_MILLIS = 4_000
        const val MAX_RESPONSE_BYTES = 512 * 1024
        const val USER_AGENT = "LIFEOS-Private/1.0 WebDeepSearch"
    }
}

internal class BoundedHttpsDocumentTransport : WebDocumentTransport {
    override suspend fun fetch(url: String, query: String, maxBytes: Int): WebDocumentHit? =
        withContext(Dispatchers.IO) {
            require(maxBytes in 1..MAX_DOCUMENT_BYTES)
            var current = URI(url)
            repeat(MAX_REDIRECTS + 1) { redirectIndex ->
                validatePublicHttps(current)
                val connection = (current.toURL().openConnection() as HttpsURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = CONNECT_TIMEOUT_MILLIS
                    readTimeout = READ_TIMEOUT_MILLIS
                    instanceFollowRedirects = false
                    setRequestProperty("Accept", "text/html,text/plain,application/xhtml+xml")
                    setRequestProperty("User-Agent", USER_AGENT)
                }
                try {
                    val code = connection.responseCode
                    if (code in REDIRECT_CODES) {
                        if (redirectIndex >= MAX_REDIRECTS) return@withContext null
                        val location = connection.getHeaderField("Location") ?: return@withContext null
                        current = current.resolve(location)
                        return@repeat
                    }
                    if (code != HttpsURLConnection.HTTP_OK) return@withContext null
                    val type = connection.contentType.orEmpty().substringBefore(';').trim().lowercase()
                    if (type !in ALLOWED_CONTENT_TYPES) return@withContext null
                    val body = connection.inputStream.use { readBoundedUtf8(it, maxBytes) }
                    val extracted = WebDocumentTextExtractor.extract(body, query)
                    if (extracted.passage.isBlank()) return@withContext null
                    return@withContext WebDocumentHit(
                        title = extracted.title,
                        url = current.normalize().toASCIIString(),
                        passage = extracted.passage,
                    )
                } finally {
                    connection.disconnect()
                }
            }
            null
        }

    private fun validatePublicHttps(uri: URI) {
        require(uri.scheme.equals("https", ignoreCase = true)) { "web-document-non-https" }
        val host = uri.host?.takeIf { it.isNotBlank() } ?: error("web-document-host-missing")
        val addresses = InetAddress.getAllByName(host)
        require(addresses.isNotEmpty()) { "web-document-host-unresolved" }
        require(addresses.none(::isPrivateAddress)) { "web-document-private-address-blocked" }
    }

    private fun isPrivateAddress(address: InetAddress): Boolean {
        if (
            address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) return true
        val bytes = address.address
        return bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0xfc
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 3_000
        const val READ_TIMEOUT_MILLIS = 4_000
        const val MAX_DOCUMENT_BYTES = 192 * 1024
        const val MAX_REDIRECTS = 3
        const val USER_AGENT = "LIFEOS-Private/1.0 WebDeepSearch"
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        val ALLOWED_CONTENT_TYPES = setOf("text/html", "text/plain", "application/xhtml+xml")
    }
}

internal object WebSearchHtmlParser {
    fun parse(html: String, query: String, limit: Int = 8): List<WebSearchHit> {
        require(limit in 1..32)
        val queryTerms = SemanticSearchTerms.expandedTokens(query)
        val anchors = RESULT_ANCHOR.findAll(html).toList()
        return anchors.asSequence()
            .mapIndexedNotNull { index, match ->
                val title = clean(match.groupValues[2])
                val target = canonicalHttpsTarget(match.groupValues[1]) ?: return@mapIndexedNotNull null
                if (title.isBlank()) return@mapIndexedNotNull null
                val nextStart = anchors.getOrNull(index + 1)?.range?.first ?: html.length
                val snippetEnd = minOf(nextStart, match.range.last + 1 + MAX_SNIPPET_SCAN_CHARS, html.length)
                val tail = html.substring(match.range.last + 1, snippetEnd)
                val snippet = SNIPPET.find(tail)?.groupValues?.get(1)?.let(::clean).orEmpty()
                val candidateTerms = SemanticSearchTerms.expandedTokens("$title $snippet")
                val overlap = if (queryTerms.isEmpty()) 0.0 else {
                    queryTerms.count(candidateTerms::contains).toDouble() / queryTerms.size.toDouble()
                }
                if (queryTerms.isNotEmpty() && overlap <= 0.0) return@mapIndexedNotNull null
                val titleTerms = SemanticSearchTerms.expandedTokens(title)
                val titleCoverage = if (queryTerms.isEmpty()) 0.0 else {
                    queryTerms.count(titleTerms::contains).toDouble() / queryTerms.size.toDouble()
                }
                val confidence = (0.42 + overlap * 0.30 + titleCoverage * 0.16).coerceIn(0.0, 0.88)
                WebSearchHit(
                    title = title.take(MAX_TITLE_CHARS),
                    url = target,
                    snippet = snippet.take(MAX_SNIPPET_CHARS),
                    confidence = confidence,
                )
            }
            .distinctBy { it.url }
            .sortedWith(compareByDescending<WebSearchHit> { it.confidence }.thenBy { it.url })
            .take(limit)
            .toList()
    }

    private fun canonicalHttpsTarget(raw: String): String? {
        val decoded = decodeEntities(raw).trim()
        val absolute = if (decoded.startsWith("//")) "https:$decoded" else decoded
        val uri = runCatching { URI(absolute) }.getOrNull() ?: return null
        val redirected = if (uri.host?.endsWith("duckduckgo.com") == true) {
            uri.rawQuery.orEmpty().split('&')
                .firstOrNull { it.startsWith("uddg=") }
                ?.substringAfter('=')
                ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }
        } else null
        val target = runCatching { URI(redirected ?: absolute) }.getOrNull() ?: return null
        if (!target.scheme.equals("https", ignoreCase = true) || target.host.isNullOrBlank()) return null
        return target.normalize().toASCIIString()
    }

    private fun clean(value: String): String = decodeEntities(value.replace(TAGS, " "))
        .replace(WHITESPACE, " ")
        .trim()

    private fun decodeEntities(value: String): String = value
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")

    private val RESULT_ANCHOR = Regex(
        """(?is)<a[^>]+class=["'][^"']*result__a[^"']*["'][^>]+href=["']([^"']+)["'][^>]*>(.*?)</a>"""
    )
    private val SNIPPET = Regex(
        """(?is)<[^>]+class=["'][^"']*result__snippet[^"']*["'][^>]*>(.*?)</[^>]+>"""
    )
    private val TAGS = Regex("(?is)<[^>]+>")
    private val WHITESPACE = Regex("\\s+")
    private const val MAX_SNIPPET_SCAN_CHARS = 2_048
    private const val MAX_TITLE_CHARS = 220
    private const val MAX_SNIPPET_CHARS = 520
}

internal object WebDocumentTextExtractor {
    data class Extracted(val title: String, val passage: String)

    fun extract(html: String, query: String): Extracted {
        val title = TITLE.find(html)?.groupValues?.get(1)?.let(::clean).orEmpty().take(MAX_TITLE_CHARS)
        val body = html
            .replace(COMMENTS, " ")
            .replace(SCRIPT_STYLE, " ")
            .replace(BLOCK_BREAKS, "\n")
            .replace(TAGS, " ")
        val decoded = decodeEntities(body)
        val passages = decoded.lineSequence()
            .map { it.replace(WHITESPACE, " ").trim() }
            .filter { it.length >= MIN_PASSAGE_CHARS }
            .map { it.take(MAX_PASSAGE_CHARS) }
            .distinct()
            .take(MAX_PASSAGES)
            .toList()
        val queryTerms = SemanticSearchTerms.expandedTokens(query)
        val ranked = passages.sortedWith(
            compareByDescending<String> { passage ->
                val terms = SemanticSearchTerms.expandedTokens(passage)
                if (queryTerms.isEmpty()) 0.0
                else queryTerms.count(terms::contains).toDouble() / queryTerms.size.toDouble()
            }.thenByDescending { it.length }
        )
        return Extracted(
            title = title,
            passage = ranked.take(3).joinToString(" ").take(MAX_SELECTED_CHARS).trim(),
        )
    }

    private fun clean(value: String): String = decodeEntities(value.replace(TAGS, " "))
        .replace(WHITESPACE, " ")
        .trim()

    private fun decodeEntities(value: String): String = value
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&nbsp;", " ")

    private val TITLE = Regex("(?is)<title[^>]*>(.*?)</title>")
    private val COMMENTS = Regex("(?is)<!--.*?-->")
    private val SCRIPT_STYLE = Regex("(?is)<(script|style|noscript|svg|canvas)[^>]*>.*?</\\1>")
    private val BLOCK_BREAKS = Regex("(?is)</?(?:p|div|article|main|section|li|h[1-6]|br)[^>]*>")
    private val TAGS = Regex("(?is)<[^>]+>")
    private val WHITESPACE = Regex("\\s+")
    private const val MIN_PASSAGE_CHARS = 40
    private const val MAX_PASSAGE_CHARS = 1_400
    private const val MAX_SELECTED_CHARS = 2_800
    private const val MAX_PASSAGES = 96
    private const val MAX_TITLE_CHARS = 220
}

private fun readBoundedUtf8(input: InputStream, maxBytes: Int): String {
    val buffer = ByteArray(maxBytes)
    var offset = 0
    while (offset < maxBytes) {
        val read = input.read(buffer, offset, maxBytes - offset)
        if (read <= 0) break
        offset += read
    }
    return String(buffer, 0, offset, StandardCharsets.UTF_8)
}

internal class AndroidWebDeepSearchSource(
    ownerPolicy: OwnerPolicyLedger,
    private val transport: WebSearchTransport,
    private val loadPhoton: suspend (PhotonId) -> Photon?,
    private val persistPhoton: suspend (Photon) -> Photon,
    private val now: () -> Instant = Instant::now,
    private val documentTransport: WebDocumentTransport = BoundedHttpsDocumentTransport(),
) : DeepSearchSource {
    private val effects = OwnerPolicyEffectGate(ownerPolicy)
    private val claimCompatibility = DeepSearchClaimCompatibility()

    override val descriptor = DeepSearchSourceDescriptor(
        sourceId = WebDeepSearchOwnerPolicy.SOURCE_ID,
        kind = DeepSearchSourceKind.EXTERNAL,
        capabilityId = WebDeepSearchOwnerPolicy.CAPABILITY_ID,
        permissionState = DeepSearchPermissionState.UNKNOWN,
        reliability = 0.80,
        workUnitsPerExpansion = WEB_WORK_UNITS,
    )

    override suspend fun expand(
        request: DeepSearchRequest,
        branch: DeepSearchBranch,
    ): List<DeepSearchFindingDraft> {
        val query = queryFor(request, branch)
        return when (val exposure = effects.expose(WebDeepSearchOwnerPolicy.request()) {
            searchAndFetch(
                query = query,
                depth = branch.depth,
                referenceStatement = branch.hypothesis.statement.takeIf { branch.depth > 0 },
            )
        }) {
            is OwnerEffectExposureResult.Exposed -> exposure.value
            is OwnerEffectExposureResult.Blocked -> error("deepsearch-web-owner-policy-blocked")
        }
    }

    private suspend fun searchAndFetch(
        query: String,
        depth: Int,
        referenceStatement: String?,
    ): List<DeepSearchFindingDraft> {
        val hits = transport.search(query, SEARCH_RESPONSE_BYTES)
        return hits.take(MAX_RESULTS).mapIndexed { index, hit ->
            val document = if (index < MAX_FETCHED_DOCUMENTS) {
                try {
                    documentTransport.fetch(hit.url, query, DOCUMENT_RESPONSE_BYTES)
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }
            finding(
                hit = hit,
                document = document,
                query = query,
                depth = depth,
                referenceStatement = referenceStatement,
            )
        }
    }

    private suspend fun finding(
        hit: WebSearchHit,
        document: WebDocumentHit?,
        query: String,
        depth: Int,
        referenceStatement: String?,
    ): DeepSearchFindingDraft {
        val sourceUrl = document?.url ?: hit.url
        val evidenceText = document?.passage?.takeIf { it.isNotBlank() } ?: hit.snippet
        val title = document?.title?.takeIf { it.isNotBlank() } ?: hit.title
        val statement = listOf(title, evidenceText)
            .filter { it.isNotBlank() }
            .joinToString(" — ")
            .take(MAX_STATEMENT_CHARS)
        val confidence = evidenceConfidence(query, title, evidenceText, document != null, hit.confidence)
        val contradiction = referenceStatement
            ?.let { reference -> claimCompatibility.evaluate(reference, statement).contradiction }
            ?: false
        val evidencePhoton = evidencePhoton(
            title = title,
            url = sourceUrl,
            passage = evidenceText,
            confidence = confidence,
            query = query,
            depth = depth,
            fullDocument = document != null,
        )
        return DeepSearchFindingDraft(
            statement = statement,
            semanticTerms = SemanticSearchTerms.expandedTokens(statement),
            confidence = confidence,
            evidence = listOf(
                DeepSearchEvidenceDraft(
                    statement = statement,
                    confidence = confidence,
                    sourcePhotonId = evidencePhoton.id,
                    sourcePhotonRevision = evidencePhoton.revision,
                    contradiction = contradiction,
                )
            ),
        )
    }

    private fun evidenceConfidence(
        query: String,
        title: String,
        passage: String,
        fullDocument: Boolean,
        searchConfidence: Double,
    ): Double {
        val queryTerms = SemanticSearchTerms.expandedTokens(query)
        val passageTerms = SemanticSearchTerms.expandedTokens(passage)
        val titleTerms = SemanticSearchTerms.expandedTokens(title)
        val passageCoverage = if (queryTerms.isEmpty()) 0.0
        else queryTerms.count(passageTerms::contains).toDouble() / queryTerms.size.toDouble()
        val titleCoverage = if (queryTerms.isEmpty()) 0.0
        else queryTerms.count(titleTerms::contains).toDouble() / queryTerms.size.toDouble()
        return (
            0.30 +
                searchConfidence * 0.20 +
                passageCoverage * 0.28 +
                titleCoverage * 0.12 +
                if (fullDocument) 0.10 else 0.0
            ).coerceIn(0.0, 0.94)
    }

    private fun queryFor(request: DeepSearchRequest, branch: DeepSearchBranch): String {
        if (branch.depth == 0) return request.query
        // Never project a local-memory hypothesis into a public Web query. Web refinement may only
        // build on the owner's current request or on evidence that already came from this Web source.
        if (branch.sourceId != descriptor.sourceId) return request.query
        val rootTerms = SemanticSearchTerms.tokens(request.query)
        val refinements = (
            branch.hypothesis.semanticTerms.flatMap(SemanticSearchTerms::tokens) +
                SemanticSearchTerms.tokens(branch.hypothesis.statement)
            )
            .filterNot { it in rootTerms }
            .distinct()
            .take(MAX_REFINEMENT_TERMS)
        return (rootTerms.take(MAX_ROOT_TERMS) + refinements)
            .distinct()
            .joinToString(" ")
            .ifBlank { request.query }
    }

    private suspend fun evidencePhoton(
        title: String,
        url: String,
        passage: String,
        confidence: Double,
        query: String,
        depth: Int,
        fullDocument: Boolean,
    ): Photon {
        val normalizedContent = buildString {
            appendLine(title.trim())
            if (passage.isNotBlank()) appendLine(passage.trim())
            append("Source: ").append(url)
        }.trim()
        val id = PhotonId(
            "web-evidence_" + StableFieldIds.fingerprint(
                "deepsearch-web-evidence/v2",
                descriptor.sourceId,
                url,
                normalizedContent,
            )
        )
        loadPhoton(id)?.let { existing ->
            require(existing.content == normalizedContent) { "web-evidence-photon-id-collision" }
            return existing
        }
        val photon = Photon(
            id = id,
            content = normalizedContent,
            mimeType = "text/plain",
            phase = PhotonPhase.ACTIVE,
            semanticMass = if (fullDocument) 1.15 else 0.90,
            energy = 1.0,
            confidence = confidence,
            provenance = Provenance(
                source = url,
                actor = descriptor.sourceId,
                createdAt = now(),
            ),
            tags = buildSet {
                add("web-evidence")
                add("web-evidence:v2")
                add("deepsearch-evidence")
                add("deepsearch-source:${descriptor.sourceId}")
                add("web-url:${StableFieldIds.fingerprint("web-url/v1", url)}")
                add("web-query:${StableFieldIds.fingerprint("web-query/v1", query)}")
                add("web-depth:$depth")
                add(if (fullDocument) "web-content:fetched" else "web-content:snippet-only")
            },
        )
        return persistPhoton(photon)
    }

    private companion object {
        const val WEB_WORK_UNITS = 6
        const val SEARCH_RESPONSE_BYTES = 96 * 1024
        const val DOCUMENT_RESPONSE_BYTES = 96 * 1024
        const val MAX_RESULTS = 6
        const val MAX_FETCHED_DOCUMENTS = 2
        const val MAX_STATEMENT_CHARS = 1_200
        const val MAX_REFINEMENT_TERMS = 8
        const val MAX_ROOT_TERMS = 8
    }
}
