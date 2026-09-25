package app.lifeos.next

import app.lifeos.core.runtime.boot.RuntimeAvailability
import kotlinx.coroutines.flow.StateFlow

enum class LifeOsProcessStartupPhase {
    STARTING,
    READY,
    FAILED,
}

data class LifeOsProcessStartupState(
    val phase: LifeOsProcessStartupPhase,
    val stage: String,
    val availability: RuntimeAvailability = RuntimeAvailability.RECOVERY,
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

    val usable: Boolean
        get() =
            ready &&
                (
                    availability == RuntimeAvailability.FULL ||
                        availability == RuntimeAvailability.DEGRADED ||
                        availability == RuntimeAvailability.READ_ONLY
                    )

    val readable: Boolean
        get() = usable

    val writable: Boolean
        get() =
            ready &&
                (
                    availability == RuntimeAvailability.FULL ||
                        availability == RuntimeAvailability.DEGRADED
                    )

    val actionable: Boolean
        get() = writable

    companion object {
        fun starting(stage: String = "LIFEOS wird gestartet") = LifeOsProcessStartupState(
            phase = LifeOsProcessStartupPhase.STARTING,
            stage = stage,
            availability = RuntimeAvailability.RECOVERY,
        )

        internal fun stageStarted(stage: LifeOsStartupStage) = LifeOsProcessStartupState(
            phase = LifeOsProcessStartupPhase.STARTING,
            stage = "→ ${stage.displayName} …",
            availability = RuntimeAvailability.RECOVERY,
            stageId = stage.name,
            diagnosticCode = stage.diagnosticCode,
        )

        internal fun stageCompleted(
            event: LifeOsStartupStageEvent.Completed,
        ) = LifeOsProcessStartupState(
            phase = LifeOsProcessStartupPhase.STARTING,
            stage = "✓ ${event.stage.displayName}",
            availability = RuntimeAvailability.RECOVERY,
            stageId = event.stage.name,
            diagnosticCode = event.stage.diagnosticCode,
            durationMillis = event.durationMillis,
        )

        internal fun stageFailed(
            event: LifeOsStartupStageEvent.Failed,
        ) = LifeOsProcessStartupState(
            phase = LifeOsProcessStartupPhase.FAILED,
            stage = "Start eingeschränkt",
            availability = RuntimeAvailability.SAFE_MODE,
            stageId = event.stage.name,
            diagnosticCode = event.diagnosticCode,
            durationMillis = event.durationMillis,
            failure =
                "${event.stage.displayName} konnte nicht gestartet werden. " +
                    event.message,
        )

        fun ready(
            availability: RuntimeAvailability = RuntimeAvailability.FULL,
        ) = LifeOsProcessStartupState(
            phase = LifeOsProcessStartupPhase.READY,
            stage = when (availability) {
                RuntimeAvailability.FULL -> "Runtime bereit"
                RuntimeAvailability.DEGRADED -> "Runtime eingeschränkt"
                RuntimeAvailability.READ_ONLY -> "Nur-Lesen-Modus"
                RuntimeAvailability.RECOVERY -> "Wiederherstellung"
                RuntimeAvailability.SAFE_MODE -> "Sicherheitsmodus"
            },
            availability = availability,
        )

        fun failed(message: String) = LifeOsProcessStartupState(
            phase = LifeOsProcessStartupPhase.FAILED,
            stage = "LIFEOS konnte nicht gestartet werden",
            availability = RuntimeAvailability.SAFE_MODE,
            failure = message.ifBlank { "Unbekannter Startfehler" },
        )
    }
}

interface LifeOsProcessStartupStateReader {
    val startupState: StateFlow<LifeOsProcessStartupState>
}
