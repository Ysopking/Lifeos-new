package app.lifeos.core.language

/** Deterministic utterance/clause speech-act classification. Intent evidence is deliberately absent. */
class SpeechActParser(
    private val syntaxAnalyzer: ClauseSyntaxAnalyzer = ClauseSyntaxAnalyzer(),
) {
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
        val syntax = syntaxAnalyzer.analyze(utterance, clause)
        val words = syntax.words
        val semanticWords = syntax.semanticWords
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
        if (words.firstOrNull() in GREETINGS || lower in GREETING_PHRASES) {
            return act(SpeechActType.GREETING, 0.98, span, "social-form", "greeting")
        }
        if (words.firstOrNull() in ACKNOWLEDGEMENTS || lower in ACKNOWLEDGEMENT_PHRASES) {
            return act(SpeechActType.ACKNOWLEDGEMENT, 0.98, span, "social-form", "acknowledgement")
        }
        if (words.any { it in CORRECTION_MARKERS }) {
            return act(SpeechActType.CORRECTION, 0.90, span, "correction-cue", "contrast/correction cue")
        }

        if (syntax.addressedRequest) return act(
            SpeechActType.REQUEST,
            if (syntax.politeImperative) 0.98 else 0.96,
            span,
            "syntax-request",
            "addressed modal or polite imperative request",
        )

        if (syntax.question) return act(
            SpeechActType.QUESTION,
            if (syntax.explicitQuestionMark) 0.99 else 0.94,
            span,
            "syntax-question",
            when {
                syntax.explicitQuestionMark -> "terminal question punctuation outside clause span"
                syntax.interrogativeLead -> "interrogative clause lead"
                else -> "clause-initial auxiliary inversion"
            },
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

        if (syntax.imperativeLead) return act(
            SpeechActType.COMMAND,
            0.96,
            span,
            "syntax-imperative",
            "command verb at clause head",
        )

        return act(
            SpeechActType.ASSERTION,
            0.78,
            span,
            "declarative-default",
            "no question/request/command boundary matched",
        )
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
        private val HYPOTHETICAL_MARKERS = setOf(
            "wenn", "falls", "sofern", "angenommen", "hypothetisch", "würde", "wuerde", "könnte", "koennte",
            "if", "unless", "assuming", "hypothetically", "would", "could",
        )
        private val CORRECTION_MARKERS = setOf("sondern", "stattdessen", "korrektur", "rather", "instead")
        private val GREETINGS = setOf("hallo", "hi", "hey", "moin", "servus", "hello")
        private val ACKNOWLEDGEMENTS = setOf("ok", "okay", "verstanden", "danke", "thanks", "merci")
        private val GREETING_PHRASES = setOf("guten morgen", "guten tag", "guten abend", "good morning", "good evening")
        private val ACKNOWLEDGEMENT_PHRASES = setOf("alles klar", "vielen dank", "thank you")
        private val QUOTE_MARKERS = setOf("\"", "„", "“", "”", "«", "»")
    }
}
