package app.lifeos.core.language

import java.util.Locale

data class EntityPipelineV3Result(
    val entities: List<SemanticEntityV2>,
    val legacyProjection: List<SemanticEntity>,
)

fun interface SemanticEntityExtractorV3 {
    fun extract(utterance: NormalizedUtterance): List<SemanticEntityV2>
}

class EntityEngineV3(
    baseline: DeterministicEntityPipelineV2,
    extractors: List<SemanticEntityExtractorV3> = listOf(
        ContactAddressEntityExtractorV3(),
        NamedEntityExtractorV3(),
    ),
) {
    private val sources: List<SemanticEntityExtractorV3> =
        listOf(SemanticEntityExtractorV3 { baseline.extract(it).entities }) + extractors

    fun extract(utterance: NormalizedUtterance): EntityPipelineV3Result {
        val entities = sources
            .flatMap { it.extract(utterance) }
            .distinctBy {
                listOf(
                    it.typeId.value,
                    it.tokenStart.toString(),
                    it.tokenEndExclusive.toString(),
                    it.normalizedValue,
                )
            }
            .sortedWith(
                compareBy<SemanticEntityV2> { it.tokenStart }
                    .thenBy { it.tokenEndExclusive }
                    .thenBy { it.typeId.value }
                    .thenByDescending { it.confidence }
                    .thenBy { it.source }
            )

        val legacyProjection = entities
            .mapNotNull(SemanticEntityV2::legacyProjection)
            .distinctBy {
                listOf(
                    it.type.name,
                    it.tokenStart.toString(),
                    it.tokenEndExclusive.toString(),
                    it.normalizedValue,
                )
            }
            .sortedWith(
                compareBy<SemanticEntity> { it.tokenStart }
                    .thenBy { it.tokenEndExclusive }
                    .thenBy { it.type.name }
            )

        return EntityPipelineV3Result(entities, legacyProjection)
    }
}

class ContactAddressEntityExtractorV3 : SemanticEntityExtractorV3 {
    override fun extract(utterance: NormalizedUtterance): List<SemanticEntityV2> = buildList {
        addMatches(
            utterance,
            ADDRESS_REGEX,
            EntityTypeRegistry.ADDRESS,
            confidence = 0.96,
            source = "entity-v3-address-pattern",
        ) { raw -> raw.replace(Regex("\\s+"), " ").trim() }
        addMatches(
            utterance,
            EMAIL_REGEX,
            EntityTypeRegistry.EMAIL_ADDRESS,
            confidence = 0.998,
            source = "entity-v3-email-pattern",
        ) { raw -> raw.lowercase(Locale.ROOT) }
        addMatches(
            utterance,
            URL_REGEX,
            EntityTypeRegistry.URL,
            confidence = 0.998,
            source = "entity-v3-url-pattern",
        ) { raw -> raw.trim() }
        addMatches(
            utterance,
            PHONE_REGEX,
            EntityTypeRegistry.PHONE_NUMBER,
            confidence = 0.97,
            source = "entity-v3-phone-pattern",
        ) { raw -> raw.replace(Regex("[^+0-9]"), "") }
    }

    private fun MutableList<SemanticEntityV2>.addMatches(
        utterance: NormalizedUtterance,
        regex: Regex,
        type: SemanticEntityTypeDefinition,
        confidence: Double,
        source: String,
        normalize: (String) -> String,
    ) {
        regex.findAll(utterance.original).forEach { match ->
            val tokenRange = utterance.tokenRange(match.range.first, match.range.last + 1)
                ?: return@forEach
            val raw = match.value.trim()
            add(
                SemanticEntityV2(
                    typeId = type.id,
                    rawText = raw,
                    normalizedValue = normalize(raw),
                    tokenStart = tokenRange.first,
                    tokenEndExclusive = tokenRange.last + 1,
                    confidence = confidence,
                    source = source,
                )
            )
        }
    }

    private companion object {
        val EMAIL_REGEX = Regex("""\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b""", RegexOption.IGNORE_CASE)
        val URL_REGEX = Regex("""\bhttps?://[^\s<>()]+""", RegexOption.IGNORE_CASE)
        val PHONE_REGEX = Regex("""(?<!\w)(?:\+?\d[\d /()-]{6,}\d)(?!\w)""")
        val ADDRESS_REGEX = Regex(
            """(?iu)\b[\p{L}][\p{L}.'’-]*(?:\s+[\p{L}][\p{L}.'’-]*){0,3}\s+""" +
                """(?:straße|strasse|str\.|weg|platz|allee|gasse)\s+\d+[a-z]?\b"""
        )
    }
}

