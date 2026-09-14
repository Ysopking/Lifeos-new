package app.lifeos.core.language

enum class LanguageResponseAct {
    ASSERT,
    EVIDENCE,
    UNCERTAINTY,
    REPORT_SUCCESS,
    REPORT_BLOCKED,
    REPORT_FAILURE,
}

data class LanguageResponseFact(
    val statement: String,
    val semanticTags: Set<String> = emptySet(),
    val confidence: Double = 1.0,
    val semanticGraph: LanguageSemanticGraph? = null,
) {
    init {
        require(statement.isNotBlank())
        require(semanticTags.none { it.isBlank() })
        require(confidence.isFinite() && confidence in 0.0..1.0)
    }
}

data class LanguageResponseTarget(
    val act: LanguageResponseAct,
    val language: LanguageCode,
    val facts: List<LanguageResponseFact>,
    val confidence: Double = facts.map { it.confidence }.averageOrOne(),
) {
    init {
        require(facts.isNotEmpty())
        require(confidence.isFinite() && confidence in 0.0..1.0)
    }
}

data class LanguageResponseCandidate(
    val text: String,
    val roundTrip: LanguageUnderstandingResult,
    val semanticPreservation: Double,
    val factCoverage: Double,
    val semanticCoverage: Double,
    val semanticGraphCoverage: Double,
    val actCuePreserved: Boolean,
) {
    init {
        require(text.isNotBlank())
        require(semanticPreservation.isFinite() && semanticPreservation in 0.0..1.0)
        require(factCoverage.isFinite() && factCoverage in 0.0..1.0)
        require(semanticCoverage.isFinite() && semanticCoverage in 0.0..1.0)
        require(semanticGraphCoverage.isFinite() && semanticGraphCoverage in 0.0..1.0)
    }
}

data class LanguageResponseGenerationResult(
    val text: String,
    val target: LanguageResponseTarget,
    val winner: LanguageResponseCandidate,
    val alternatives: List<LanguageResponseCandidate>,
) {
    init {
        require(text == winner.text)
        require(alternatives.none { it.text == winner.text })
    }
}

/**
 * Top-down response realization for LIFEOS.
 *
 * Facts/evidence remain the authority. The realizer may add only bounded discourse words around the
 * supplied statements. Every factual statement is normalized into the same canonical semantic graph
 * used by [LanguageUnderstandingEngine], unless the caller already supplies that graph. Every output
 * candidate is then parsed through the productive understanding engine and ranked by graph round-trip
 * preservation as well as factual/field coverage.
 *
 * CONVERSATION is intentionally different from factual acts. The productive composer supplies the
 * current social utterance as a semantic conversation cue, not as an external-world fact that must
 * be repeated verbatim. The realizer therefore answers greetings, courtesy and short check-ins with
 * bounded conversational surfaces while still round-tripping every candidate through understanding.
 */
