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
