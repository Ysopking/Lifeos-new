package app.lifeos.core.runtime.health

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Component states stay local: an optional field failure never rewrites Kernel/Runtime. */
class HealthGraph {
    private val nodes = HealthNodes.all.associateBy { it.id }.toMutableMap()
    private var sequence = 0L
    private val mutableStates = MutableStateFlow(nodes.keys.associateWith { HealthState.UNKNOWN })
    val states = mutableStates.asStateFlow()
    private val mutableObservations = MutableStateFlow<List<HealthObservation>>(emptyList())
    val observations = mutableObservations.asStateFlow()

    @Synchronized fun record(node: HealthNode, state: HealthState, reason: String) {
        nodes[node.id] = node
        mutableStates.value = mutableStates.value + (node.id to state)
        mutableObservations.value = (mutableObservations.value + HealthObservation(
            node.id, state, ++sequence, reason,
        )).takeLast(100)
    }
}
