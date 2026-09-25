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
    val availability: RuntimeAvailability,
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
        get() = phase == LifeOsProcessStartupPhase.READY &&
            availability in setOf(RuntimeAvailability.FULL, RuntimeAvailability.DEGRADED)

    val usable: Boolean
        get() = availability != RuntimeAvailability.RECOVERY

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
        ): LifeOsProcessStartupState {
            require(
                availability == RuntimeAvailability.FULL ||
                    availability == RuntimeAvailability.DEGRADED
            ) {
                "Ready process state requires FULL or DEGRADED availability"
            }
            return LifeOsProcessStartupState(
                phase = LifeOsProcessStartupPhase.READY,
                stage = if (availability == RuntimeAvailability.DEGRADED) {
                    "Runtime eingeschränkt bereit"
                } else {
                    "Runtime bereit"
                },
                availability = availability,
            )
        }

        fun restricted(
            availability: RuntimeAvailability,
            message: String,
        ): LifeOsProcessStartupState {
            require(
                availability == RuntimeAvailability.READ_ONLY ||
                    availability == RuntimeAvailability.SAFE_MODE
            )
            return LifeOsProcessStartupState(
                phase = LifeOsProcessStartupPhase.FAILED,
                stage = if (availability == RuntimeAvailability.READ_ONLY) {
                    "Nur-Lese-Modus"
                } else {
                    "Sicherer Modus"
                },
                availability = availability,
                failure = message.ifBlank { "Runtime ist nur eingeschränkt verfügbar" },
            )
        }

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
