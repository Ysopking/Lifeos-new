package app.lifeos.core.language

/**
 * Deterministic clause-local syntax features used by [SpeechActParser].
 *
 * This analyzer does not infer intent and does not authorize execution. It only exposes stable
 * surface/syntax evidence, including terminal punctuation that deliberately lives outside the
 * clause span produced by [LanguageSemanticGraphExtractor].
 */
internal class ClauseSyntaxAnalyzer {
    fun analyze(
        utterance: NormalizedUtterance,
        clause: SemanticClause,
    ): ClauseSyntaxFeatures {
        val tokens = utterance.tokens.subList(clause.tokenStart, clause.tokenEndExclusive)
        val words = tokens
            .filter { it.kind == TokenKind.WORD }
            .map { it.normalized }
        val semanticWords = words.dropWhile { it in CLAUSE_LEADING_CUES }
        val first = semanticWords.firstOrNull()
        val second = semanticWords.getOrNull(1)
        val prefix = semanticWords.take(4).toSet()

        val explicitQuestionMark = terminalPunctuation(utterance, clause) == "?"
        val interrogativeLead = first in QUESTION_WORDS
        val politeImperative =
            first in POLITENESS_MARKERS &&
                semanticWords.drop(1).firstOrNull() in SpeechActParser.DIRECT_COMMAND_VERBS
        val addressedRequest =
            politeImperative ||
                (
                    first in REQUEST_AUXILIARIES &&
                        prefix.any { it in SECOND_PERSON_PRONOUNS } &&
                        semanticWords.any { it in SpeechActParser.DIRECT_COMMAND_VERBS }
                    ) ||
                (
                    first in ADDRESSABLE_QUESTION_AUXILIARIES &&
                        second in SECOND_PERSON_PRONOUNS &&
                        semanticWords.any { it in SpeechActParser.DIRECT_COMMAND_VERBS }
                    )
        val auxiliaryQuestion =
            !addressedRequest &&
                first in QUESTION_AUXILIARIES &&
                (
                    explicitQuestionMark ||
                        second in QUESTION_SUBJECTS ||
                        second in ARTICLES ||
                        second in POSSESSIVES
                    )
        val imperativeLead =
            first in SpeechActParser.DIRECT_COMMAND_VERBS ||
                politeImperative

        return ClauseSyntaxFeatures(
            words = words,
            semanticWords = semanticWords,
            explicitQuestionMark = explicitQuestionMark,
            interrogativeLead = interrogativeLead,
            auxiliaryQuestion = auxiliaryQuestion,
            addressedRequest = addressedRequest,
            politeImperative = politeImperative,
            imperativeLead = imperativeLead,
        )
    }

    private fun terminalPunctuation(
        utterance: NormalizedUtterance,
        clause: SemanticClause,
    ): String? {
        var index = clause.tokenEndExclusive
        while (index < utterance.tokens.size) {
            val token = utterance.tokens[index]
            if (token.kind != TokenKind.PUNCTUATION) return null
            if (token.original in QUOTE_MARKERS) {
                index += 1
                continue
            }
            return token.original
        }
        return null
    }

    private companion object {
        val QUESTION_WORDS = setOf(
            "wie", "warum", "wieso", "was", "wer", "wen", "wem", "wo", "wohin", "wann",
            "welche", "welcher", "welches",
            "how", "why", "what", "who", "where", "when", "which",
        )
        val QUESTION_AUXILIARIES = setOf(
            "ist", "sind", "hat", "haben", "kann", "können", "koennen", "darf", "soll",
            "is", "are", "do", "does", "did", "can", "could", "would", "should",
        )
        val ADDRESSABLE_QUESTION_AUXILIARIES = setOf(
            "kann", "kannst", "können", "koennen", "könntest", "koenntest",
            "würdest", "wuerdest",
            "can", "could", "would",
        )
        val REQUEST_AUXILIARIES = setOf(
            "kannst", "könntest", "koenntest", "würdest", "wuerdest",
            "could", "would",
        )
        val SECOND_PERSON_PRONOUNS = setOf("du", "ihr", "sie", "you")
        val QUESTION_SUBJECTS = setOf(
            "ich", "du", "er", "sie", "es", "wir", "ihr",
            "i", "you", "he", "she", "it", "we", "they",
        )
        val ARTICLES = setOf(
            "der", "die", "das", "den", "dem", "ein", "eine", "einen", "einem",
            "the", "a", "an",
        )
        val POSSESSIVES = setOf(
            "mein", "meine", "dein", "deine", "sein", "seine", "ihr", "ihre", "unser", "unsere",
            "my", "your", "his", "her", "our", "their",
        )
        val POLITENESS_MARKERS = setOf("bitte", "please")
        val CLAUSE_LEADING_CUES = setOf(
            "und", "oder", "aber", "danach", "anschließend", "anschliessend",
            "and", "or", "but", "then",
        )
        val QUOTE_MARKERS = setOf(""", "„", "“", "”", "«", "»")
    }
}

internal data class ClauseSyntaxFeatures(
    val words: List<String>,
    val semanticWords: List<String>,
    val explicitQuestionMark: Boolean,
    val interrogativeLead: Boolean,
    val auxiliaryQuestion: Boolean,
    val addressedRequest: Boolean,
    val politeImperative: Boolean,
    val imperativeLead: Boolean,
) {
    val question: Boolean
        get() = explicitQuestionMark || interrogativeLead || auxiliaryQuestion
}
