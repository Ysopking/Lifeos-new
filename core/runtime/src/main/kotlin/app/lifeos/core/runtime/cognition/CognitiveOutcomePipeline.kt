package app.lifeos.core.runtime.cognition

import app.lifeos.core.model.FieldInfluence
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.task.LifeTask
import app.lifeos.core.model.task.TaskId
import app.lifeos.core.model.task.TaskState
import app.lifeos.core.runtime.RuntimeFailure
import app.lifeos.core.runtime.field.FieldShadowExecution
import app.lifeos.core.runtime.workers.CognitiveTaskExecutionResult
import app.lifeos.core.runtime.workers.DurableTaskExecutionObserver
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class CognitiveOutcome(
    val taskId: TaskId,
    val photonId: PhotonId?,
    val finalState: TaskState,
    val influences: List<FieldInfluence>,
    val failures: List<RuntimeFailure>,
    val recordedAt: Instant,
    val fieldShadow: FieldShadowExecution? = null,
) {
    val averageConfidence: Double = influences
        .map { it.confidence }
        .takeIf { it.isNotEmpty() }
        ?.average()
        ?: 0.0

    val totalEnergyDelta: Double = influences.sumOf { it.deltaEnergy }
}

interface CognitiveOutcomeJournal {
    suspend fun record(outcome: CognitiveOutcome): Long
    suspend fun latest(limit: Int = 64): List<CognitiveOutcome>
}

class InMemoryCognitiveOutcomeJournal : CognitiveOutcomeJournal {
    private val mutex = Mutex()
    private val outcomes = mutableListOf<CognitiveOutcome>()

    override suspend fun record(outcome: CognitiveOutcome): Long = mutex.withLock {
        outcomes += outcome
        outcomes.size.toLong()
    }

    override suspend fun latest(limit: Int): List<CognitiveOutcome> = mutex.withLock {
        require(limit > 0) { "Outcome limit must be positive" }
        outcomes.asReversed().take(limit)
    }
}

enum class CognitiveTriggerType {
    RECOVERY,
    QUARANTINE_REVIEW,
    REEVALUATE,
    CONVERGENCE,
}

data class CognitiveTrigger(
    val id: String = UUID.randomUUID().toString(),
    val type: CognitiveTriggerType,
    val sourceTaskId: TaskId,
    val photonId: PhotonId?,
    val reason: String,
    val createdAt: Instant,
) {
    init {
        require(id.isNotBlank()) { "Trigger id must not be blank" }
        require(reason.isNotBlank()) { "Trigger reason must not be blank" }
    }
}

fun interface CognitiveTriggerPolicy {
    fun evaluate(outcome: CognitiveOutcome): List<CognitiveTrigger>
}

