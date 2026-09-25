package app.lifeos.next

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

internal data class LifeOsWarmStageSnapshot(
    val completedStages: Set<LifeOsStartupStage> = emptySet(),
    val failures: List<LifeOsWarmStartupFailure> = emptyList(),
) {
    init {
        require(completedStages.all {
            LifeOsStartupStageGraph.laneFor(it) == LifeOsStartupLane.WARM
        })
        require(failures.all {
            LifeOsStartupStageGraph.laneFor(it.stage) == LifeOsStartupLane.WARM
        })
        require(failures == failures.distinctBy { it.stage }.sortedBy { it.stage.ordinal })
        require(failures.none { it.stage in completedStages })
    }

    fun terminal(stage: LifeOsStartupStage): Boolean =
        stage in completedStages || failures.any { it.stage == stage }

    fun failure(stage: LifeOsStartupStage): LifeOsWarmStartupFailure? =
        failures.singleOrNull { it.stage == stage }
}

/**
 * Publishes warm-stage completion immediately when the stage finishes.
 *
 * This is intentionally independent from LifeOsWarmStartupReport, which remains the aggregate
 * end-of-warm report. Feature readiness must not wait for unrelated warm work.
 */
internal class LifeOsWarmStageReadiness {
    private val mutableState = MutableStateFlow(LifeOsWarmStageSnapshot())
    val state: StateFlow<LifeOsWarmStageSnapshot> = mutableState.asStateFlow()

    fun onEvent(event: LifeOsStartupStageEvent) {
        if (LifeOsStartupStageGraph.laneFor(event.stage) != LifeOsStartupLane.WARM) return
        when (event) {
            is LifeOsStartupStageEvent.Started -> Unit
            is LifeOsStartupStageEvent.Completed -> {
                mutableState.update { current ->
                    current.copy(
                        completedStages = current.completedStages + event.stage,
                        failures = current.failures.filterNot { it.stage == event.stage },
                    )
                }
            }
            is LifeOsStartupStageEvent.Failed -> {
                val failure = LifeOsWarmStartupFailure(
                    stage = event.stage,
                    diagnosticCode = event.diagnosticCode,
                    message = event.message,
                )
                mutableState.update { current ->
                    current.copy(
                        completedStages = current.completedStages - event.stage,
                        failures = (
                            current.failures.filterNot { it.stage == event.stage } + failure
                            ).sortedBy { it.stage.ordinal },
                    )
                }
            }
        }
    }

    suspend fun await(stage: LifeOsStartupStage) {
        require(LifeOsStartupStageGraph.laneFor(stage) == LifeOsStartupLane.WARM) {
            "Only warm startup stages have live warm readiness"
        }
        val snapshot = state.first { it.terminal(stage) }
        snapshot.failure(stage)?.let { failure ->
            error(
                "Warm stage failed: ${failure.diagnosticCode}:" +
                    "${failure.stage.name}:${failure.message}"
            )
        }
    }
}
