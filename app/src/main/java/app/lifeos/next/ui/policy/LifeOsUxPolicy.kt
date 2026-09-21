package app.lifeos.next.ui.policy

import app.lifeos.next.kernel.InitialCognitiveContextPhase
import app.lifeos.next.kernel.InitialCognitiveContextReadiness
import app.lifeos.next.ui.chat.ChatComposerPolicy
import app.lifeos.next.ui.chat.ChatTurnPhase
import app.lifeos.next.ui.chat.ChatTurnProcessingState
import app.lifeos.next.ui.components.RuntimeHealthLevel
import app.lifeos.next.ui.components.RuntimeHealthUiModel

enum class LifeOsUxPriority {
    QUIET,
    CONTEXT,
    ACTION_REQUIRED,
    BLOCKING,
}

data class LifeOsUxNotice(
    val priority: LifeOsUxPriority,
    val message: String,
)

object LifeOsUxPolicy {
    fun runtimeNotice(model: RuntimeHealthUiModel): LifeOsUxNotice? = when (model.level) {
        RuntimeHealthLevel.STARTING,
        RuntimeHealthLevel.VERIFYING,
        RuntimeHealthLevel.READY -> null

        RuntimeHealthLevel.DEGRADED -> LifeOsUxNotice(
            priority = LifeOsUxPriority.ACTION_REQUIRED,
            message = "Ein Teil von LIFEOS ist eingeschränkt.",
        )

        RuntimeHealthLevel.FAILED -> LifeOsUxNotice(
            priority = LifeOsUxPriority.BLOCKING,
            message = "LIFEOS braucht deine Aufmerksamkeit.",
        )
    }

    fun contextNotice(context: InitialCognitiveContextReadiness): LifeOsUxNotice? = when (context.phase) {
        InitialCognitiveContextPhase.PREPARING,
        InitialCognitiveContextPhase.BUILDING_MEMORY,
        InitialCognitiveContextPhase.READY -> null

        InitialCognitiveContextPhase.WAITING_FOR_PERMISSIONS -> LifeOsUxNotice(
            priority = LifeOsUxPriority.CONTEXT,
            message = "LIFEOS wartet auf deine Datenfreigaben.",
        )

        InitialCognitiveContextPhase.PARTIAL -> {
            val missing = context.unauthorizedSources + context.unavailableSources
            LifeOsUxNotice(
                priority = LifeOsUxPriority.CONTEXT,
                message = if (missing > 0) {
                    "Teilweise bereit · $missing Quelle(n) fehlen."
                } else {
                    "Teilweise bereit."
                },
            )
        }

        InitialCognitiveContextPhase.FAILED -> LifeOsUxNotice(
            priority = LifeOsUxPriority.BLOCKING,
            message = "Dein Kontext ist gerade nicht verfügbar.",
        )
    }

    fun processingNotice(processing: ChatTurnProcessingState): LifeOsUxNotice? =
        if (processing.phase == ChatTurnPhase.FAILED) {
            ChatComposerPolicy.statusLabel(processing)?.let { message ->
                LifeOsUxNotice(
                    priority = LifeOsUxPriority.ACTION_REQUIRED,
                    message = message,
                )
            }
        } else {
            null
        }
}
