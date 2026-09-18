package app.lifeos.next.kernel

import app.lifeos.core.runtime.ConversationFastPathDecision

data class ConversationTurnResult(
    val route: ConversationFastPathDecision,
    val responseText: String,
    val source: PhotonSubmissionResult,
    val assistant: PhotonSubmissionResult,
    val language: LanguageSubmissionResult? = null,
) {
    val fastPath: Boolean
        get() = route.path == app.lifeos.core.runtime.ConversationPath.FAST_CHAT
}
