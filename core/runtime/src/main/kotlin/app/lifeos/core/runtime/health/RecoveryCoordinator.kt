package app.lifeos.core.runtime.health

import java.time.Instant

sealed interface RecoveryActionResult {
    data class Success(val message: String? = null) : RecoveryActionResult
    data class Failure(val message: String, val retryable: Boolean = true) : RecoveryActionResult
}

interface RecoveryAction {
    val id: String
    suspend fun execute(): RecoveryActionResult
}

data class RecoveryPlan(
    val nodeId: HealthNodeId,
    val source: String,
    val actions: List<RecoveryAction>,
    val verificationProbes: List<RepairProbe>,
    val quarantineOnFailure: Boolean = true,
) {
    init {
        require(source.isNotBlank()) { "Recovery source must not be blank" }
        require(actions.isNotEmpty()) { "Recovery plan must contain at least one action" }
        require(actions.all { it.id.isNotBlank() }) { "Recovery action ids must not be blank" }
        require(actions.map { it.id }.distinct().size == actions.size) {
            "Recovery action ids must be unique"
        }
        require(verificationProbes.isNotEmpty()) {
            "Recovery plan must contain verification probes"
        }
        require(verificationProbes.map { it.id }.distinct().size == verificationProbes.size) {
            "Recovery verification probe ids must be unique"
        }
    }
}

sealed interface RecoveryResult {
    data class Recovered(
        val nodeId: HealthNodeId,
        val actionId: String,
        val evidence: CompositeRepairEvidence,
    ) : RecoveryResult

    data class Exhausted(
        val nodeId: HealthNodeId,
        val attemptedActionIds: List<String>,
        val quarantined: Boolean,
        val lastFailure: String,
        val evidence: CompositeRepairEvidence? = null,
    ) : RecoveryResult
}

class RecoveryCoordinator(
    private val healthGraph: HealthGraph,
    private val quarantineRegistry: QuarantineRegistry,
    private val now: () -> Instant = Instant::now,
) {
    suspend fun recover(plan: RecoveryPlan): RecoveryResult {
        healthGraph.record(
            HealthObservation(
                nodeId = plan.nodeId,
                state = HealthState.RECOVERING,
                observedAt = now(),
                source = plan.source,
                message = "recovery-started",
            )
        )

        val verifier = CompositeRepairProbe(plan.verificationProbes, now)
        val attempted = mutableListOf<String>()
        var lastFailure = "recovery-exhausted"
        var lastEvidence: CompositeRepairEvidence? = null

        for (action in plan.actions) {
            attempted += action.id
            when (val result = action.execute()) {
                is RecoveryActionResult.Success -> {
                    val evidence = verifier.collect(plan.nodeId)
                    lastEvidence = evidence
                    if (evidence.verifiedHealthy) {
                        quarantineRegistry.release(plan.nodeId)
                        healthGraph.recordHealthy(
                            id = plan.nodeId,
                            source = "${plan.source}:${action.id}",
                            message = result.message ?: "recovery-verified",
                            observedAt = evidence.capturedAt,
                        )
                        return RecoveryResult.Recovered(
                            nodeId = plan.nodeId,
                            actionId = action.id,
                            evidence = evidence,
                        )
                    }
                    lastFailure = "verification-failed:${evidence.summary()}"
                }

                is RecoveryActionResult.Failure -> {
                    lastFailure = result.message
                    if (!result.retryable) break
                }
            }
        }

        healthGraph.record(
            HealthObservation(
                nodeId = plan.nodeId,
                state = HealthState.UNHEALTHY,
                observedAt = now(),
                source = plan.source,
                message = lastFailure,
            )
        )

        val quarantined = plan.quarantineOnFailure
        if (quarantined) {
            val at = now()
            quarantineRegistry.quarantine(
                QuarantineEntry(
                    nodeId = plan.nodeId,
                    source = plan.source,
                    reason = lastFailure,
                    quarantinedAt = at,
                )
            )
            healthGraph.record(
                HealthObservation(
                    nodeId = plan.nodeId,
                    state = HealthState.QUARANTINED,
                    observedAt = at,
                    source = plan.source,
                    message = lastFailure,
                )
            )
        }

        return RecoveryResult.Exhausted(
            nodeId = plan.nodeId,
            attemptedActionIds = attempted,
            quarantined = quarantined,
            lastFailure = lastFailure,
            evidence = lastEvidence,
        )
    }
}
