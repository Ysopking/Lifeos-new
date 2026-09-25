package app.lifeos.next

import app.lifeos.core.runtime.boot.RuntimeAvailability

internal object LifeOsStartupStateProjector {
    fun projectEvent(
        current: LifeOsProcessStartupState,
        event: LifeOsStartupStageEvent,
    ): LifeOsProcessStartupState {
        if (
            current.ready &&
            LifeOsStartupStageGraph.laneFor(event.stage) == LifeOsStartupLane.WARM
        ) {
            return if (event is LifeOsStartupStageEvent.Failed) {
                current.copy(
                    availability = current.availability.degradedIfFull(),
                    stage = "Runtime eingeschränkt",
                    diagnosticCode = event.diagnosticCode,
                    durationMillis = event.durationMillis,
                    failure = "Warm Boot · ${event.stage.displayName}: ${event.message}",
                )
            } else {
                current
            }
        }

        return when (event) {
            is LifeOsStartupStageEvent.Started ->
                LifeOsProcessStartupState.stageStarted(event.stage)
            is LifeOsStartupStageEvent.Completed ->
                LifeOsProcessStartupState.stageCompleted(event)
            is LifeOsStartupStageEvent.Failed ->
                LifeOsProcessStartupState.stageFailed(event)
        }
    }

    fun projectWarmCompletion(
        current: LifeOsProcessStartupState,
        kernelAvailability: RuntimeAvailability,
        report: LifeOsWarmStartupReport,
    ): LifeOsProcessStartupState {
        if (
            !current.ready ||
            (!report.degraded && kernelAvailability != RuntimeAvailability.DEGRADED)
        ) {
            return current
        }
        val summary = report.failures
            .joinToString("; ") { "${it.diagnosticCode}:${it.stage.displayName}" }
            .ifBlank { "kernel-warm-rehydration" }
        return current.copy(
            availability = current.availability.degradedIfFull(),
            stage = "Runtime eingeschränkt",
            failure = current.failure ?: "Warm Boot eingeschränkt: $summary",
        )
    }

    fun projectStartupFailure(
        current: LifeOsProcessStartupState,
        error: Exception,
    ): LifeOsProcessStartupState {
        val message = error.message ?: error::class.simpleName ?: "lifeos-startup-failed"
        return if (current.ready) {
            current.copy(
                availability = current.availability.degradedIfFull(),
                stage = "Runtime eingeschränkt",
                failure = message,
            )
        } else if (current.phase != LifeOsProcessStartupPhase.FAILED) {
            LifeOsProcessStartupState.failed(message)
        } else {
            current
        }
    }

    private fun RuntimeAvailability.degradedIfFull(): RuntimeAvailability =
        if (this == RuntimeAvailability.FULL) RuntimeAvailability.DEGRADED else this
}
