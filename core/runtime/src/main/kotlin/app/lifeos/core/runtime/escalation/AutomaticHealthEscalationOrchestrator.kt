package app.lifeos.core.runtime.escalation

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.runtime.health.AutomaticSelfHealingPlanRegistry
import app.lifeos.core.runtime.health.FailureClassification
import app.lifeos.core.runtime.health.HealthFailureCategory
import app.lifeos.core.runtime.health.HealthGraph
import app.lifeos.core.runtime.health.HealthNode
import app.lifeos.core.runtime.health.HealthNodeId
import app.lifeos.core.runtime.health.HealthObservation
import app.lifeos.core.runtime.health.HealthScope
import app.lifeos.core.runtime.health.HealthState
import app.lifeos.core.runtime.health.SelfHealingGenerationResolution
import app.lifeos.core.runtime.health.SelfHealingIncidentGenerationResolver
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface HealthEscalationRecoveryPlanner {
    fun observeHealthy(nodeId: HealthNodeId)

    suspend fun recoveryAvailable(
        node: HealthNode,
        observation: HealthObservation,
    ): Boolean
}

/**
 * Reuses the existing automatic self-healing plan registry and generation resolver as the L2
 * context authority. Recovery plans remain owned by the established self-healing subsystem.
 */
class AutomaticSelfHealingEscalationPlanner(
    private val graph: HealthGraph,
    private val plans: AutomaticSelfHealingPlanRegistry,
    private val generations: SelfHealingIncidentGenerationResolver,
) : HealthEscalationRecoveryPlanner, SelfHealingEscalationContextSource {
    private val healthySeenSinceStartup = ConcurrentHashMap.newKeySet<HealthNodeId>()

    override fun observeHealthy(nodeId: HealthNodeId) {
        healthySeenSinceStartup += nodeId
    }

    override suspend fun recoveryAvailable(
        node: HealthNode,
        observation: HealthObservation,
    ): Boolean = resolveContext(node, observation) != null

    override suspend fun resolve(
        request: EscalationExecutionRequest,
    ): SelfHealingEscalationContext? {
        val node = graph.node(request.nodeId) ?: return null
        val observation = graph.recent(request.nodeId, limit = 16)
            .firstOrNull {
                it.state == HealthState.DEGRADED ||
                    it.state == HealthState.UNHEALTHY
            }
            ?: return null
        return resolveContext(node, observation)
    }

    private suspend fun resolveContext(
        node: HealthNode,
        observation: HealthObservation,
    ): SelfHealingEscalationContext? {
        val resolved = plans.resolve(node, observation) ?: return null
        return when (
            val generation = generations.resolve(
                plan = resolved.plan,
                familyFingerprint = resolved.familyFingerprint,
                healthyObservedSinceStartup = node.id in healthySeenSinceStartup,
            )
        ) {
            is SelfHealingGenerationResolution.Ready -> SelfHealingEscalationContext(
                plan = resolved.plan.copy(quarantineOnFailure = false),
                resources = resolved.resources,
                incidentFingerprint = generation.incidentFingerprint,
            )

            is SelfHealingGenerationResolution.Suppressed -> null
        }
    }
}

/**
 * Productive HealthGraph -> central escalation bridge.
 *
 * Only DEGRADED/UNHEALTHY observations enter the ladder. Per-node in-process serialization avoids
 * duplicate concurrent exposure while EscalationLedger supplies process-death durability.
 */
class AutomaticHealthEscalationOrchestrator(
    private val scope: CoroutineScope,
    private val graph: HealthGraph,
    private val recoveryPlanner: HealthEscalationRecoveryPlanner,
    private val coordinator: EscalationCoordinator,
    private val onFailure: suspend (HealthNodeId, Throwable) -> Unit = { _, _ -> },
) {
    private val stateMutex = Mutex()
    private val activeNodes = mutableSetOf<HealthNodeId>()
    private var job: Job? = null

    fun start(): Job {
        job?.takeIf { it.isActive }?.let { return it }
        return scope.launch {
            graph.observations.collect { observation ->
                when (observation.state) {
                    HealthState.HEALTHY -> recoveryPlanner.observeHealthy(observation.nodeId)
                    HealthState.DEGRADED,
                    HealthState.UNHEALTHY -> schedule(observation)

                    HealthState.UNKNOWN,
                    HealthState.RECOVERING,
                    HealthState.QUARANTINED,
                    HealthState.DISABLED -> Unit
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
        val acquired = stateMutex.withLock { activeNodes.add(node.id) }
        if (!acquired) return

        scope.launch {
            try {
                val recoverable = observation.classification?.recoverable ?: true
                val recoveryAvailable = if (recoverable) {
                    try {
                        recoveryPlanner.recoveryAvailable(node, observation)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        false
                    }
                } else {
                    false
                }

                val trigger = EscalationTrigger(
                    nodeId = node.id,
                    scope = node.scope,
                    category = observation.classification?.category
                        ?: HealthFailureCategory.UNKNOWN,
                    recoverable = recoverable,
                    consecutiveFailures = node.consecutiveFailures,
                    retryBudgetRemaining = false,
                    componentRecoveryAvailable = recoveryAvailable,
                    protectionCritical = protectionCritical(node, observation.classification),
                    evidenceRefs = setOf(healthObservationRef(node, observation)),
                    observedAt = observation.observedAt,
                )
                val result = coordinator.coordinate(trigger)
                if (
                    result is EscalationCoordinationResult.Completed &&
                    result.snapshot.level == EscalationLevel.L2_RECOVER_COMPONENT &&
                    result.execution is EscalationExecutionResult.Failed
                ) {
                    coordinator.coordinate(
                        trigger.copy(
                            componentRecoveryAvailable = false,
                            priorLevels = listOf(EscalationLevel.L2_RECOVER_COMPONENT),
                            evidenceRefs = trigger.evidenceRefs + setOf(
                                "escalation:" + result.snapshot.escalationId.value
                            ),
                        )
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                onFailure(node.id, error)
            } finally {
                stateMutex.withLock { activeNodes.remove(node.id) }
            }
        }
    }

    private fun protectionCritical(
        node: HealthNode,
        classification: FailureClassification?,
    ): Boolean {
        if (classification == null) return false
        if (classification.category == HealthFailureCategory.DATA_CORRUPTION) return true
        return classification.category == HealthFailureCategory.STATE_INVARIANT &&
            node.scope in setOf(HealthScope.KERNEL, HealthScope.STORAGE_ENGINE)
    }

    private fun healthObservationRef(
        node: HealthNode,
        observation: HealthObservation,
    ): String = "health-observation:" + StableFieldIds.fingerprint(
        "health-escalation-observation/v1",
        node.id.value,
        node.scope.name,
        observation.state.name,
        observation.observedAt.toString(),
        observation.source,
        observation.message.orEmpty(),
        observation.classification?.category?.name.orEmpty(),
        observation.classification?.recoverable?.toString().orEmpty(),
    )
}
