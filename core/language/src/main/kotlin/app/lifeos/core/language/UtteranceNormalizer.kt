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
        .replace("ß", "ss")

    private fun detectLanguage(tokens: List<LanguageToken>): LanguageCode {
        val words = tokens.asSequence().filter { it.kind == TokenKind.WORD }.map { it.normalized }.toList()
        if (words.isEmpty()) return LanguageCode.UNKNOWN
        val germanMarkers = setOf(
            "der", "die", "das", "den", "dem", "des", "ein", "eine", "einen", "und", "oder",
            "ich", "du", "wir", "ihr", "mir", "mich", "dir", "dich", "bitte", "kannst", "könntest", "koenntest",
            "mach", "mache", "machen", "erzeuge", "erstelle", "suche", "finde", "schau", "sieh", "guck",
            "prüf", "pruef", "sende", "schicke", "schreib", "merke", "merk", "plane", "setz", "setze",
            "weiter", "nochmal", "nein", "doch", "sondern", "statt", "gestern", "heute", "morgen",
            "bild", "datei", "bescheid", "termin", "von", "mit", "ohne", "am", "um", "nach",
        )
        val englishMarkers = setOf(
            "the", "a", "an", "and", "or", "i", "you", "we", "they", "me", "my", "your", "please",
            "can", "could", "would", "make", "create", "generate", "search", "find", "look", "check",
            "send", "reply", "remember", "store", "schedule", "continue", "again", "no", "rather", "instead",
            "yesterday", "today", "tomorrow", "image", "file", "message", "appointment", "with", "without",
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
