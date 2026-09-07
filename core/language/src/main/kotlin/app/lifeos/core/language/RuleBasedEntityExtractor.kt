package app.lifeos.core.language

import java.util.Locale

class RuleBasedEntityExtractor {
    private val numberWords = mapOf(
        "null" to 0, "eins" to 1, "ein" to 1, "eine" to 1, "einen" to 1, "zwei" to 2, "drei" to 3,
        "vier" to 4, "fuenf" to 5, "fünf" to 5, "sechs" to 6, "sieben" to 7, "acht" to 8, "neun" to 9, "zehn" to 10,
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
        "eight" to 8, "nine" to 9, "ten" to 10,
    )
    private val colors = setOf(
        "rot", "gruen", "grün", "blau", "gelb", "orange", "lila", "violett", "schwarz", "weiss", "weiß", "grau", "braun",
        "red", "green", "blue", "yellow", "orange", "purple", "black", "white", "grey", "gray", "brown",
    )
    private val imageWords = setOf("bild", "bilder", "foto", "fotos", "grafik", "grafiken", "image", "images", "photo", "photos", "picture", "pictures")
    private val actionWords = setOf(
        "spielen", "spielt", "laufend", "laufen", "sitzt", "sitzen", "steht", "stehen", "fliegt", "fahren",
        "playing", "play", "running", "run", "sitting", "sit", "standing", "stand", "flying", "driving",
    )
    private val objectWords = setOf(
        "fussball", "ball", "auto", "fahrrad", "hund", "katze", "baum", "haus", "football", "soccer", "car", "bike", "dog", "cat", "tree", "house",
    )
    private val styleWords = setOf(
        "fotorealistisch", "realistisch", "cinematisch", "comic", "vektor", "aquarell", "photorealistic", "realistic", "cinematic", "vector", "watercolor",
    )
    private val relativeDates = mapOf(
        "heute" to "relative:today", "gestern" to "relative:yesterday", "morgen" to "relative:tomorrow",
        "today" to "relative:today", "yesterday" to "relative:yesterday", "tomorrow" to "relative:tomorrow",
    )
    private val fileRegex = Regex("(?i)([\\p{L}\\p{N}_ .-]+\\.(?:png|jpe?g|webp|pdf|txt|md|json|kt|java|cpp|h|apk))")
    private val timeRegex = Regex("(?i)\\b(?:[01]?\\d|2[0-3])[:.]?[0-5]\\d\\b|\\b(?:[01]?\\d|2[0-3])\\s*(?:uhr|am|pm)\\b")
    private val dateRegex = Regex("\\b(?:0?[1-9]|[12]\\d|3[01])[./-](?:0?[1-9]|1[0-2])(?:[./-](?:19|20)\\d{2})?\\b")

    fun extract(utterance: NormalizedUtterance): List<SemanticEntity> {
        val entities = mutableListOf<SemanticEntity>()
        utterance.tokens.forEachIndexed { index, token ->
            val word = token.normalized
            when {
                token.kind == TokenKind.NUMBER && word.all { it.isDigit() } -> entities += entity(EntityType.NUMBER, token.original, word, index, 0.99)
                word in numberWords -> entities += entity(EntityType.NUMBER, token.original, numberWords.getValue(word).toString(), index, 0.95)
            }
            if (word in colors) entities += entity(EntityType.COLOR, token.original, word, index, 0.98)
            if (word in imageWords) entities += entity(EntityType.IMAGE, token.original, "image", index, 0.99)
            if (word in actionWords) entities += entity(EntityType.ACTION, token.original, word, index, 0.90)
            if (word in objectWords) entities += entity(EntityType.OBJECT, token.original, word, index, 0.92)
            if (word in styleWords) entities += entity(EntityType.STYLE, token.original, word, index, 0.96)
            relativeDates[word]?.let { entities += entity(EntityType.DATE, token.original, it, index, 0.98) }
        }

        addRegexEntities(utterance, fileRegex, EntityType.FILE, 0.99, entities) { it.trim().lowercase(Locale.ROOT) }
        addRegexEntities(utterance, dateRegex, EntityType.DATE, 0.99, entities) { it }
        addRegexEntities(utterance, timeRegex, EntityType.TIME, 0.97, entities) { it.lowercase(Locale.ROOT).replace(" ", "") }
        entities += extractLocations(utterance)

        return entities
            .distinctBy { listOf(it.type.name, it.tokenStart.toString(), it.tokenEndExclusive.toString(), it.normalizedValue) }
            .sortedWith(compareBy<SemanticEntity> { it.tokenStart }.thenBy { it.type.name })
    }

    private fun extractLocations(utterance: NormalizedUtterance): List<SemanticEntity> {
        val result = mutableListOf<SemanticEntity>()
        val prepositions = setOf("in", "bei", "am", "near", "at")
        for (i in 0 until utterance.tokens.lastIndex) {
            val token = utterance.tokens[i]
            if (token.normalized !in prepositions) continue
            val collected = mutableListOf<LanguageToken>()
            var cursor = i + 1
            while (cursor < utterance.tokens.size && collected.size < 3) {
                val next = utterance.tokens[cursor]
                if (next.kind != TokenKind.WORD) break
                val looksNamed = next.original.firstOrNull()?.isUpperCase() == true || next.normalized in KNOWN_LOCATIONS
                if (!looksNamed && collected.isEmpty()) break
                if (!looksNamed && collected.isNotEmpty()) break
                collected += next
                cursor++
            }
            if (collected.isNotEmpty()) {
                val raw = collected.joinToString(" ") { it.original }
                result += SemanticEntity(
                    type = EntityType.LOCATION,
                    rawText = raw,
                    normalizedValue = collected.joinToString(" ") { it.normalized },
                    tokenStart = i + 1,
                    tokenEndExclusive = i + 1 + collected.size,
                    confidence = if (collected.all { it.original.firstOrNull()?.isUpperCase() == true }) 0.88 else 0.76,
                )
            }
        }
        return result
    }

    private fun addRegexEntities(
        utterance: NormalizedUtterance,
        regex: Regex,
        type: EntityType,
        confidence: Double,
        out: MutableList<SemanticEntity>,
        normalize: (String) -> String,
    ) {
        for (match in regex.findAll(utterance.original)) {
            val overlapping = utterance.tokens.withIndex().filter { (_, token) ->
                token.start < match.range.last + 1 && token.endExclusive > match.range.first
            }
            if (overlapping.isEmpty()) continue
            out += SemanticEntity(
                type = type,
                rawText = match.value.trim(),
                normalizedValue = normalize(match.value.trim()),
                tokenStart = overlapping.first().index,
                tokenEndExclusive = overlapping.last().index + 1,
                confidence = confidence,
            )
        }
    }

    private fun entity(type: EntityType, raw: String, value: String, index: Int, confidence: Double) = SemanticEntity(
        type = type,
        rawText = raw,
        normalizedValue = value,
        tokenStart = index,
        tokenEndExclusive = index + 1,
        confidence = confidence,
    )

    private companion object {
        val KNOWN_LOCATIONS = setOf("berlin", "hamburg", "muenchen", "münchen", "koeln", "köln", "frankfurt", "london", "paris", "tokyo")
    }
}
