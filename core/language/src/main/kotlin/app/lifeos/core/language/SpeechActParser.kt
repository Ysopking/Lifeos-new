package app.lifeos.core.language

/** Deterministic utterance/clause speech-act classification. Intent evidence is deliberately absent. */
class SpeechActParser {
    fun parse(
        utterance: NormalizedUtterance,
        graph: LanguageSemanticGraph,
    ): Map<Int, SpeechAct> {
        val quoteRanges = quoteRanges(utterance)
        return graph.clauses.associate { clause ->
            val span = clauseSpan(utterance, clause)
            clause.id to classify(utterance, clause, span, quoteRanges)
        }
    }

    private fun classify(
        utterance: NormalizedUtterance,
        clause: SemanticClause,
        span: TextSpan,
        quoteRanges: List<TextSpan>,
    ): SpeechAct {
        val tokens = utterance.tokens.subList(clause.tokenStart, clause.tokenEndExclusive)
        val words = tokens.filter { it.kind == TokenKind.WORD }.map { it.normalized }
        val semanticWords = words.dropWhile { it in CLAUSE_LEADING_CUES }
        val text = utterance.original.substring(span.start, span.endExclusive).trim()
        val quoted = quoteRanges.any { it.contains(span) || it.overlaps(span) && quoteCoverage(it, span) >= 0.80 }
        if (quoted) return act(
            SpeechActType.QUOTATION,
            0.99,
            span,
            "quote-boundary",
            "clause lies inside explicit quotation",
        )

        val lower = text.lowercase()
        val question = text.endsWith("?") ||
            semanticWords.firstOrNull() in QUESTION_WORDS ||
            semanticWords.take(3).any { it in QUESTION_AUXILIARIES }
        if (question) return act(
            SpeechActType.QUESTION,
            if (text.endsWith("?")) 0.99 else 0.92,
            span,
            "question-form",
            "question punctuation/interrogative syntax",
        )

        if (words.firstOrNull() in GREETINGS || lower in GREETING_PHRASES) {
            return act(SpeechActType.GREETING, 0.98, span, "social-form", "greeting")
        }
        if (words.firstOrNull() in ACKNOWLEDGEMENTS || lower in ACKNOWLEDGEMENT_PHRASES) {
            return act(SpeechActType.ACKNOWLEDGEMENT, 0.98, span, "social-form", "acknowledgement")
        }
        if (words.any { it in CORRECTION_MARKERS }) {
            return act(SpeechActType.CORRECTION, 0.90, span, "correction-cue", "contrast/correction cue")
        }

        val request = hasRequestForm(semanticWords)
        if (request) return act(
            SpeechActType.REQUEST,
            0.94,
            span,
            "request-form",
            "polite or modal request syntax",
        )

        if (semanticWords.firstOrNull() in HYPOTHETICAL_MARKERS ||
            semanticWords.any { it in HYPOTHETICAL_MARKERS } && semanticWords.none { it in DIRECT_COMMAND_VERBS }
        ) {
            return act(
                SpeechActType.HYPOTHETICAL,
                0.90,
                span,
                "hypothetical-cue",
                "conditional/subjunctive wording",
            )
        }

        val firstContent = semanticWords.firstOrNull()
        val imperative = firstContent in DIRECT_COMMAND_VERBS ||
            semanticWords.take(2).any { it in DIRECT_COMMAND_VERBS } &&
                semanticWords.firstOrNull() in POLITENESS_MARKERS
        if (imperative) return act(
            SpeechActType.COMMAND,
            0.96,
            span,
            "imperative-form",
            "deterministic command verb at clause head",
        )

        return act(
            SpeechActType.ASSERTION,
            0.78,
            span,
            "declarative-default",
            "no question/request/command boundary matched",
        )
    }

    private fun hasRequestForm(words: List<String>): Boolean {
        if (words.isEmpty()) return false
        if (words.first() in POLITENESS_MARKERS && words.any { it in DIRECT_COMMAND_VERBS }) return true
        val prefix = words.take(4).toSet()
        return prefix.any { it in REQUEST_AUXILIARIES } &&
            ("du" in prefix || "you" in prefix || "sie" in prefix) &&
            words.any { it in DIRECT_COMMAND_VERBS }
    }

