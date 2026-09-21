package app.lifeos.core.runtime.reasoning

import app.lifeos.core.reasoning.ReasoningSearchResult
import app.lifeos.core.field.HypothesisId
import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.runtime.level7.CounterfactualWorldFormulaInput
import app.lifeos.core.runtime.level7.CounterfactualWorldFormulaRunner
import app.lifeos.core.runtime.level7.CounterfactualWorldSnapshot
import app.lifeos.core.runtime.level7.WorldModelNamespaceGate
import app.lifeos.core.runtime.level7.WorldModelScenario
import app.lifeos.core.runtime.world.WorldFormulaRequest
import app.lifeos.core.runtime.world.WorldFormulaSnapshotNamespace

fun interface CounterfactualSimulationExecutor {
    suspend fun run(input: CounterfactualWorldFormulaInput): CounterfactualWorldSnapshot

    companion object {
        fun from(runner: CounterfactualWorldFormulaRunner): CounterfactualSimulationExecutor =
            CounterfactualSimulationExecutor(runner::run)
    }
}

data class ReasoningCounterfactualInput(
    val stateFingerprint: String,
    val request: WorldFormulaRequest,
) {
    init { require(stateFingerprint.isNotBlank()) }
}

data class ReasoningCounterfactualSimulation(
    val stateFingerprint: String,
    val selectedHypothesisIds: List<HypothesisId>,
    val requestId: String,
    val interventionFingerprint: String,
    val scenario: WorldModelScenario,
    val snapshot: CounterfactualWorldSnapshot,
    val fingerprint: String,
) {
    init {
        require(stateFingerprint.isNotBlank())
        require(selectedHypothesisIds == selectedHypothesisIds.distinct().sortedBy { it.value })
        require(requestId.isNotBlank())
        require(interventionFingerprint.isNotBlank())
        require(scenario.namespace == WorldFormulaSnapshotNamespace.COUNTERFACTUAL)
        require(snapshot.namespace == WorldFormulaSnapshotNamespace.COUNTERFACTUAL)
        require(!scenario.productiveCommitAllowed)
        require(!snapshot.productiveCommitAllowed)
        require(scenario.interventionFingerprint == interventionFingerprint)
        require(snapshot.interventionFingerprint == interventionFingerprint)
        require(scenario.baseProductiveSnapshotId == snapshot.baseProductiveSnapshotId)
        require(
            fingerprint == simulationFingerprint(
                stateFingerprint = stateFingerprint,
                selectedHypothesisIds = selectedHypothesisIds,
                requestId = requestId,
                interventionFingerprint = interventionFingerprint,
                scenario = scenario,
                snapshot = snapshot,
            )
        )
    }
}

data class ReasoningCounterfactualBatch(
    val searchFingerprint: String,
    val baseProductiveSnapshotId: String,
    val baseEquationVersion: String,
    val simulations: List<ReasoningCounterfactualSimulation>,
    val fingerprint: String,
) {
    init {
        require(searchFingerprint.isNotBlank())
        require(baseProductiveSnapshotId.isNotBlank())
        require(baseEquationVersion.isNotBlank())
        require(simulations.isNotEmpty())
        require(simulations == simulations.sortedBy { it.stateFingerprint })
        require(simulations.map { it.stateFingerprint }.distinct().size == simulations.size)
        require(simulations.all {
            it.scenario.baseProductiveSnapshotId == baseProductiveSnapshotId &&
                it.snapshot.baseProductiveSnapshotId == baseProductiveSnapshotId &&
                it.scenario.baseEquationVersion == baseEquationVersion
        })
        require(
            fingerprint == batchFingerprint(
                searchFingerprint,
                baseProductiveSnapshotId,
                baseEquationVersion,
                simulations,
            )
        )
    }
}

/**
 * B369 bridges complete B368 alternatives into the already isolated counterfactual World Formula
 * runtime. Every complete search state must be simulated exactly once; no state can be silently
 * dropped or promoted into productive world authority.
 */
