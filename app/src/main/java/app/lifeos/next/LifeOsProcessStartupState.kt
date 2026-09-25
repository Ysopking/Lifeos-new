package app.lifeos.next

import kotlinx.coroutines.flow.StateFlow

enum class LifeOsProcessStartupPhase {
    STARTING,
    READY,
    FAILED,
}

data class LifeOsProcessStartupState(
    val phase: LifeOsProcessStartupPhase,
    val stage: String,
    val stageId: String? = null,
    val diagnosticCode: String? = null,
    val durationMillis: Long? = null,
    val failure: String? = null,
) {
    init {
        require(stage.isNotBlank())
        require(stageId == null || stageId.isNotBlank())
        require(diagnosticCode == null || diagnosticCode.isNotBlank())
        require(durationMillis == null || durationMillis >= 0L)
        require(failure == null || failure.isNotBlank())
    }

    val ready: Boolean
        get() = phase == LifeOsProcessStartupPhase.READY

    companion object {
        fun starting(stage: String = "LIFEOS wird gestartet") = LifeOsProcessStartupState(
            phase = LifeOsProcessStartupPhase.STARTING,
            stage = stage,
        )

        internal fun stageStarted(stage: LifeOsStartupStage) = LifeOsProcessStartupState(
            phase = LifeOsProcessStartupPhase.STARTING,
            stage = "→ ${stage.displayName} …",
            stageId = stage.name,
            diagnosticCode = stage.diagnosticCode,
        )

        internal fun stageCompleted(
            event: LifeOsStartupStageEvent.Completed,
        ) = LifeOsProcessStartupState(
            phase = LifeOsProcessStartupPhase.STARTING,
            stage = "✓ ${event.stage.displayName}",
            stageId = event.stage.name,
            diagnosticCode = event.stage.diagnosticCode,
            durationMillis = event.durationMillis,
        )

        internal fun stageFailed(
            event: LifeOsStartupStageEvent.Failed,
        ) = LifeOsProcessStartupState(
            phase = LifeOsProcessStartupPhase.FAILED,
            stage = "Start eingeschränkt",
            stageId = event.stage.name,
            diagnosticCode = event.diagnosticCode,
            durationMillis = event.durationMillis,
            failure =
                "${event.stage.displayName} konnte nicht gestartet werden. " +
                    event.message,
        )

        fun ready() = LifeOsProcessStartupState(
            phase = LifeOsProcessStartupPhase.READY,
            stage = "Runtime bereit",
        )

        fun failed(message: String) = LifeOsProcessStartupState(
            phase = LifeOsProcessStartupPhase.FAILED,
            stage = "LIFEOS konnte nicht gestartet werden",
            failure = message.ifBlank { "Unbekannter Startfehler" },
        )
    }
}

interface LifeOsProcessStartupStateReader {
    val startupState: StateFlow<LifeOsProcessStartupState>
}
