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
    val failure: String? = null,
) {
    val ready: Boolean
        get() = phase == LifeOsProcessStartupPhase.READY

    companion object {
        fun starting(stage: String = "BootEngine wird gestartet") = LifeOsProcessStartupState(
            phase = LifeOsProcessStartupPhase.STARTING,
            stage = stage,
        )

        fun ready() = LifeOsProcessStartupState(
            phase = LifeOsProcessStartupPhase.READY,
            stage = "Runtime bereit",
        )

        fun failed(message: String) = LifeOsProcessStartupState(
            phase = LifeOsProcessStartupPhase.FAILED,
            stage = "BootEngine fehlgeschlagen",
            failure = message.ifBlank { "Unbekannter Startfehler" },
        )
    }
}

interface LifeOsProcessStartupStateReader {
    val startupState: StateFlow<LifeOsProcessStartupState>
}
