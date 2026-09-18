package app.lifeos.core.runtime.escalation

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.runtime.health.HealthNodeId
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@JvmInline
value class EscalationExecutionId(val value: String) {
    init {
        require(value.startsWith(PREFIX)) { "Invalid escalation execution id" }
        require(value.removePrefix(PREFIX).matches(Regex("[0-9a-f]{64}"))) {
            "Invalid escalation execution digest"
        }
    }

    companion object {
        const val PREFIX = "escalation-execution:"

        fun create(
            escalationId: EscalationId,
            level: EscalationLevel,
        ): EscalationExecutionId = EscalationExecutionId(
            PREFIX + StableFieldIds.fingerprint(
                "escalation-execution/v1",
                escalationId.value,
                level.name,
            )
        )
    }
}

data class EscalationExecutionRequest(
    val escalationId: EscalationId,
    val executionId: EscalationExecutionId,
    val level: EscalationLevel,
    val nodeId: HealthNodeId,
    val triggerFingerprint: String,
    val evidenceRefs: Set<String>,
    val resuming: Boolean,
) {
    init {
        require(triggerFingerprint.isNotBlank())
        require(evidenceRefs.none { it.isBlank() })
    }
}

sealed interface EscalationExecutionResult {
    val detail: String
    val evidenceRefs: Set<String>

    data class Succeeded(
        override val detail: String,
        override val evidenceRefs: Set<String> = emptySet(),
    ) : EscalationExecutionResult {
        init {
            require(detail.isNotBlank())
            require(evidenceRefs.none { it.isBlank() })
        }
    }

    data class Failed(
        override val detail: String,
        override val evidenceRefs: Set<String> = emptySet(),
    ) : EscalationExecutionResult {
        init {
            require(detail.isNotBlank())
            require(evidenceRefs.none { it.isBlank() })
        }
    }

    data class Blocked(
        override val detail: String,
        override val evidenceRefs: Set<String> = emptySet(),
    ) : EscalationExecutionResult {
        init {
            require(detail.isNotBlank())
            require(evidenceRefs.none { it.isBlank() })
        }
    }
}

fun interface EscalationLevelExecutor {
    suspend fun execute(request: EscalationExecutionRequest): EscalationExecutionResult
}

class EscalationExecutorRegistry(
    executors: Map<EscalationLevel, EscalationLevelExecutor>,
) {
    private val executors = executors.toMap()

    init {
        require(this.executors.keys == EscalationLevel.entries.toSet()) {
            "Escalation executor registry must bind every L0-L7 level exactly once"
        }
    }

    fun executor(level: EscalationLevel): EscalationLevelExecutor =
        requireNotNull(executors[level]) { "Missing escalation executor for " + level.name }
}

sealed interface EscalationCoordinationResult {
    data class Completed(
        val snapshot: EscalationSnapshot,
        val executionId: EscalationExecutionId,
        val resumed: Boolean,
        val execution: EscalationExecutionResult,
    ) : EscalationCoordinationResult

    data class ReplayedTerminal(
        val snapshot: EscalationSnapshot,
    ) : EscalationCoordinationResult
}

/**
 * Durable central authority for one escalation decision/action lifecycle.
 *
 * Existing subsystem executors retain their own safety, budget, owner-policy and idempotency
 * boundaries. This coordinator only selects a level, records the authoritative transition, and
 * delegates through a stable execution id. Cancellation intentionally leaves ACTION_IN_FLIGHT so
 * the same execution identity can be recovered after process death.
 */
class EscalationCoordinator(
    private val policy: EscalationPolicy,
    private val ledger: EscalationLedger,
    private val executors: EscalationExecutorRegistry,
) {
    private val locks = ConcurrentHashMap<EscalationId, Mutex>()

    suspend fun coordinate(trigger: EscalationTrigger): EscalationCoordinationResult {
        val mutex = locks.computeIfAbsent(trigger.id) { Mutex() }
        return mutex.withLock {
            coordinateLocked(trigger)
        }
    }

    suspend fun resumeActive(
        nodeId: HealthNodeId,
    ): List<EscalationCoordinationResult> {
        val snapshots = ledger.active()
            .filter { it.nodeId == nodeId && it.state != EscalationState.OPEN }
        return buildList {
            for (candidate in snapshots) {
                val mutex = locks.computeIfAbsent(candidate.escalationId) { Mutex() }
                add(
                    mutex.withLock {
                        val fresh = requireNotNull(ledger.snapshot(candidate.escalationId)) {
                            "Active escalation disappeared during resume"
                        }
                        if (fresh.terminal) {
                            EscalationCoordinationResult.ReplayedTerminal(fresh)
                        } else {
                            require(fresh.state != EscalationState.OPEN) {
                                "Undecided escalation cannot resume without its trigger"
                            }
                            executeSnapshot(fresh, forceResuming = true)
                        }
                    }
                )
            }
        }
    }

    private suspend fun coordinateLocked(
        trigger: EscalationTrigger,
    ): EscalationCoordinationResult {
        val decision = policy.decide(trigger)
        val snapshot = ledger.openDecided(trigger, decision)
        if (snapshot.terminal) {
            return EscalationCoordinationResult.ReplayedTerminal(snapshot)
        }
        require(snapshot.state != EscalationState.OPEN) {
            "New escalation must persist its decision atomically with opening"
        }
        return executeSnapshot(snapshot, forceResuming = false)
    }

    private suspend fun executeSnapshot(
        initial: EscalationSnapshot,
        forceResuming: Boolean,
    ): EscalationCoordinationResult {
        var snapshot = initial
        val level = requireNotNull(snapshot.level) {
            "Escalation action cannot execute without a durable decision"
        }
        val executionId = EscalationExecutionId.create(snapshot.escalationId, level)
        val resuming = forceResuming || snapshot.state == EscalationState.ACTION_IN_FLIGHT

        if (snapshot.state == EscalationState.DECIDED) {
            snapshot = ledger.markActionStarted(
                snapshot = snapshot,
                detail = "execution:" + executionId.value,
            )
        } else {
            require(snapshot.state == EscalationState.ACTION_IN_FLIGHT) {
                "Escalation action cannot execute from state " + snapshot.state.name
            }
        }

        val request = EscalationExecutionRequest(
            escalationId = snapshot.escalationId,
            executionId = executionId,
            level = level,
            nodeId = snapshot.nodeId,
            triggerFingerprint = snapshot.triggerFingerprint,
            evidenceRefs = snapshot.evidenceRefs,
            resuming = resuming,
        )

        val execution = try {
            executors.executor(level).execute(request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            EscalationExecutionResult.Failed(
                detail = "executor-exception:" +
                    (error::class.simpleName ?: "Exception") + ":" +
                    error.message.orEmpty().take(160),
            )
        }

        snapshot = when (execution) {
            is EscalationExecutionResult.Succeeded -> ledger.markActionSucceeded(
                snapshot,
                execution.detail,
                execution.evidenceRefs,
            )
            is EscalationExecutionResult.Failed -> ledger.markActionFailed(
                snapshot,
                execution.detail,
                execution.evidenceRefs,
            )
            is EscalationExecutionResult.Blocked -> ledger.markBlocked(
                snapshot,
                execution.detail,
                execution.evidenceRefs,
            )
        }

        return EscalationCoordinationResult.Completed(
            snapshot = snapshot,
            executionId = executionId,
            resumed = resuming,
            execution = execution,
        )
    }
}
