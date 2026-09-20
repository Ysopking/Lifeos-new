package app.lifeos.core.language

import java.text.Normalizer
import java.util.Locale

/**
 * Shared deterministic search normalization used by the language engine, local retrieval and Web
 * DeepSearch. It deliberately stays model-free and keeps exact/derived forms traceable.
 */
object SemanticSearchTerms {
    private val tokenRegex = Regex("[\\p{L}\\p{N}._-]+")
    private val suffixes = listOf(
        "ern", "em", "en", "er", "es", "e", "s",
        "ung", "ungen", "keit", "keiten", "heit", "heiten",
    )

    fun tokens(value: String): Set<String> = tokenRegex.findAll(value)
        .map { normalizeToken(it.value) }
        .filter { it.length >= 2 && it !in stopWords }
        .take(MAX_TERMS)
        .toSortedSet()

    fun expandedTokens(value: String): Set<String> = tokens(value)
        .flatMapTo(sortedSetOf()) { token -> variants(token) }

    fun normalizeToken(value: String): String {
        val lower = value.trim().lowercase(Locale.ROOT)
            .replace("ß", "ss")
            .replace("ä", "ae")
            .replace("ö", "oe")
            .replace("ü", "ue")
        return Normalizer.normalize(lower, Normalizer.Form.NFKC)
            .replace(Regex("[^\\p{L}\\p{N}._-]+"), "")
    }

    private fun variants(token: String): Set<String> = buildSet {
        add(token)
        suffixes.forEach { suffix ->
            if (token.length >= suffix.length + 4 && token.endsWith(suffix)) {
                add(token.removeSuffix(suffix))
            }
        }
        if (token.endsWith("ae")) add(token.removeSuffix("ae") + "a")
        if (token.endsWith("oe")) add(token.removeSuffix("oe") + "o")
        if (token.endsWith("ue")) add(token.removeSuffix("ue") + "u")
    }

    private const val MAX_TERMS = 192
    private val stopWords = setOf(
        "der", "die", "das", "den", "dem", "des", "ein", "eine", "einen", "einer",
        "und", "oder", "ich", "du", "wir", "sie", "es", "ist", "sind", "mit", "von",
        "zu", "in", "auf", "am", "an", "im", "fuer", "für", "ueber", "über", "bitte",
        "mir", "nach", "the", "a", "an", "and", "or", "i", "you", "we", "it", "is",
        "are", "with", "from", "to", "for", "about",
    ).map(::normalizeToken).toSet()
}