class NamedEntityExtractorV3 : SemanticEntityExtractorV3 {
    override fun extract(utterance: NormalizedUtterance): List<SemanticEntityV2> = buildList {
        ORGANIZATION_REGEX.findAll(utterance.original).forEach { match ->
            val tokenRange = utterance.tokenRange(match.range.first, match.range.last + 1)
                ?: return@forEach
            val raw = match.value.trim()
            add(
                SemanticEntityV2(
                    typeId = EntityTypeRegistry.ORGANIZATION.id,
                    rawText = raw,
                    normalizedValue = normalizeName(raw),
                    tokenStart = tokenRange.first,
                    tokenEndExclusive = tokenRange.last + 1,
                    confidence = 0.93,
                    source = "entity-v3-organization-pattern",
                )
            )
        }

        TITLED_PERSON_REGEX.findAll(utterance.original).forEach { match ->
            val tokenRange = utterance.tokenRange(match.range.first, match.range.last + 1)
                ?: return@forEach
            val raw = match.value.trim()
            add(
                SemanticEntityV2(
                    typeId = EntityTypeRegistry.PERSON.id,
                    rawText = raw,
                    normalizedValue = normalizePerson(raw),
                    tokenStart = tokenRange.first,
                    tokenEndExclusive = tokenRange.last + 1,
                    confidence = 0.97,
                    source = "entity-v3-titled-person-pattern",
                )
            )
        }

        PERSON_PAIR_REGEX.findAll(utterance.original).forEach { match ->
            val tokenRange = utterance.tokenRange(match.range.first, match.range.last + 1)
                ?: return@forEach
            if (tokenRange.first == 0 && utterance.tokens.size > tokenRange.last + 1) {
                val first = utterance.tokens[tokenRange.first].normalized
                if (first in SENTENCE_LEADS) return@forEach
            }
            val raw = match.value.trim()
            if (raw.lowercase(Locale.ROOT).split(Regex("\\s+")).any { it in ORGANIZATION_SUFFIXES }) {
                return@forEach
            }
            add(
                SemanticEntityV2(
                    typeId = EntityTypeRegistry.PERSON.id,
                    rawText = raw,
                    normalizedValue = normalizePerson(raw),
                    tokenStart = tokenRange.first,
                    tokenEndExclusive = tokenRange.last + 1,
                    confidence = 0.84,
                    source = "entity-v3-person-name-pattern",
                )
            )
        }
    }

    private fun normalizeName(raw: String): String =
        raw.replace(Regex("\\s+"), " ").trim()

    private fun normalizePerson(raw: String): String =
        raw.replace(TITLE_PREFIX_REGEX, "")
            .replace(Regex("\\s+"), " ")
            .trim()

    private companion object {
        val ORGANIZATION_SUFFIXES = setOf(
            "gmbh", "ag", "kg", "bank", "versicherung", "klinik", "universität", "universitaet",
            "verein", "amt", "behörde", "behoerde",
        )
        val ORGANIZATION_REGEX = Regex(
            """(?u)\b(?:[A-ZÄÖÜ][\p{L}&.'’-]*\s+){0,4}""" +
                """(?:GmbH|AG|KG|e\.V\.|Bank|Versicherung|Klinik|Universität|Amt|Behörde)\b"""
        )
        val TITLED_PERSON_REGEX = Regex(
            """(?u)\b(?:Herr|Frau|Dr\.|Doktor|Prof\.|Professor|Mr\.|Mrs\.|Ms\.)\s+""" +
                """[A-ZÄÖÜ][\p{L}'’-]+(?:\s+[A-ZÄÖÜ][\p{L}'’-]+){0,2}\b"""
        )
        val PERSON_PAIR_REGEX = Regex(
            """(?u)\b[A-ZÄÖÜ][\p{L}'’-]{1,}(?:\s+[A-ZÄÖÜ][\p{L}'’-]{1,})\b"""
        )
        val TITLE_PREFIX_REGEX = Regex(
            """(?iu)^(?:Herr|Frau|Dr\.|Doktor|Prof\.|Professor|Mr\.|Mrs\.|Ms\.)\s+"""
        )
        val SENTENCE_LEADS = setOf(
            "Bitte", "Wenn", "Falls", "Der", "Die", "Das", "Ein", "Eine",
            "Please", "If", "The", "A", "An",
        ).mapTo(hashSetOf()) { it.lowercase(Locale.ROOT) }
    }
}

private fun NormalizedUtterance.tokenRange(
    charStart: Int,
    charEndExclusive: Int,
): IntRange? {
    val overlapping = tokens.withIndex().filter { (_, token) ->
        token.start < charEndExclusive && token.endExclusive > charStart
    }
    if (overlapping.isEmpty()) return null
    return overlapping.first().index..overlapping.last().index
}
