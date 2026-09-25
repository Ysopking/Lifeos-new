package app.lifeos.next

/**
 * Incremental warm-start projection.
 *
 * The final [LifeOsWarmStartupReport] is still published after the whole warm DAG finishes, but
 * callers that only need one warm subsystem can observe its terminal stage immediately instead of
 * waiting for unrelated warm siblings.
 */
internal object LifeOsWarmStartupProgressProjector {
    fun project(
        current: LifeOsWarmStartupReport?,
        event: LifeOsStartupStageEvent,
    ): LifeOsWarmStartupReport? {
        if (LifeOsStartupStageGraph.laneFor(event.stage) != LifeOsStartupLane.WARM) {
            return current
        }

        return when (event) {
            is LifeOsStartupStageEvent.Started -> current

            is LifeOsStartupStageEvent.Completed -> {
                val base = current ?: LifeOsWarmStartupReport(
                    completedStages = emptySet(),
                    failures = emptyList(),
                )
                LifeOsWarmStartupReport(
                    completedStages = base.completedStages + event.stage,
                    failures = base.failures
                        .filterNot { it.stage == event.stage }
                        .sortedBy { it.stage.ordinal },
                )
            }

            is LifeOsStartupStageEvent.Failed -> {
                val base = current ?: LifeOsWarmStartupReport(
                    completedStages = emptySet(),
                    failures = emptyList(),
                )
                val failure = LifeOsWarmStartupFailure(
                    stage = event.stage,
                    diagnosticCode = event.diagnosticCode,
                    message = event.message,
                )
                LifeOsWarmStartupReport(
                    completedStages = base.completedStages - event.stage,
                    failures = (
                        base.failures.filterNot { it.stage == event.stage } + failure
                        ).sortedBy { it.stage.ordinal },
                )
            }
        }
    }
}
