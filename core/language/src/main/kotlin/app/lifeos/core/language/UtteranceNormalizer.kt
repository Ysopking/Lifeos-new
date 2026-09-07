package app.lifeos.core.language

import java.text.Normalizer
import java.util.Locale

class UtteranceNormalizer {
    private val tokenRegex = Regex("\\p{L}[\\p{L}\\p{M}'’-]*|\\d+(?:[.,:]\\d+)*|[^\\s\\p{L}\\p{N}]")

    fun normalize(text: String): NormalizedUtterance {
        require(text.isNotBlank())
        val trimmed = text.trim()
        val tokens = tokenRegex.findAll(trimmed).map { match ->
            val original = match.value
            LanguageToken(
                original = original,
                normalized = canonicalize(original),
                kind = when {
                    original.first().isLetter() -> TokenKind.WORD
                    original.first().isDigit() -> TokenKind.NUMBER
                    else -> TokenKind.PUNCTUATION
                },
                start = match.range.first,
                endExclusive = match.range.last + 1,
            )
        }.toList()
        return NormalizedUtterance(
            original = trimmed,
            normalized = tokens.joinToString(" ") { it.normalized },
            language = detectLanguage(tokens),
            tokens = tokens,
        )
    }

    private fun canonicalize(value: String): String = Normalizer
        .normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .replace('ß', 's')
        .replace("ss", "ss")

    private fun detectLanguage(tokens: List<LanguageToken>): LanguageCode {
        val words = tokens.asSequence().filter { it.kind == TokenKind.WORD }.map { it.normalized }.toList()
        if (words.isEmpty()) return LanguageCode.UNKNOWN
        val germanMarkers = setOf(
            "der", "die", "das", "ein", "eine", "und", "oder", "ich", "du", "wir", "bitte",
            "mach", "mache", "erzeuge", "erstelle", "suche", "finde", "weiter", "gestern", "heute",
            "morgen", "bild", "datei", "von", "mit", "ohne", "am", "um",
        )
        val englishMarkers = setOf(
            "the", "a", "an", "and", "or", "i", "you", "we", "please", "make", "create", "generate",
            "search", "find", "continue", "yesterday", "today", "tomorrow", "image", "file", "with", "without",
        )
        val german = words.count { it in germanMarkers }
        val english = words.count { it in englishMarkers }
        return when {
            german > english -> LanguageCode.DE
            english > german -> LanguageCode.EN
            else -> LanguageCode.UNKNOWN
        }
    }
}