class LanguageResponseGenerationEngine(
    private val understanding: LanguageUnderstandingEngine = LanguageUnderstandingEngine(),
    private val maximumCandidates: Int = 12,
) {
    init { require(maximumCandidates in 1..64) }

    fun generate(
        target: LanguageResponseTarget,
        context: LanguageContext = LanguageContext(),
    ): LanguageResponseGenerationResult {
        val proposed = propose(target)
            .map(::normalizeSurface)
            .filter { it.isNotBlank() }
            .distinct()
            .take(maximumCandidates)
        require(proposed.isNotEmpty()) { "Language response generation produced no candidate" }

        val ranked = proposed.map { text -> evaluate(target, text, context) }
            .sortedWith(
                compareByDescending<LanguageResponseCandidate> {
                    target.act == LanguageResponseAct.ASSERT || it.actCuePreserved
                }
                    .thenByDescending { it.semanticPreservation }
                    .thenByDescending { it.semanticGraphCoverage }
                    .thenByDescending { it.roundTrip.goal.confidence }
                    .thenBy { it.text.length }
                    .thenBy { it.text }
            )
        val winner = ranked.first()
        return LanguageResponseGenerationResult(
            text = winner.text,
            target = target,
            winner = winner,
            alternatives = ranked.drop(1).take(4),
        )
    }

    private fun evaluate(
        target: LanguageResponseTarget,
        text: String,
        context: LanguageContext,
    ): LanguageResponseCandidate {
        val roundTrip = understanding.understand(text, context)
        val factCoverage = factCoverage(target.facts, text)
        val semanticCoverage = semanticCoverage(target.facts, roundTrip)
        val graphCoverage = responseSemanticGraphCoverage(target, roundTrip)
        val actCuePreserved = actCuePreserved(target.act, text, target.language)
        val languagePreserved = target.language == LanguageCode.UNKNOWN || roundTrip.goal.language == target.language
        val confidenceAgreement = 1.0 - kotlin.math.abs(target.confidence - roundTrip.goal.confidence)
        val score = if (isConversationTarget(target)) {
            val prompt = conversationPrompt(target.facts)
            val fit = conversationFit(prompt, text, target.language)
            val surfaceQuality = conversationSurfaceQuality(text)
            (
                fit * 0.64 +
                    surfaceQuality * 0.18 +
                    (if (languagePreserved) 0.10 else 0.0) +
                    confidenceAgreement.coerceIn(0.0, 1.0) * 0.08
                ).coerceIn(0.0, 1.0)
        } else {
            (
                factCoverage * 0.40 +
                    semanticCoverage * 0.20 +
                    graphCoverage * 0.24 +
                    (if (actCuePreserved) 0.07 else 0.0) +
                    (if (languagePreserved) 0.05 else 0.0) +
                    confidenceAgreement.coerceIn(0.0, 1.0) * 0.04
                ).coerceIn(0.0, 1.0)
        }
        return LanguageResponseCandidate(
            text = text,
            roundTrip = roundTrip,
            semanticPreservation = score,
            factCoverage = factCoverage,
            semanticCoverage = semanticCoverage,
            semanticGraphCoverage = graphCoverage,
            actCuePreserved = actCuePreserved,
        )
    }

    private fun responseSemanticGraphCoverage(
        target: LanguageResponseTarget,
        roundTrip: LanguageUnderstandingResult,
    ): Double {
        if (isConversationTarget(target)) return 1.0
        val expected = target.facts.map { fact ->
            fact.semanticGraph ?: understanding.understand(fact.statement).goal.semanticGraph
        }
        if (expected.isEmpty()) return 1.0
        return expected.map { semanticGraphCoverage(it, roundTrip.goal.semanticGraph) }.average()
    }

    private fun factCoverage(facts: List<LanguageResponseFact>, text: String): Double {
        val actual = contentTerms(text)
        val expected = facts.flatMapTo(sortedSetOf()) { contentTerms(it.statement) }
        if (expected.isEmpty()) return 1.0
        return expected.count(actual::contains).toDouble() / expected.size.toDouble()
    }

    private fun semanticCoverage(
        facts: List<LanguageResponseFact>,
        roundTrip: LanguageUnderstandingResult,
    ): Double {
        val expected = facts.flatMapTo(sortedSetOf()) { fact -> fact.semanticTags.map { it.uppercase() } }
        if (expected.isEmpty()) return 1.0
        val field = roundTrip.linguisticField ?: return 0.0
        return expected.map { tag -> field.semanticActivation(tag).coerceIn(0.0, 1.0) }.average()
    }

    private fun propose(target: LanguageResponseTarget): List<String> {
        val language = if (target.language == LanguageCode.UNKNOWN) LanguageCode.DE else target.language
        if (isConversationTarget(target)) {
            val prompt = conversationPrompt(target.facts)
            return when (language) {
                LanguageCode.DE -> germanConversationCandidates(prompt)
                LanguageCode.EN -> englishConversationCandidates(prompt)
                LanguageCode.UNKNOWN -> error("resolved above")
            }
        }
        val facts = target.facts.joinToString(" ") { sentence(it.statement) }
        return when (language) {
            LanguageCode.DE -> germanCandidates(target.act, facts)
            LanguageCode.EN -> englishCandidates(target.act, facts)
            LanguageCode.UNKNOWN -> error("resolved above")
        }
    }

    private fun germanCandidates(act: LanguageResponseAct, facts: String): List<String> = when (act) {
        LanguageResponseAct.ASSERT -> listOf(facts, "Ergebnis: $facts")
        LanguageResponseAct.EVIDENCE -> listOf(
            "Die Evidenz zeigt: $facts",
            "Auf Basis der Evidenz: $facts",
            facts,
        )
        LanguageResponseAct.UNCERTAINTY -> listOf(
            "Die Evidenz ist noch nicht eindeutig: $facts",
            "Unsicher ist derzeit: $facts",
            "Vorläufiges Ergebnis: $facts",
        )
        LanguageResponseAct.REPORT_SUCCESS -> listOf("Erfolgreich: $facts", "Ergebnis: $facts", facts)
        LanguageResponseAct.REPORT_BLOCKED -> listOf("Blockiert: $facts", "Nicht ausführbar: $facts", facts)
        LanguageResponseAct.REPORT_FAILURE -> listOf("Fehlgeschlagen: $facts", "Fehler: $facts", facts)
    }

    private fun englishCandidates(act: LanguageResponseAct, facts: String): List<String> = when (act) {
        LanguageResponseAct.ASSERT -> listOf(facts, "Result: $facts")
        LanguageResponseAct.EVIDENCE -> listOf("The evidence shows: $facts", "Based on the evidence: $facts", facts)
        LanguageResponseAct.UNCERTAINTY -> listOf(
            "The evidence is not conclusive yet: $facts",
            "Current uncertainty: $facts",
            "Preliminary result: $facts",
        )
        LanguageResponseAct.REPORT_SUCCESS -> listOf("Successful: $facts", "Result: $facts", facts)
        LanguageResponseAct.REPORT_BLOCKED -> listOf("Blocked: $facts", "Not executable: $facts", facts)
        LanguageResponseAct.REPORT_FAILURE -> listOf("Failed: $facts", "Error: $facts", facts)
    }

    private fun germanConversationCandidates(prompt: String): List<String> = when (conversationKind(prompt, LanguageCode.DE)) {
        ConversationKind.GREETING -> listOf("Hallo!", "Hi!", "Hey!")
        ConversationKind.THANKS -> listOf("Gern!", "Sehr gern!", "Gern geschehen!")
        ConversationKind.CHECK_IN -> listOf(
            "Danke der Nachfrage. Ich bin bereit.",
            "Ich bin bereit. Womit machen wir weiter?",
            "Bereit – womit möchtest du weitermachen?",
        )
        ConversationKind.GENERAL -> listOf(
            "Ich höre zu.",
            "Verstanden. Erzähl gern weiter.",
            "Okay. Wir können daran anknüpfen.",
        )
    }

    private fun englishConversationCandidates(prompt: String): List<String> = when (conversationKind(prompt, LanguageCode.EN)) {
        ConversationKind.GREETING -> listOf("Hello!", "Hi!", "Hey!")
        ConversationKind.THANKS -> listOf("You're welcome!", "Gladly!", "Of course!")
        ConversationKind.CHECK_IN -> listOf(
            "Thanks for asking. I'm ready.",
            "I'm ready. What should we continue with?",
            "Ready — what would you like to do next?",
        )
        ConversationKind.GENERAL -> listOf(
            "I'm listening.",
            "Understood. Go ahead.",
            "Okay. We can continue from there.",
        )
    }

    private fun isConversationTarget(target: LanguageResponseTarget): Boolean =
        target.facts.any { fact -> fact.semanticTags.any { it.equals(CONVERSATION_TAG, ignoreCase = true) } }

    private fun conversationPrompt(facts: List<LanguageResponseFact>): String {
        val statement = facts.firstOrNull()?.statement?.trim().orEmpty()
        if (statement.isBlank()) return ""
        val lower = statement.lowercase()
        val germanMarker = "semantisch erfasst:"
        val englishMarker = "captured semantically:"
        return when {
            lower.contains(germanMarker) -> statement.substring(lower.indexOf(germanMarker) + germanMarker.length).trim()
            lower.contains(englishMarker) -> statement.substring(lower.indexOf(englishMarker) + englishMarker.length).trim()
            lower.startsWith("der gesprächskontext ist aktiv") -> ""
            lower.startsWith("the conversation context is active") -> ""
            else -> statement
        }
    }

    private fun conversationKind(prompt: String, language: LanguageCode): ConversationKind {
        val normalized = prompt.lowercase().trim().trimEnd('.', '!', '?', ';', ':')
        if (normalized.isBlank()) return ConversationKind.GENERAL
        return when (language) {
            LanguageCode.DE -> when {
                normalized.startsWith("hallo") || normalized.startsWith("hi") || normalized.startsWith("hey") ||
                    normalized.startsWith("moin") || normalized.startsWith("servus") ||
                    normalized.startsWith("guten morgen") || normalized.startsWith("guten tag") ||
                    normalized.startsWith("guten abend") -> ConversationKind.GREETING
                normalized.contains("danke") || normalized.contains("dankeschön") ||
                    normalized.contains("dankeschoen") -> ConversationKind.THANKS
                normalized.contains("wie geht") -> ConversationKind.CHECK_IN
                else -> ConversationKind.GENERAL
            }
            LanguageCode.EN -> when {
                normalized.startsWith("hello") || normalized.startsWith("hi") || normalized.startsWith("hey") ||
                    normalized.startsWith("good morning") || normalized.startsWith("good afternoon") ||
                    normalized.startsWith("good evening") -> ConversationKind.GREETING
                normalized.contains("thank you") || normalized.contains("thanks") -> ConversationKind.THANKS
                normalized.contains("how are you") -> ConversationKind.CHECK_IN
                else -> ConversationKind.GENERAL
            }
            LanguageCode.UNKNOWN -> ConversationKind.GENERAL
        }
    }

    private fun conversationFit(prompt: String, text: String, language: LanguageCode): Double {
        val normalized = text.lowercase()
        return when (conversationKind(prompt, language)) {
            ConversationKind.GREETING -> if (
                listOf("hallo", "hi", "hey", "hello").any(normalized::contains)
            ) 1.0 else 0.25
            ConversationKind.THANKS -> if (
                listOf("gern", "welcome", "gladly", "of course").any(normalized::contains)
            ) 1.0 else 0.25
            ConversationKind.CHECK_IN -> if (
                listOf("bereit", "ready").any(normalized::contains)
            ) 1.0 else 0.35
            ConversationKind.GENERAL -> if (
                listOf("höre zu", "erzähl", "anknüpfen", "listening", "go ahead", "continue").any(normalized::contains)
            ) 1.0 else 0.50
        }
    }

    private fun conversationSurfaceQuality(text: String): Double {
        val lower = text.lowercase()
        val internalLeak = INTERNAL_CONVERSATION_TERMS.any(lower::contains)
        val lengthScore = when {
            text.length in 2..120 -> 1.0
            text.length <= 180 -> 0.75
            else -> 0.45
        }
        return if (internalLeak) lengthScore * 0.20 else lengthScore
    }

    private fun actCuePreserved(
        act: LanguageResponseAct,
        text: String,
        language: LanguageCode,
    ): Boolean {
        if (act == LanguageResponseAct.ASSERT) return true
        val normalized = text.lowercase()
        val cues = when (act) {
            LanguageResponseAct.ASSERT -> emptySet()
            LanguageResponseAct.EVIDENCE -> if (language == LanguageCode.EN) {
                setOf("evidence", "based on")
            } else {
                setOf("evidenz", "basis")
            }
            LanguageResponseAct.UNCERTAINTY -> if (language == LanguageCode.EN) {
                setOf("not conclusive", "uncertainty", "preliminary")
            } else {
                setOf("nicht eindeutig", "unsicher", "vorläufig")
            }
            LanguageResponseAct.REPORT_SUCCESS -> if (language == LanguageCode.EN) {
                setOf("successful", "result")
            } else {
                setOf("erfolgreich", "ergebnis")
            }
            LanguageResponseAct.REPORT_BLOCKED -> if (language == LanguageCode.EN) {
                setOf("blocked", "not executable")
            } else {
                setOf("blockiert", "nicht ausführbar")
            }
            LanguageResponseAct.REPORT_FAILURE -> if (language == LanguageCode.EN) {
                setOf("failed", "error")
            } else {
                setOf("fehlgeschlagen", "fehler")
            }
        }
        return cues.any(normalized::contains)
    }

    private fun contentTerms(value: String): Set<String> = TERM.findAll(value.lowercase())
        .map { it.value.replace("ß", "ss") }
        .filter { it.length >= 3 && it !in STOP_WORDS }
        .toSortedSet()

    private fun sentence(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return trimmed
        return if (trimmed.last() in ".!?;") trimmed else "$trimmed."
    }

    private fun normalizeSurface(value: String): String = value
        .replace(Regex("\\s+"), " ")
        .replace(" :", ":")
        .trim()

    private enum class ConversationKind {
        GREETING,
        THANKS,
        CHECK_IN,
        GENERAL,
    }

    private companion object {
        const val CONVERSATION_TAG = "CONVERSATION"
        val INTERNAL_CONVERSATION_TERMS = setOf(
            "gesprächskontext",
            "conversation context",
            "semantisch erfasst",
            "captured semantically",
            "lifeos-photon",
            "intent:",
        )
        val TERM = Regex("[\\p{L}\\p{N}_-]+")
        val STOP_WORDS = setOf(
            "aber", "als", "auf", "aus", "bei", "das", "dass", "der", "die", "ein", "eine", "einer",
            "für", "hat", "ist", "mit", "oder", "und", "von", "war", "wie", "wird", "zu", "zum", "zur",
            "and", "are", "for", "from", "has", "have", "into", "the", "this", "that", "was", "were", "with",
        )
    }
}

private fun List<Double>.averageOrOne(): Double = if (isEmpty()) 1.0 else average()
