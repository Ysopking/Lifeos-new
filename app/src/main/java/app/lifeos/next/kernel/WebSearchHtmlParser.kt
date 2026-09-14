package app.lifeos.next.kernel

import app.lifeos.core.runtime.deepsearch.DeepSearchEvidenceDraft
import app.lifeos.core.runtime.deepsearch.DeepSearchFindingDraft

internal object WebSearchHtmlParser {
    fun parse(html: String, query: String, limit: Int = 8): List<DeepSearchFindingDraft> {
        val queryTerms = terms(query)
        return RESULT_REGEX.findAll(html)
            .mapNotNull { match ->
                val href = decode(match.groupValues[1])
                val title = clean(match.groupValues[2])
                if (href.isBlank() || title.isBlank()) return@mapNotNull null
                val candidateTerms = terms(title)
                val hits = candidateTerms.intersect(queryTerms)
                if (queryTerms.isNotEmpty() && hits.isEmpty()) return@mapNotNull null
                val statement = "$title [$href]"
                DeepSearchFindingDraft(
                    statement = statement,
                    semanticTerms = hits.ifEmpty { candidateTerms.take(16).toSet() },
                    confidence = 0.78,
                    evidence = listOf(DeepSearchEvidenceDraft(statement, 0.78)),
                )
            }
            .distinctBy { it.statement }
            .take(limit)
            .toList()
    }

    private fun clean(value: String): String = decode(value.replace(TAG_REGEX, " "))
        .replace(WHITESPACE_REGEX, " ")
        .trim()

    private fun decode(value: String): String = value
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")

    private fun terms(value: String): Set<String> = TERM_REGEX.findAll(value)
        .map { it.value.lowercase() }
        .filter { it.length >= 2 }
        .take(128)
        .toSet()

    private val RESULT_REGEX = Regex(
        """(?is)<a[^>]+class=[\"'][^\"']*result__a[^\"']*[\"'][^>]+href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>"""
    )
    private val TAG_REGEX = Regex("(?is)<[^>]+>")
    private val TERM_REGEX = Regex("[\\p{L}\\p{N}]+")
    private val WHITESPACE_REGEX = Regex("\\s+")
}
