package app.lifeos.next.kernel

import app.lifeos.core.model.Photon
import app.lifeos.core.model.Provenance
import app.lifeos.core.runtime.health.HealthGraph
import app.lifeos.core.runtime.health.HealthNodeId
import app.lifeos.core.runtime.health.HealthScope
import app.lifeos.core.runtime.health.HealthState
import app.lifeos.core.runtime.self.SELF_OBSERVATION_HEALTH_SOURCE
import app.lifeos.core.runtime.topology.LifeOsRuntimeBindingRegistry
import app.lifeos.core.runtime.topology.LifeOsRuntimeBindingState
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Projects meaningful health/self-healing transitions back into the LIFEOS Photon/chat stream and
 * updates only unambiguous process topology bindings. It deliberately does not fold arbitrary field
 * or storage child nodes into one aggregate subsystem state.
 */
object LifeOsHealthPhotonBridge {
    private val lastStateByNode = ConcurrentHashMap<String, HealthState>()

    @Volatile
    private var started = false

    fun start(
        scope: CoroutineScope,
        graph: HealthGraph,
        persist: suspend (Photon) -> Unit,
    ) {
        synchronized(this) {
            if (started) return
            started = true
        }
        scope.launch {
            graph.observations.collect { observation ->
                // Self-observation already has a dedicated DecisionTrace. Re-projecting its derived
                // HealthGraph output into PhotonStore/topology would mutate the state it is observing.
                if (observation.source == SELF_OBSERVATION_HEALTH_SOURCE) return@collect
                updateTopology(graph, observation.nodeId, observation.state, observation.message)

                val previous = lastStateByNode.put(observation.nodeId.value, observation.state)
                if (previous == observation.state) return@collect
                if (observation.state !in VISIBLE_STATES) return@collect

                val message = buildString {
                    append("Systemzustand ")
                    append(observation.nodeId.value)
                    append(": ")
                    append(observation.state.name.lowercase())
                    observation.message?.takeIf { it.isNotBlank() }?.let {
                        append(" — ")
                        append(it.take(240))
                    }
                }
                persist(
                    Photon(
                        content = message,
                        provenance = Provenance(
                            source = "health-self-healing",
                            actor = "system",
                            createdAt = observation.observedAt,
                        ),
                        tags = setOf(
                            "chat",
                            "chat:system",
                            "conversation:default",
                            "system:health",
                            "health:${observation.state.name.lowercase()}",
                            "health-node:${observation.nodeId.value}",
                        ),
                    )
                )
            }
        }
    }

    private suspend fun updateTopology(
        graph: HealthGraph,
        nodeId: HealthNodeId,
        state: HealthState,
        message: String?,
    ) {
        val node = graph.node(nodeId) ?: return
        val subsystemId = topologySubsystem(node.scope, nodeId.value) ?: return
        if (LifeOsRuntimeBindingRegistry.current(subsystemId) == null) return
        val bindingState = when {
            nodeId.value == "runtime" && message?.contains("stopped cleanly", ignoreCase = true) == true ->
                LifeOsRuntimeBindingState.STOPPED
            state == HealthState.HEALTHY -> LifeOsRuntimeBindingState.ACTIVE
            state == HealthState.DEGRADED || state == HealthState.RECOVERING ->
                LifeOsRuntimeBindingState.DEGRADED
            state == HealthState.UNHEALTHY || state == HealthState.QUARANTINED ->
                LifeOsRuntimeBindingState.QUARANTINED
            state == HealthState.DISABLED -> LifeOsRuntimeBindingState.STOPPED
            HealthState.UNKNOWN == state -> return
            else -> return
        }
        val current = LifeOsRuntimeBindingRegistry.current(subsystemId) ?: return
        if (current.state != bindingState || current.detail != message) {
            LifeOsRuntimeBindingRegistry.update(
                subsystemId = subsystemId,
                state = bindingState,
                detail = message?.take(240),
            )
        }
    }

    private fun topologySubsystem(scope: HealthScope, nodeId: String): String? = when (scope) {
        HealthScope.RUNTIME -> "runtime-supervisor"
        HealthScope.WORKER -> "cognitive-worker"
        HealthScope.SCHEDULER -> "task-scheduler"
        HealthScope.PLANNER -> "goal-planning"
        HealthScope.BUILD_STUDIO -> "build-studio"
        HealthScope.AUTOMATION -> if (nodeId.contains("tool", ignoreCase = true)) {
            "autonomous-tool-workshop"
        } else {
            null
        }
        else -> null
    }

    private val VISIBLE_STATES = setOf(
        HealthState.DEGRADED,
        HealthState.UNHEALTHY,
        HealthState.RECOVERING,
        HealthState.QUARANTINED,
        HealthState.DISABLED,
    )
}
