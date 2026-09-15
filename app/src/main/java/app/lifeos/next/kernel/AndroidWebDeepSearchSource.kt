package app.lifeos.next.kernel

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonPhase
import app.lifeos.core.model.Provenance
import app.lifeos.core.runtime.deepsearch.DeepSearchBranch
import app.lifeos.core.runtime.deepsearch.DeepSearchEvidenceDraft
import app.lifeos.core.runtime.deepsearch.DeepSearchFindingDraft
import app.lifeos.core.runtime.deepsearch.DeepSearchPermissionState
import app.lifeos.core.runtime.deepsearch.DeepSearchRequest
import app.lifeos.core.runtime.deepsearch.DeepSearchSource
import app.lifeos.core.runtime.deepsearch.DeepSearchSourceDescriptor
import app.lifeos.core.runtime.deepsearch.DeepSearchSourceKind
import app.lifeos.core.runtime.goal.LocalDeepSearchGoalEngine
import app.lifeos.core.runtime.policy.OwnerEffectExposureResult
import app.lifeos.core.runtime.policy.OwnerPolicyEffectGate
import app.lifeos.core.runtime.policy.OwnerPolicyLedger
import java.io.InputStream
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
    val confidence: Double = 0.78,
) {
    init {
        require(title.isNotBlank())
        require(url.isNotBlank())
        require(confidence in 0.0..1.0)
    }
}

internal fun interface WebSearchTransport {
    suspend fun search(query: String, maxBytes: Int): List<WebSearchHit>
}

/**
 * Narrow HTTPS transport for public Web DeepSearch. It is deliberately not a generic network
 * client: only the fixed search endpoint is contacted, cleartext is never accepted and the body is
 * bounded before parsing.
 */
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
            val body = connection.inputStream.use { input ->
                readBoundedUtf8(input, maxBytes)
            }
            WebSearchHtmlParser.parse(body, query)
        } finally {
            connection.disconnect()
        }
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

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 3_000
        const val READ_TIMEOUT_MILLIS = 4_000
        const val MAX_RESPONSE_BYTES = 512 * 1024
        const val USER_AGENT = "LIFEOS-Private/1.0 WebDeepSearch"
    }
}

/** Minimal parser for the fixed search result surface; it never executes or renders returned HTML. */
internal object WebSearchHtmlParser {
    fun parse(html: String, query: String, limit: Int = 8): List<WebSearchHit> {
        require(limit in 1..32)
        val queryTerms = terms(query)
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
                val candidateTerms = terms("$title $snippet")
                val hits = candidateTerms.intersect(queryTerms)
                if (queryTerms.isNotEmpty() && hits.isEmpty()) return@mapIndexedNotNull null
                WebSearchHit(
                    title = title.take(MAX_TITLE_CHARS),
                    url = target,
                    snippet = snippet.take(MAX_SNIPPET_CHARS),
                )
            }
            .distinctBy { it.url to it.title }
            .take(limit)
            .toList()
    }

    private fun canonicalHttpsTarget(raw: String): String? {
        val decoded = decodeEntities(raw).trim()
        val absolute = when {
            decoded.startsWith("//") -> "https:$decoded"
            else -> decoded
        }
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

    private fun terms(value: String): Set<String> = TERMS.findAll(value)
        .map { it.value.lowercase() }
        .filter { it.length >= 2 }
        .take(128)
        .toSet()

    private val RESULT_ANCHOR = Regex(
        """(?is)<a[^>]+class=[\"'][^\"']*result__a[^\"']*[\"'][^>]+href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>"""
    )
    private val SNIPPET = Regex(
        """(?is)<[^>]+class=[\"'][^\"']*result__snippet[^\"']*[\"'][^>]*>(.*?)</[^>]+>"""
    )
    private val TAGS = Regex("(?is)<[^>]+>")
    private val TERMS = Regex("[\\p{L}\\p{N}]+")
    private val WHITESPACE = Regex("\\s+")
    private const val MAX_SNIPPET_SCAN_CHARS = 2_048
    private const val MAX_TITLE_CHARS = 220
    private const val MAX_SNIPPET_CHARS = 420
}

