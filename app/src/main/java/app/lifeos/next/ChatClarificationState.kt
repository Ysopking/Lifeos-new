package app.lifeos.next

import app.lifeos.next.kernel.ConversationTurnResult
import app.lifeos.next.ui.chat.ChatClarificationPolicy

internal fun LifeOsChatUiState.withClarificationFrom(
    turn: ConversationTurnResult,
): LifeOsChatUiState {
    val clarification = turn.language?.understanding?.goal?.clarification
    return copy(
        clarificationOptions = ChatClarificationPolicy.options(
            required = clarification?.required == true,
            alternatives = clarification?.alternatives.orEmpty(),
        )
    )
}
