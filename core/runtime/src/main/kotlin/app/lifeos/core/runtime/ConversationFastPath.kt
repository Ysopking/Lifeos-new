package app.lifeos.core.runtime

enum class ConversationPath { FAST_CHAT, COGNITIVE, ARTIFACT, AGENCY }

data class ConversationRouteSignals(
    val requiresMemory: Boolean = false,
    val requiresMatter: Boolean = false,
    val requestsArtifact: Boolean = false,
    val requestsExternalEffect: Boolean = false,
)

/** Cheap deterministic gate: only turns needing world state pay the cognitive cost. */
class ConversationFastPath {
    fun route(signals: ConversationRouteSignals): ConversationPath = when {
        signals.requestsExternalEffect -> ConversationPath.AGENCY
        signals.requestsArtifact -> ConversationPath.ARTIFACT
        signals.requiresMatter || signals.requiresMemory -> ConversationPath.COGNITIVE
        else -> ConversationPath.FAST_CHAT
    }
}


data class ConversationFastPathDecision(
    val path: ConversationPath,
    val signals: ConversationRouteSignals,
    val reason: String,
) {
    init { require(reason.isNotBlank()) }
}

data class FastConversationContext(
    val conversationId: String,
    val recentTurnCount: Int,
    val lastUserText: String?,
) {
    init {
        require(conversationId.isNotBlank())
        require(recentTurnCount >= 0)
    }
}

/**
 * Conservative classifier. FAST_CHAT is selected only for clearly social/local turns.
 * Any memory, life-matter, artifact, scheduling, communication or action cue escalates.
 */
class ConversationSignalClassifier {
    fun classify(
        text: String,
        tags: Set<String> = emptySet(),
    ): ConversationFastPathDecision {
        val normalized = text.trim().lowercase()
        val signals = ConversationRouteSignals(
            requiresMemory = MEMORY_CUES.any(normalized::contains) ||
                tags.any { it.startsWith("memory") || it.startsWith("context:goal") },
            requiresMatter = MATTER_CUES.any(normalized::contains) ||
                tags.any { it.startsWith("life-matter") || it.startsWith("legal") || it.startsWith("debt") },
            requestsArtifact = ARTIFACT_CUES.any(normalized::contains) ||
                tags.any { it.startsWith("artifact") || it.startsWith("image") || it.startsWith("document") },
            requestsExternalEffect = AGENCY_CUES.any(normalized::contains) ||
                tags.any { it.startsWith("agency") || it.startsWith("external-effect") },
        )
        val path = when {
            signals.requestsExternalEffect -> ConversationPath.AGENCY
            signals.requestsArtifact -> ConversationPath.ARTIFACT
            signals.requiresMatter || signals.requiresMemory -> ConversationPath.COGNITIVE
            isClearlyFastConversation(normalized) -> ConversationPath.FAST_CHAT
            else -> ConversationPath.COGNITIVE
        }
        val reason = when (path) {
            ConversationPath.AGENCY -> "external-effect-cue"
            ConversationPath.ARTIFACT -> "artifact-cue"
            ConversationPath.COGNITIVE -> if (signals.requiresMatter || signals.requiresMemory) {
                "world-context-required"
            } else {
                "conservative-escalation"
            }
            ConversationPath.FAST_CHAT -> "local-social-turn"
        }
        return ConversationFastPathDecision(path, signals, reason)
    }

    private fun isClearlyFastConversation(text: String): Boolean {
        if (text.isBlank()) return false
        if (text.length > 160) return false
        return FAST_PATTERNS.any { it.matches(text) }
    }

    private companion object {
        val FAST_PATTERNS = listOf(
            Regex("""^(hi|hallo|hey|moin|servus|guten (morgen|tag|abend))[!.? ]*$"""),
            Regex("""^(danke|vielen dank|merci|thanks|thank you)[!.? ]*$"""),
            Regex("""^(ok|okay|alles klar|verstanden|passt|gut)[!.? ]*$"""),
            Regex("""^(wie geht'?s|wie geht es dir|how are you)[?.! ]*$"""),
        )
        val MEMORY_CUES = listOf(
            "erinner", "weißt du noch", "weiss du noch", "vorhin", "letztes mal",
            "mein ziel", "mein projekt", "meine schulden", "mein termin",
        )
        val MATTER_CUES = listOf(
            "behörde", "amt", "schuld", "forderung", "vertrag", "frist", "recht",
            "versicherung", "rechnung", "zahlung", "bescheid",
        )
        val ARTIFACT_CUES = listOf(
            "erstelle", "mach ein bild", "generiere", "dokument", "pdf", "datei",
            "tabelle", "präsentation", "presentation", "bild", "grafik",
        )
        val AGENCY_CUES = listOf(
            "sende", "schick", "verschicke", "buche", "bestelle", "plane einen termin",
            "erinnere mich", "schedule", "email", "nachricht an",
        )
    }
}