/**
 * Existing DeepSearchSource contract backed by bounded Web search. Every adopted Web fact is first
 * persisted as an immutable Photon and then referenced by DeepSearchEvidence.sourcePhotonId.
 */
internal class AndroidWebDeepSearchSource(
    ownerPolicy: OwnerPolicyLedger,
    private val transport: WebSearchTransport,
    private val loadPhoton: suspend (PhotonId) -> Photon?,
    private val persistPhoton: suspend (Photon) -> Photon,
    private val now: () -> Instant = Instant::now,
) : DeepSearchSource {
    private val effects = OwnerPolicyEffectGate(ownerPolicy)

    override val descriptor = DeepSearchSourceDescriptor(
        sourceId = WebDeepSearchOwnerPolicy.SOURCE_ID,
        kind = DeepSearchSourceKind.EXTERNAL,
        capabilityId = WebDeepSearchOwnerPolicy.CAPABILITY_ID,
        permissionState = DeepSearchPermissionState.UNKNOWN,
        reliability = 0.78,
        workUnitsPerExpansion = 4,
    )

    override suspend fun expand(
        request: DeepSearchRequest,
        branch: DeepSearchBranch,
    ): List<DeepSearchFindingDraft> {
        if (branch.depth > 0) return emptyList()
        val hits = when (val exposure = effects.expose(WebDeepSearchOwnerPolicy.request()) {
            transport.search(request.query, LocalDeepSearchGoalEngine.WEB_NETWORK_BYTES.toInt())
        }) {
            is OwnerEffectExposureResult.Exposed -> exposure.value
            is OwnerEffectExposureResult.Blocked -> error("deepsearch-web-owner-policy-blocked")
        }
        return hits.map { hit ->
            val evidencePhoton = evidencePhoton(hit)
            val statement = listOf(hit.title, hit.snippet)
                .filter { it.isNotBlank() }
                .joinToString(" — ")
                .take(MAX_STATEMENT_CHARS)
            val semanticTerms = TERMS.findAll(statement)
                .map { it.value.lowercase() }
                .filter { it.length >= 2 }
                .take(128)
                .toSet()
            DeepSearchFindingDraft(
                statement = statement,
                semanticTerms = semanticTerms,
                confidence = hit.confidence,
                evidence = listOf(
                    DeepSearchEvidenceDraft(
                        statement = statement,
                        confidence = hit.confidence,
                        sourcePhotonId = evidencePhoton.id,
                        sourcePhotonRevision = evidencePhoton.revision,
                    )
                ),
            )
        }
    }

    private suspend fun evidencePhoton(hit: WebSearchHit): Photon {
        val normalizedContent = buildString {
            appendLine(hit.title.trim())
            if (hit.snippet.isNotBlank()) appendLine(hit.snippet.trim())
            append("Source: ").append(hit.url)
        }.trim()
        val id = PhotonId(
            "web-evidence_" + StableFieldIds.fingerprint(
                "deepsearch-web-evidence/v1",
                descriptor.sourceId,
                hit.url,
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
            semanticMass = 1.0,
            energy = 1.0,
            confidence = hit.confidence,
            provenance = Provenance(
                source = hit.url,
                actor = descriptor.sourceId,
                createdAt = now(),
            ),
            tags = setOf(
                "web-evidence",
                "deepsearch-evidence",
                "deepsearch-source:${descriptor.sourceId}",
                "web-url:${StableFieldIds.fingerprint("web-url/v1", hit.url)}",
            ),
        )
        return persistPhoton(photon)
    }

    private companion object {
        private val TERMS = Regex("[\\p{L}\\p{N}]+")
        private const val MAX_STATEMENT_CHARS = 640
    }
}
