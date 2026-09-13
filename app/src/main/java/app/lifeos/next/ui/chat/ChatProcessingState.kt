package app.lifeos.next.ui.chat

import app.lifeos.next.kernel.KernelBootstrapStatus

enum class ChatTurnPhase {
    IDLE,
    SUBMITTING,
    PERSISTING_RESPONSE,
    FAILED,
}

data class ChatTurnProcessingState(
    val phase: ChatTurnPhase = ChatTurnPhase.IDLE,
    val turnId: String? = null,
    val userTurnPersisted: Boolean = false,
    val failureMessage: String? = null,
) {
    init {
        when (phase) {
            ChatTurnPhase.IDLE -> {
                require(turnId == null)
                require(!userTurnPersisted)
                require(failureMessage == null)
            }
            ChatTurnPhase.SUBMITTING -> {
                require(!turnId.isNullOrBlank())
                require(!userTurnPersisted)
                require(failureMessage == null)
            }
            ChatTurnPhase.PERSISTING_RESPONSE -> {
                require(!turnId.isNullOrBlank())
                require(userTurnPersisted)
                require(failureMessage == null)
            }
            ChatTurnPhase.FAILED -> {
                require(!turnId.isNullOrBlank())
                require(!failureMessage.isNullOrBlank())
            }
        }
    }

    val inFlight: Boolean
        get() = phase == ChatTurnPhase.SUBMITTING || phase == ChatTurnPhase.PERSISTING_RESPONSE

    companion object {
        fun idle(): ChatTurnProcessingState = ChatTurnProcessingState()

        fun submitting(turnId: String): ChatTurnProcessingState = ChatTurnProcessingState(
            phase = ChatTurnPhase.SUBMITTING,
            turnId = turnId,
        )

        fun persistingResponse(turnId: String): ChatTurnProcessingState = ChatTurnProcessingState(
            phase = ChatTurnPhase.PERSISTING_RESPONSE,
            turnId = turnId,
            userTurnPersisted = true,
        )

        fun failed(
            turnId: String,
            userTurnPersisted: Boolean,
            message: String,
        ): ChatTurnProcessingState = ChatTurnProcessingState(
            phase = ChatTurnPhase.FAILED,
            turnId = turnId,
            userTurnPersisted = userTurnPersisted,
            failureMessage = message,
        )
    }
}

object ChatComposerPolicy {
    fun canSend(
        draft: String,
        bootStatus: KernelBootstrapStatus,
        processing: ChatTurnProcessingState,
    ): Boolean = draft.isNotBlank() &&
        !processing.inFlight &&
        (bootStatus == KernelBootstrapStatus.READY || bootStatus == KernelBootstrapStatus.DEGRADED)

    fun statusLabel(processing: ChatTurnProcessingState): String? = when (processing.phase) {
        ChatTurnPhase.IDLE -> null
        ChatTurnPhase.SUBMITTING -> "Nachricht wird lokal gespeichert und verarbeitet …"
        ChatTurnPhase.PERSISTING_RESPONSE -> "Antwort wird lokal gespeichert …"
        ChatTurnPhase.FAILED -> if (processing.userTurnPersisted) {
            "Nachricht ist gespeichert; die Antwortverarbeitung ist fehlgeschlagen."
        } else {
            "Nachricht konnte nicht gespeichert werden."
        }
    }
}
