package app.lifeos.next.kernel

import app.lifeos.core.model.Photon
import app.lifeos.core.model.Provenance
import app.lifeos.core.runtime.health.HealthGraph
import app.lifeos.core.runtime.health.HealthState
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Projects meaningful health/self-healing transitions back into the LIFEOS Photon/chat stream. */
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

    private val VISIBLE_STATES = setOf(
        HealthState.DEGRADED,
        HealthState.UNHEALTHY,
        HealthState.RECOVERING,
        HealthState.QUARANTINED,
        HealthState.DISABLED,
    )
}
