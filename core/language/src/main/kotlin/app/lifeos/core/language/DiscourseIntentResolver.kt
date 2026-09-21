package app.lifeos.core.language

/**
 * Bounded discourse carry-over for elliptical follow-up turns.
 *
 * Only descriptive intent evidence is inherited. No predicate frame, role, reference or execution
 * authority is synthesized from discourse context.
 */
class DiscourseIntentResolver {
    fun evidence(
        utterance: NormalizedUtterance,
        context: LanguageContext,
        existingEvidence: List<IntentEvidence>,
    ): List<IntentEvidence> {
        val lexical = utterance.tokens.filter { it.kind != TokenKind.PUNCTUATION }
        if (lexical.isEmpty() || lexical.size > MAX_ELLIPTICAL_TOKENS) return emptyList()

        val currentStrong = existingEvidence
            .filter { it.intent !in setOf(IntentType.UNKNOWN, IntentType.QUERY, IntentType.CONVERSATION) }
            .maxOfOrNull { it.score }
            ?: 0.0
        if (currentStrong >= CURRENT_EVIDENCE_SUPPRESSION) return emptyList()

        val words = lexical.map { it.normalized }
        if (!looksElliptical(words)) return emptyList()

        val prior = context.items
            .asSequence()
            .filter { item -> item.tags.any { it.startsWith(INTENT_TAG_PREFIX) } }
            .sortedWith(
                compareByDescending<LanguageContextItem> { it.active }
                    .thenByDescending { it.createdAt }
                    .thenByDescending { it.revisionRef?.revision ?: 0L }
                    .thenBy { it.photonId.value }
            )
            .mapNotNull { item ->
                val tag = item.tags.firstOrNull { it.startsWith(INTENT_TAG_PREFIX) }
                    ?: return@mapNotNull null
                val intentName = tag.substringAfter(INTENT_TAG_PREFIX).uppercase()
                val intent = runCatching { IntentType.valueOf(intentName) }.getOrNull()
                    ?: return@mapNotNull null
                if (intent in DISALLOWED_INHERITED_INTENTS) return@mapNotNull null
                item to intent
            }
            .firstOrNull()
            ?: return emptyList()

        val (item, intent) = prior
        val activeBonus = if (item.active) 0.08 else 0.0
        val score = (BASE_SCORE + activeBonus).coerceAtMost(MAX_SCORE)
        return listOf(
            IntentEvidence(
                intent = intent,
                score = score,
                reasons = listOf(
                    "discourse-ellipsis:v1",
                    "prior=" + item.photonId.value + "@" + (item.revisionRef?.revision ?: 0L),
                ),
            )
        )
    }

    private fun looksElliptical(words: List<String>): Boolean {
        if (words.any { it in EXPLICIT_ELLIPSIS_CUES }) return true
        if (words.size <= 4 && words.any { it in DEICTIC_CUES }) return true
        if (words.size <= 5 && words.any { it in CORRECTION_CUES }) return true
        if (words.size <= 4 && words.any { it in TEMPORAL_OR_STYLE_CUES }) return true
        return false
    }

    private companion object {
        const val INTENT_TAG_PREFIX = "intent:"
        const val MAX_ELLIPTICAL_TOKENS = 8
        const val CURRENT_EVIDENCE_SUPPRESSION = 0.58
        const val BASE_SCORE = 0.58
        const val MAX_SCORE = 0.68

        val DISALLOWED_INHERITED_INTENTS = setOf(
            IntentType.UNKNOWN,
            IntentType.CONVERSATION,
        )

        val EXPLICIT_ELLIPSIS_CUES = setOf(
            "nochmal", "nochmals", "wieder", "weiter", "genau", "ebenso", "auch",
            "again", "same", "continue",
        )
        val DEICTIC_CUES = setOf(
            "das", "dies", "diese", "dieses", "diesen", "andere", "anderen", "so",
            "it", "this", "that", "other", "same",
        )
        val CORRECTION_CUES = setOf(
            "nein", "doch", "sondern", "statt", "stattdessen", "lieber",
            "no", "rather", "instead",
        )
        val TEMPORAL_OR_STYLE_CUES = setOf(
            "heute", "morgen", "übermorgen", "uebermorgen", "freitag", "samstag", "sonntag",
            "heller", "dunkler", "wärmer", "waermer", "kürzer", "kuerzer", "länger", "laenger",
            "today", "tomorrow", "friday", "saturday", "sunday",
            "brighter", "darker", "warmer", "shorter", "longer",
        )
    }
}
