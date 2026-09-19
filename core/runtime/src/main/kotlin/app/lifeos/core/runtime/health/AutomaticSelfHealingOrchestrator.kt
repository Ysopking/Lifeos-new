package app.lifeos.core.runtime.health

import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AutomaticSelfHealingOrchestrator(
    private val scope: CoroutineScope,
    private val graph: HealthGraph,
    private val plans: AutomaticSelfHealingPlanRegistry,
    private val generations: SelfHealingIncidentGenerationResolver,
    private val coordinator: DurableSelfHealingCoordinator,
    private val quarantineRegistry: QuarantineRegistry,
    private val now: () -> Instant = Instant::now,
) {
    private val stateMutex = Mutex()
    private val activeNodes = mutableSetOf<HealthNodeId>()
    private val healthySeenSinceStartup = mutableSetOf<HealthNodeId>()
    private var job: Job? = null

    fun start(): Job {
        job?.takeIf { it.isActive }?.let { return it }
        return scope.launch {
            graph.observations.collect { observation ->
                if (!observation.actionable) return@collect
                when (observation.state) {
                    HealthState.HEALTHY -> stateMutex.withLock {
                        healthySeenSinceStartup += observation.nodeId
                    }
                    HealthState.DEGRADED,
                    HealthState.UNHEALTHY -> schedule(observation)
                    else -> Unit
                }
            }
        }.also { job = it }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun schedule(observation: HealthObservation) {
        val node = graph.node(observation.nodeId) ?: return
        if (observation.classification?.recoverable == false) {
            quarantine(node, observation, "non-recoverable-health-failure")
            return
        }
        val resolved = try {
            plans.resolve(node, observation)
        } catch (error: Exception) {
            quarantine(node, observation, "repair-plan-resolution:${error.message.orEmpty().take(160)}")
            return
        }
        if (resolved == null) {
            quarantine(node, observation, "no-registered-safe-repair-plan")
            return
        }

        val acquired = stateMutex.withLock { activeNodes.add(node.id) }
        if (!acquired) return
        scope.launch {
            try {
                val healthySeen = stateMutex.withLock { node.id in healthySeenSinceStartup }
                when (
                    val generation = generations.resolve(
                        plan = resolved.plan,
                        familyFingerprint = resolved.familyFingerprint,
                        healthyObservedSinceStartup = healthySeen,
                    )
                ) {
                    is SelfHealingGenerationResolution.Suppressed -> Unit
                    is SelfHealingGenerationResolution.Ready -> coordinator.recover(
                        plan = resolved.plan,
                        incidentFingerprint = generation.incidentFingerprint,
                        resources = resolved.resources,
                    )
                }
            } catch (error: Exception) {
                quarantine(
                    node,
                    observation,
                    "automatic-self-healing-failed:${error::class.simpleName}:${error.message.orEmpty().take(160)}",
                )
            } finally {
                stateMutex.withLock { activeNodes.remove(node.id) }
            }
        }
    }

    private suspend fun quarantine(
        node: HealthNode,
        observation: HealthObservation,
        reason: String,
    ) {
        val at = now()
        quarantineRegistry.quarantine(
            QuarantineEntry(
                nodeId = node.id,
                source = "automatic-self-healing:${observation.source}",
                reason = reason,
                quarantinedAt = at,
            )
        )
        graph.record(
            HealthObservation(
                nodeId = node.id,
                state = HealthState.QUARANTINED,
                observedAt = at,
                source = "automatic-self-healing",
                message = reason,
                classification = observation.classification,
            )
        )
    }
}