    private fun clauseSpan(
        utterance: NormalizedUtterance,
        clause: SemanticClause,
    ): TextSpan {
        val first = utterance.tokens[clause.tokenStart]
        val last = utterance.tokens[clause.tokenEndExclusive - 1]
        return TextSpan(first.start, last.endExclusive)
    }

    private fun quoteRanges(utterance: NormalizedUtterance): List<TextSpan> {
        val result = mutableListOf<TextSpan>()
        var open: LanguageToken? = null
        utterance.tokens.forEach { token ->
            if (token.kind != TokenKind.PUNCTUATION || token.original !in QUOTE_MARKERS) return@forEach
            if (open == null) {
                open = token
            } else {
                val start = requireNotNull(open)
                if (token.start > start.endExclusive) {
                    result += TextSpan(start.endExclusive, token.start)
                }
                open = null
            }
        }
        return result
    }

    private fun quoteCoverage(quote: TextSpan, clause: TextSpan): Double {
        val overlapStart = maxOf(quote.start, clause.start)
        val overlapEnd = minOf(quote.endExclusive, clause.endExclusive)
        if (overlapEnd <= overlapStart) return 0.0
        return (overlapEnd - overlapStart).toDouble() /
            (clause.endExclusive - clause.start).toDouble()
    }

    private fun act(
        type: SpeechActType,
        confidence: Double,
        span: TextSpan,
        source: String,
        detail: String,
    ): SpeechAct = SpeechAct(
        type = type,
        confidence = confidence,
        evidence = listOf(SemanticEvidence(source, detail, confidence, span)),
        span = span,
    )

    companion object {
        val DIRECT_COMMAND_VERBS = setOf(
            "erstelle", "erzeuge", "generiere", "zeichne", "render", "rendere", "create", "generate", "draw",
            "ändere", "aendere", "bearbeite", "edit", "change",
            "suche", "finde", "recherchiere", "search", "find", "research", "lookup",
            "sende", "schicke", "schick", "teile", "share", "send", "reply", "antworte",
            "erinnere", "plane", "schedule", "remind",
            "merke", "speichere", "remember", "store", "save",
            "baue", "implementiere", "entwickle", "programmiere", "build", "implement", "develop", "code",
            "lösche", "loesche", "delete", "entferne", "remove",
            "lade", "upload", "hochladen",
            "überweise", "ueberweise", "zahle", "pay", "transfer",
            "weiter", "fortsetzen", "continue", "proceed",
        )
        private val QUESTION_WORDS = setOf(
            "wie", "warum", "wieso", "was", "wer", "wen", "wem", "wo", "wohin", "wann", "welche", "welcher",
            "how", "why", "what", "who", "where", "when", "which",
        )
        private val QUESTION_AUXILIARIES = setOf(
            "ist", "sind", "hat", "haben", "kann", "können", "koennen", "darf", "soll",
            "is", "are", "do", "does", "did", "can", "could", "would", "should",
        )
        private val REQUEST_AUXILIARIES = setOf(
            "kannst", "könntest", "koenntest", "würdest", "wuerdest", "bitte",
            "could", "would", "please",
        )
        private val POLITENESS_MARKERS = setOf("bitte", "please")
        private val HYPOTHETICAL_MARKERS = setOf(
            "wenn", "falls", "sofern", "angenommen", "hypothetisch", "würde", "wuerde", "könnte", "koennte",
            "if", "unless", "assuming", "hypothetically", "would", "could",
        )
        private val CORRECTION_MARKERS = setOf("sondern", "stattdessen", "korrektur", "rather", "instead")
        private val GREETINGS = setOf("hallo", "hi", "hey", "moin", "servus", "hello")
        private val ACKNOWLEDGEMENTS = setOf("ok", "okay", "verstanden", "danke", "thanks", "merci")
        private val GREETING_PHRASES = setOf("guten morgen", "guten tag", "guten abend", "good morning", "good evening")
        private val ACKNOWLEDGEMENT_PHRASES = setOf("alles klar", "vielen dank", "thank you")
        private val CLAUSE_LEADING_CUES = setOf(
            "und", "oder", "aber", "danach", "anschließend", "anschliessend",
            "and", "or", "but", "then",
        )
        private val QUOTE_MARKERS = setOf("\"", "„", "“", "”", "«", "»")
    }
}