class DefaultCognitiveTriggerPolicy(
    private val convergenceMinInfluences: Int = 2,
    private val convergenceMinConfidence: Double = 0.85,
    private val reevaluationMinConfidence: Double = 0.80,
    private val reevaluationMinEnergyDelta: Double = 1.0,
) : CognitiveTriggerPolicy {
    init {
        require(convergenceMinInfluences > 0) { "Convergence influence threshold must be positive" }
        require(convergenceMinConfidence in 0.0..1.0) { "Convergence confidence must be normalized" }
        require(reevaluationMinConfidence in 0.0..1.0) { "Reevaluation confidence must be normalized" }
        require(reevaluationMinEnergyDelta >= 0.0 && reevaluationMinEnergyDelta.isFinite()) {
            "Reevaluation energy threshold must be finite and non-negative"
        }
    }

    override fun evaluate(outcome: CognitiveOutcome): List<CognitiveTrigger> {
        if (outcome.finalState == TaskState.FAILED) {
            val recoverable = outcome.failures.any { it.recoverable }
            return listOf(
                CognitiveTrigger(
                    id = deterministicId(outcome, if (recoverable) "recovery" else "quarantine"),
                    type = if (recoverable) {
                        CognitiveTriggerType.RECOVERY
                    } else {
                        CognitiveTriggerType.QUARANTINE_REVIEW
                    },
                    sourceTaskId = outcome.taskId,
                    photonId = outcome.photonId,
                    reason = if (recoverable) {
                        "recoverable-task-failure"
                    } else {
                        "non-recoverable-task-failure"
                    },
                    createdAt = outcome.recordedAt,
                )
            )
        }

        if (outcome.finalState != TaskState.COMPLETED || outcome.influences.isEmpty()) {
            return emptyList()
        }

        return buildList {
            if (
                outcome.influences.size >= convergenceMinInfluences &&
                outcome.averageConfidence >= convergenceMinConfidence
            ) {
                add(
                    CognitiveTrigger(
                        id = deterministicId(outcome, "convergence"),
                        type = CognitiveTriggerType.CONVERGENCE,
                        sourceTaskId = outcome.taskId,
                        photonId = outcome.photonId,
                        reason = "multi-field-high-confidence-outcome",
                        createdAt = outcome.recordedAt,
                    )
                )
            }
            if (
                outcome.averageConfidence >= reevaluationMinConfidence &&
                kotlin.math.abs(outcome.totalEnergyDelta) >= reevaluationMinEnergyDelta
            ) {
                add(
                    CognitiveTrigger(
                        id = deterministicId(outcome, "reevaluate"),
                        type = CognitiveTriggerType.REEVALUATE,
                        sourceTaskId = outcome.taskId,
                        photonId = outcome.photonId,
                        reason = "high-confidence-material-field-change",
                        createdAt = outcome.recordedAt,
                    )
                )
            }
        }
    }

    private fun deterministicId(outcome: CognitiveOutcome, suffix: String): String =
        "trigger:${outcome.taskId.value}:${outcome.finalState}:$suffix"
}

interface CognitiveTriggerSink {
    suspend fun emit(trigger: CognitiveTrigger): Boolean
    suspend fun snapshot(): List<CognitiveTrigger>
}

class InMemoryCognitiveTriggerSink : CognitiveTriggerSink {
    private val mutex = Mutex()
    private val triggers = linkedMapOf<String, CognitiveTrigger>()

    override suspend fun emit(trigger: CognitiveTrigger): Boolean = mutex.withLock {
        if (trigger.id in triggers) return@withLock false
        triggers[trigger.id] = trigger
        true
    }

    override suspend fun snapshot(): List<CognitiveTrigger> = mutex.withLock {
        triggers.values.toList()
    }
}

class OutcomeTriggerObserver(
    private val outcomes: CognitiveOutcomeJournal,
    private val triggers: CognitiveTriggerSink,
    private val policy: CognitiveTriggerPolicy = DefaultCognitiveTriggerPolicy(),
    private val now: () -> Instant = Instant::now,
) : DurableTaskExecutionObserver {
    override suspend fun onExecutionResult(result: CognitiveTaskExecutionResult) {
        val outcome = CognitiveOutcome(
            taskId = result.taskId,
            photonId = result.photonId,
            finalState = result.finalState,
            influences = result.influences,
            failures = result.failures,
            recordedAt = now(),
            fieldShadow = result.fieldShadow,
        )
        outcomes.record(outcome)
        policy.evaluate(outcome).forEach { trigger -> triggers.emit(trigger) }
    }
}

/**
 * The first observer is authoritative. All later observers are telemetry/derived-state observers and
 * run best-effort so a monitoring or journaling defect cannot rewrite an already completed task.
 */
class CompositeDurableTaskExecutionObserver(
    observers: List<DurableTaskExecutionObserver>,
) : DurableTaskExecutionObserver {
    private val observers = observers.toList().also {
        require(it.isNotEmpty()) { "Composite execution observer requires an authoritative observer" }
    }

    override suspend fun onExecutionResult(result: CognitiveTaskExecutionResult) {
        observers.first().onExecutionResult(result)
        observers.drop(1).forEach { observer ->
            try {
                observer.onExecutionResult(result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Derived observations must never mutate durable task outcome semantics.
            }
        }
    }

    override suspend fun onDispatchFailure(task: LifeTask, error: Exception) {
        observers.first().onDispatchFailure(task, error)
        observers.drop(1).forEach { observer ->
            try {
                observer.onDispatchFailure(task, error)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Failure telemetry is best-effort after the authoritative observer is updated.
            }
        }
    }
}
