package app.lifeos.next.kernel

import app.lifeos.core.runtime.goal.LocalSharePreparation

sealed interface LocalCommunicationExecutionResult {
    data class Prepared(val share: LocalSharePreparation) : LocalCommunicationExecutionResult

    data class Blocked(val reason: String) : LocalCommunicationExecutionResult {
        init { require(reason.isNotBlank()) }
    }

    data class Failed(val message: String) : LocalCommunicationExecutionResult {
        init { require(message.isNotBlank()) }
    }
}