class ReasoningCounterfactualSimulator(
    private val executor: CounterfactualSimulationExecutor,
) {
    suspend fun simulateAll(
        search: ReasoningSearchResult,
        baseProductiveSnapshotId: String,
        baseEquationVersion: String,
        inputs: Collection<ReasoningCounterfactualInput>,
    ): ReasoningCounterfactualBatch {
        require(baseProductiveSnapshotId.isNotBlank())
        require(baseEquationVersion.isNotBlank())
        val complete = search.completeStates
        require(complete.isNotEmpty()) {
            "Counterfactual simulation requires at least one complete reasoning state"
        }

        val stateByFingerprint = complete.associateBy { it.fingerprint }
        require(stateByFingerprint.size == complete.size)

        val inputsByState = inputs.associateBy { it.stateFingerprint }
        require(inputsByState.size == inputs.size) {
            "Each complete reasoning state may have at most one counterfactual request"
        }
        require(inputsByState.keys == stateByFingerprint.keys) {
            val missing = stateByFingerprint.keys - inputsByState.keys
            val unknown = inputsByState.keys - stateByFingerprint.keys
            "Counterfactual requests must cover complete reasoning states exactly; " +
                "missing=" + missing.sorted().joinToString(",") +
                ";unknown=" + unknown.sorted().joinToString(",")
        }

        val simulations = stateByFingerprint.keys
            .sorted()
            .map { stateFingerprint ->
                val state = stateByFingerprint.getValue(stateFingerprint)
                val input = inputsByState.getValue(stateFingerprint)
                require(input.request.equationVersion == baseEquationVersion) {
                    "Counterfactual request equation version must match the productive base"
                }

                val interventionFingerprint = StableFieldIds.fingerprint(
                    "reasoning-counterfactual-intervention/v1",
                    search.seedFingerprint,
                    search.fingerprint,
                    state.fingerprint,
                    input.request.id,
                    *state.selectedHypothesisIds.map { it.value }.sorted().toTypedArray(),
                )
                val scenario = WorldModelNamespaceGate.counterfactual(
                    baseProductiveSnapshotId = baseProductiveSnapshotId,
                    baseEquationVersion = baseEquationVersion,
                    interventionFingerprint = interventionFingerprint,
                )
                val snapshot = executor.run(
                    CounterfactualWorldFormulaInput(
                        baseProductiveSnapshotId = baseProductiveSnapshotId,
                        baseEquationVersion = baseEquationVersion,
                        interventionFingerprint = interventionFingerprint,
                        request = input.request,
                    )
                )

                require(snapshot.namespace == WorldFormulaSnapshotNamespace.COUNTERFACTUAL) {
                    "Counterfactual executor returned productive namespace"
                }
                require(snapshot.baseProductiveSnapshotId == baseProductiveSnapshotId) {
                    "Counterfactual executor changed the productive base snapshot"
                }
                require(snapshot.interventionFingerprint == interventionFingerprint) {
                    "Counterfactual executor changed intervention identity"
                }
                require(!snapshot.productiveCommitAllowed) {
                    "Counterfactual result cannot acquire productive commit authority"
                }

                ReasoningCounterfactualSimulation(
                    stateFingerprint = state.fingerprint,
                    selectedHypothesisIds = state.selectedHypothesisIds,
                    requestId = input.request.id,
                    interventionFingerprint = interventionFingerprint,
                    scenario = scenario,
                    snapshot = snapshot,
                    fingerprint = simulationFingerprint(
                        stateFingerprint = state.fingerprint,
                        selectedHypothesisIds = state.selectedHypothesisIds,
                        requestId = input.request.id,
                        interventionFingerprint = interventionFingerprint,
                        scenario = scenario,
                        snapshot = snapshot,
                    ),
                )
            }

        return ReasoningCounterfactualBatch(
            searchFingerprint = search.fingerprint,
            baseProductiveSnapshotId = baseProductiveSnapshotId,
            baseEquationVersion = baseEquationVersion,
            simulations = simulations,
            fingerprint = batchFingerprint(
                search.fingerprint,
                baseProductiveSnapshotId,
                baseEquationVersion,
                simulations,
            ),
        )
    }
}

private fun simulationFingerprint(
    stateFingerprint: String,
    selectedHypothesisIds: List<HypothesisId>,
    requestId: String,
    interventionFingerprint: String,
    scenario: WorldModelScenario,
    snapshot: CounterfactualWorldSnapshot,
): String = StableFieldIds.fingerprint(
    "reasoning-counterfactual-simulation/v1",
    stateFingerprint,
    requestId,
    interventionFingerprint,
    scenario.fingerprint(),
    snapshot.id,
    snapshot.snapshot.contentFingerprint(),
    *selectedHypothesisIds.map { "hypothesis:" + it.value }.sorted().toTypedArray(),
)

private fun batchFingerprint(
    searchFingerprint: String,
    baseProductiveSnapshotId: String,
    baseEquationVersion: String,
    simulations: List<ReasoningCounterfactualSimulation>,
): String = StableFieldIds.fingerprint(
    "reasoning-counterfactual-batch/v1",
    searchFingerprint,
    baseProductiveSnapshotId,
    baseEquationVersion,
    *simulations.sortedBy { it.stateFingerprint }
        .map { "simulation:" + it.fingerprint }
        .toTypedArray(),
)
