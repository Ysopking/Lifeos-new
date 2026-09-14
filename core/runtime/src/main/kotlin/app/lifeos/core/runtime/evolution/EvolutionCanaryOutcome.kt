package app.lifeos.core.runtime.evolution

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.runtime.capability.GeneratedToolLifecycleCoordinator
import app.lifeos.core.runtime.capability.GeneratedToolState
import app.lifeos.core.runtime.capability.GeneratedToolTrialResult
import app.lifeos.core.runtime.trace.LifecycleDecisionTraceRecorder
import app.lifeos.core.runtime.trace.LifecycleDecisionTraceRuntimeRegistry
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class EvolutionCanaryOutcomeInput(
    val reservationId: String,
    val invocationId: String,
    val success: Boolean,
    val producedExpectedOutput: Boolean,
    val outputFingerprint: String? = null,
    val latencyMs: Long,
    val hardFailures: Set<EvolutionHardFailure> = emptySet(),
) {
    init {
        require(reservationId.isNotBlank()) { "Canary reservation id must not be blank" }
        require(invocationId.isNotBlank()) { "Canary invocation id must not be blank" }
        require(latencyMs >= 0) { "Canary latency must not be negative" }
        require(outputFingerprint == null || outputFingerprint.isNotBlank()) {
            "Canary output fingerprint must not be blank"
        }
        require(!success || hardFailures.isEmpty()) {
            "Canary outcome cannot be successful while carrying a hard failure"
        }
        if (producedExpectedOutput) {
            require(!outputFingerprint.isNullOrBlank()) {
                "Expected canary output requires an output fingerprint"
            }
        }
    }

    val fingerprint: String = StableFieldIds.fingerprint(
        "evolution-canary-outcome-input/v1",
        reservationId,
        invocationId,
        success.toString(),
        producedExpectedOutput.toString(),
        outputFingerprint.orEmpty(),
        latencyMs.toString(),
        *hardFailures.sortedBy { it.name }.map { "failure:${it.name}" }.toTypedArray(),
    )
}

data class EvolutionCanaryOutcome(
    val adoptionEvidenceId: String,
    val reservationId: String,
    val candidateToolId: String,
    val candidateRecordFingerprint: String,
    val invocationId: String,
    val inputFingerprint: String,
    val success: Boolean,
    val producedExpectedOutput: Boolean,
    val outputFingerprint: String?,
    val latencyMs: Long,
    val hardFailures: Set<EvolutionHardFailure>,
    val recordedAt: Instant,
) {
    init {
        require(adoptionEvidenceId.isNotBlank())
        require(reservationId.isNotBlank())
        require(candidateToolId.isNotBlank())
        require(candidateRecordFingerprint.isNotBlank())
        require(invocationId.isNotBlank())
        require(inputFingerprint.isNotBlank())
        require(latencyMs >= 0)
        require(!success || hardFailures.isEmpty())
        if (producedExpectedOutput) require(!outputFingerprint.isNullOrBlank())
    }

    val id: String = StableFieldIds.fingerprint(
        "evolution-canary-outcome/v1",
        adoptionEvidenceId,
        reservationId,
        candidateToolId,
        candidateRecordFingerprint,
        invocationId,
        inputFingerprint,
        success.toString(),
        producedExpectedOutput.toString(),
        outputFingerprint.orEmpty(),
        latencyMs.toString(),
        recordedAt.toString(),
        *hardFailures.sortedBy { it.name }.map { "failure:${it.name}" }.toTypedArray(),
    )
}

sealed interface EvolutionCanaryOutcomeWriteResult {
    data class Recorded(val outcome: EvolutionCanaryOutcome) : EvolutionCanaryOutcomeWriteResult
    data class Duplicate(val outcome: EvolutionCanaryOutcome) : EvolutionCanaryOutcomeWriteResult
    data class Conflict(val existingOutcomeId: String) : EvolutionCanaryOutcomeWriteResult
}

interface EvolutionCanaryOutcomeStore {
    suspend fun record(outcome: EvolutionCanaryOutcome): EvolutionCanaryOutcomeWriteResult
    suspend fun outcome(adoptionEvidenceId: String, invocationId: String): EvolutionCanaryOutcome?
    suspend fun outcomes(adoptionEvidenceId: String): List<EvolutionCanaryOutcome>
}

internal class InMemoryEvolutionCanaryOutcomeStore : EvolutionCanaryOutcomeStore {
    private val mutex = Mutex()
    private val values = mutableMapOf<String, LinkedHashMap<String, EvolutionCanaryOutcome>>()

    override suspend fun record(outcome: EvolutionCanaryOutcome): EvolutionCanaryOutcomeWriteResult = mutex.withLock {
        val ledger = values.getOrPut(outcome.adoptionEvidenceId) { linkedMapOf() }
        val existing = ledger[outcome.invocationId]
        if (existing != null) {
            return@withLock if (existing.inputFingerprint == outcome.inputFingerprint) {
                EvolutionCanaryOutcomeWriteResult.Duplicate(existing)
            } else {
                EvolutionCanaryOutcomeWriteResult.Conflict(existing.id)
            }
        }
        ledger[outcome.invocationId] = outcome
        EvolutionCanaryOutcomeWriteResult.Recorded(outcome)
    }

    override suspend fun outcome(adoptionEvidenceId: String, invocationId: String): EvolutionCanaryOutcome? =
        mutex.withLock { values[adoptionEvidenceId]?.get(invocationId) }

    override suspend fun outcomes(adoptionEvidenceId: String): List<EvolutionCanaryOutcome> = mutex.withLock {
        values[adoptionEvidenceId]?.values?.sortedBy { it.invocationId }.orEmpty()
    }
}

enum class EvolutionCanaryStopReason {
    HARD_FAILURE,
    TRIAL_LEDGER_SYNC_FAILED,
}

data class EvolutionCanaryKillSwitchEvidence(
    val adoptionEvidenceId: String,
    val candidateToolId: String,
    val reason: EvolutionCanaryStopReason,
    val triggerOutcomeId: String,
    val hardFailures: Set<EvolutionHardFailure>,
    val trippedAt: Instant,
) {
    init {
        require(adoptionEvidenceId.isNotBlank())
        require(candidateToolId.isNotBlank())
        require(triggerOutcomeId.isNotBlank())
        if (reason == EvolutionCanaryStopReason.HARD_FAILURE) {
            require(hardFailures.isNotEmpty()) { "Hard-failure stop requires hard failure evidence" }
        }
    }

    val id: String = StableFieldIds.fingerprint(
        "evolution-canary-kill-switch/v1",
        adoptionEvidenceId,
        candidateToolId,
        reason.name,
        triggerOutcomeId,
        trippedAt.toString(),
        *hardFailures.sortedBy { it.name }.map { "failure:${it.name}" }.toTypedArray(),
    )
}

interface EvolutionCanaryControlStore {
    suspend fun trip(evidence: EvolutionCanaryKillSwitchEvidence): EvolutionCanaryKillSwitchEvidence
    suspend fun killSwitch(adoptionEvidenceId: String): EvolutionCanaryKillSwitchEvidence?
}

/**
 * J07 requires budget reservations and the kill switch to share one durable state boundary. This
 * prevents callers from routing through one store while recording failures into another.
 */
interface EvolutionCanaryRuntimeStore : EvolutionCanaryBudgetStore, EvolutionCanaryControlStore

data class EvolutionCanaryOutcomeRecordResult(
    val outcome: EvolutionCanaryOutcome,
    val duplicate: Boolean,
    val killSwitch: EvolutionCanaryKillSwitchEvidence?,
)

/**
 * Records only outcomes backed by an exact J06 reservation. New outcomes are mirrored into the
 * existing J03 trial ledger so canary failures cannot be hidden from promotion eligibility.
 * V15 lifecycle tracing is observational and is emitted only after durable outcome state exists.
 */
class EvolutionCanaryOutcomeCoordinator(
    private val runtimeStore: EvolutionCanaryRuntimeStore,
    private val outcomeStore: EvolutionCanaryOutcomeStore,
    private val lifecycle: GeneratedToolLifecycleCoordinator,
    private val lifecycleTraceRecorder: LifecycleDecisionTraceRecorder? =
        LifecycleDecisionTraceRuntimeRegistry.currentOrNull(),
) {
    private val trustedAdoptionGate = EvolutionAdoptionGate()
    private val mutex = Mutex()

    suspend fun record(
        evidence: EvolutionCanaryEvidenceBundle,
        input: EvolutionCanaryOutcomeInput,
    ): EvolutionCanaryOutcomeRecordResult = mutex.withLock {
        val existing = outcomeStore.outcome(evidence.adoptionEvidence.id, input.invocationId)
        if (existing != null) {
            require(existing.inputFingerprint == input.fingerprint) {
                "Conflicting retry for canary invocation ${input.invocationId}"
            }
            require(existing.candidateToolId == evidence.subject.candidateToolId)
            require(existing.candidateRecordFingerprint == evidence.subject.candidateRecordFingerprint)
            return@withLock traced(
                EvolutionCanaryOutcomeRecordResult(
                    outcome = existing,
                    duplicate = true,
                    killSwitch = runtimeStore.killSwitch(evidence.adoptionEvidence.id),
                )
            )
        }

        val replayed = trustedAdoptionGate.evaluate(
            subject = evidence.subject,
            dataset = evidence.dataset,
            report = evidence.evaluationReport,
            cases = evidence.cases,
            observations = evidence.observations,
            currentCandidate = evidence.currentCandidate,
            currentBaseline = evidence.currentBaseline,
            request = evidence.adoptionRequest,
        )
        require(replayed.id == evidence.adoptionEvidence.id) {
            "Canary outcome requires exact trusted J05 replay"
        }
        require(evidence.currentCandidate.state == GeneratedToolState.TRIAL) {
            "Canary outcome candidate must still be TRIAL"
        }
        require(runtimeStore.killSwitch(evidence.adoptionEvidence.id) == null) {
            "Cannot add a new outcome after the canary kill switch has tripped"
        }

        val reservation = requireNotNull(
            runtimeStore.reservation(evidence.adoptionEvidence.id, input.invocationId)
        ) { "Canary outcome requires an existing J06 reservation" }
        require(reservation.id == input.reservationId) {
            "Canary outcome reservation id does not match budget ledger"
        }

        val now = Instant.now()
        val outcome = EvolutionCanaryOutcome(
            adoptionEvidenceId = evidence.adoptionEvidence.id,
            reservationId = reservation.id,
            candidateToolId = evidence.currentCandidate.manifest.toolId,
            candidateRecordFingerprint = evidence.currentCandidate.evolutionFingerprint(),
            invocationId = input.invocationId,
            inputFingerprint = input.fingerprint,
            success = input.success,
            producedExpectedOutput = input.producedExpectedOutput,
            outputFingerprint = input.outputFingerprint,
            latencyMs = input.latencyMs,
            hardFailures = input.hardFailures,
            recordedAt = now,
        )

        when (val write = outcomeStore.record(outcome)) {
            is EvolutionCanaryOutcomeWriteResult.Conflict ->
                error("Conflicting canary outcome already recorded: ${write.existingOutcomeId}")
            is EvolutionCanaryOutcomeWriteResult.Duplicate ->
                return@withLock traced(
                    EvolutionCanaryOutcomeRecordResult(
                        write.outcome,
                        duplicate = true,
                        killSwitch = runtimeStore.killSwitch(evidence.adoptionEvidence.id),
                    )
                )
            is EvolutionCanaryOutcomeWriteResult.Recorded -> Unit
        }

        var killSwitch: EvolutionCanaryKillSwitchEvidence? = null
        if (outcome.hardFailures.isNotEmpty()) {
            killSwitch = runtimeStore.trip(
                EvolutionCanaryKillSwitchEvidence(
                    adoptionEvidenceId = outcome.adoptionEvidenceId,
                    candidateToolId = outcome.candidateToolId,
                    reason = EvolutionCanaryStopReason.HARD_FAILURE,
                    triggerOutcomeId = outcome.id,
                    hardFailures = outcome.hardFailures,
                    trippedAt = now,
                )
            )
        }

        try {
            lifecycle.recordTrial(
                outcome.candidateToolId,
                GeneratedToolTrialResult(
                    invocationId = outcome.invocationId,
                    success = outcome.success && outcome.hardFailures.isEmpty(),
                    producedExpectedOutput = outcome.producedExpectedOutput,
                    safetyViolation = outcome.hardFailures.isNotEmpty(),
                    latencyMs = outcome.latencyMs,
                    recordedAt = outcome.recordedAt,
                )
            )
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                val syncFailure = tripLedgerSyncFailure(outcome, now)
                lifecycleTraceRecorder?.recordEvolutionOutcome(outcome, syncFailure)
            }
            throw cancelled
        } catch (failure: Exception) {
            val syncFailure = tripLedgerSyncFailure(outcome, now)
            lifecycleTraceRecorder?.recordEvolutionOutcome(outcome, syncFailure)
            throw failure
        }

        traced(EvolutionCanaryOutcomeRecordResult(outcome, duplicate = false, killSwitch = killSwitch))
    }

    private suspend fun traced(result: EvolutionCanaryOutcomeRecordResult): EvolutionCanaryOutcomeRecordResult {
        lifecycleTraceRecorder?.recordEvolutionOutcome(result.outcome, result.killSwitch)
        return result
    }

    private suspend fun tripLedgerSyncFailure(
        outcome: EvolutionCanaryOutcome,
        occurredAt: Instant,
    ): EvolutionCanaryKillSwitchEvidence = runtimeStore.trip(
        EvolutionCanaryKillSwitchEvidence(
            adoptionEvidenceId = outcome.adoptionEvidenceId,
            candidateToolId = outcome.candidateToolId,
            reason = EvolutionCanaryStopReason.TRIAL_LEDGER_SYNC_FAILED,
            triggerOutcomeId = outcome.id,
            hardFailures = outcome.hardFailures,
            trippedAt = occurredAt,
        )
    )
}

enum class EvolutionCanaryReadinessDecision {
    READY_FOR_PROMOTION_REVIEW,
    INSUFFICIENT_EVIDENCE,
    NOT_READY,
    STOPPED,
}

data class EvolutionCanaryReadinessPolicy(
    val minimumCompletedOutcomes: Int = 5,
    val minimumSuccessRate: Double = 1.0,
    val minimumExpectedOutputRate: Double = 1.0,
    val maximumLatencyRatioVsShadowBaseline: Double = 1.50,
) {
    init {
        require(minimumCompletedOutcomes > 0)
        require(minimumSuccessRate in 0.0..1.0)
        require(minimumExpectedOutputRate in 0.0..1.0)
        require(maximumLatencyRatioVsShadowBaseline.isFinite() && maximumLatencyRatioVsShadowBaseline > 0.0)
    }

    val id: String = StableFieldIds.fingerprint(
        "evolution-canary-readiness-policy/v1",
        minimumCompletedOutcomes.toString(),
        minimumSuccessRate.toString(),
        minimumExpectedOutputRate.toString(),
        maximumLatencyRatioVsShadowBaseline.toString(),
    )
}

data class EvolutionCanaryReadinessEvidence(
    val adoptionEvidenceId: String,
    val candidateToolId: String,
    val candidateRecordFingerprint: String,
    val baselineDescriptorFingerprint: String,
    val policyId: String,
    val reservedInvocations: Int,
    val completedOutcomes: Int,
    val successes: Int,
    val expectedOutputs: Int,
    val averageLatencyMs: Double,
    val decision: EvolutionCanaryReadinessDecision,
    val reasons: List<String>,
    val outcomeEvidenceIds: List<String>,
    val killSwitchEvidenceId: String? = null,
) {
    init {
        require(adoptionEvidenceId.isNotBlank())
        require(candidateToolId.isNotBlank())
        require(candidateRecordFingerprint.isNotBlank())
        require(baselineDescriptorFingerprint.isNotBlank())
        require(policyId.isNotBlank())
        require(reservedInvocations >= 0 && completedOutcomes >= 0)
        require(successes in 0..completedOutcomes)
        require(expectedOutputs in 0..completedOutcomes)
        require(averageLatencyMs >= 0.0)
        require(reasons.isNotEmpty())
        require(killSwitchEvidenceId == null || killSwitchEvidenceId.isNotBlank())
    }

    val successRate: Double = if (completedOutcomes == 0) 0.0 else successes.toDouble() / completedOutcomes
    val expectedOutputRate: Double = if (completedOutcomes == 0) 0.0 else expectedOutputs.toDouble() / completedOutcomes

    val id: String = StableFieldIds.fingerprint(
        "evolution-canary-readiness-evidence/v1",
        adoptionEvidenceId,
        candidateToolId,
        candidateRecordFingerprint,
        baselineDescriptorFingerprint,
        policyId,
        reservedInvocations.toString(),
        completedOutcomes.toString(),
        successes.toString(),
        expectedOutputs.toString(),
        averageLatencyMs.toString(),
        decision.name,
        killSwitchEvidenceId.orEmpty(),
        *reasons.sorted().map { "reason:$it" }.toTypedArray(),
        *outcomeEvidenceIds.sorted().map { "outcome:$it" }.toTypedArray(),
    )

    /** J07 readiness is evidence for review only and cannot activate or promote a tool. */
    val activationAllowed: Boolean = false
}

class EvolutionCanaryReadinessGate(
    private val runtimeStore: EvolutionCanaryRuntimeStore,
    private val outcomeStore: EvolutionCanaryOutcomeStore,
) {
    private val trustedAdoptionGate = EvolutionAdoptionGate()
    private val policy = EvolutionCanaryReadinessPolicy()

    suspend fun evaluate(evidence: EvolutionCanaryEvidenceBundle): EvolutionCanaryReadinessEvidence {
        val replayed = trustedAdoptionGate.evaluate(
            evidence.subject,
            evidence.dataset,
            evidence.evaluationReport,
            evidence.cases,
            evidence.observations,
            evidence.currentCandidate,
            evidence.currentBaseline,
            evidence.adoptionRequest,
        )
        require(replayed.id == evidence.adoptionEvidence.id) {
            "Canary readiness requires exact trusted J05 replay"
        }
        require(evidence.currentCandidate.state == GeneratedToolState.TRIAL) {
            "Canary readiness candidate must remain TRIAL"
        }

        val outcomes = outcomeStore.outcomes(evidence.adoptionEvidence.id)
        require(outcomes.all { it.candidateToolId == evidence.subject.candidateToolId })
        require(outcomes.all { it.candidateRecordFingerprint == evidence.subject.candidateRecordFingerprint })

        val reserved = runtimeStore.usedInvocations(evidence.adoptionEvidence.id)
        val completed = outcomes.size
        require(completed <= reserved) { "Canary outcomes exceed reserved invocation count" }

        val successes = outcomes.count { it.success && it.hardFailures.isEmpty() }
        val expected = outcomes.count { it.producedExpectedOutput }
        val averageLatency = outcomes.map { it.latencyMs.toDouble() }.takeIf { it.isNotEmpty() }?.average() ?: 0.0
        val reasons = mutableListOf<String>()
        val killSwitch = runtimeStore.killSwitch(evidence.adoptionEvidence.id)

        val decision = when {
            killSwitch != null -> {
                reasons += "canary-stopped:${killSwitch.reason.name}"
                EvolutionCanaryReadinessDecision.STOPPED
            }
            outcomes.any { it.hardFailures.isNotEmpty() } -> {
                reasons += "hard-failure-present"
                EvolutionCanaryReadinessDecision.STOPPED
            }
            completed < policy.minimumCompletedOutcomes -> {
                reasons += "insufficient-outcomes:$completed<${policy.minimumCompletedOutcomes}"
                EvolutionCanaryReadinessDecision.INSUFFICIENT_EVIDENCE
            }
            completed != reserved -> {
                reasons += "pending-outcomes:${reserved - completed}"
                EvolutionCanaryReadinessDecision.INSUFFICIENT_EVIDENCE
            }
            successes.toDouble() / completed < policy.minimumSuccessRate -> {
                reasons += "success-rate:${successes.toDouble() / completed}<${policy.minimumSuccessRate}"
                EvolutionCanaryReadinessDecision.NOT_READY
            }
            expected.toDouble() / completed < policy.minimumExpectedOutputRate -> {
                reasons += "expected-output-rate:${expected.toDouble() / completed}<${policy.minimumExpectedOutputRate}"
                EvolutionCanaryReadinessDecision.NOT_READY
            }
            !latencyWithinPolicy(averageLatency, evidence.evaluationReport.baselineStats.meanLatencyMs) -> {
                reasons += "latency-regression"
                EvolutionCanaryReadinessDecision.NOT_READY
            }
            else -> {
                reasons += "ready-for-explicit-promotion-review"
                EvolutionCanaryReadinessDecision.READY_FOR_PROMOTION_REVIEW
            }
        }

        return EvolutionCanaryReadinessEvidence(
            adoptionEvidenceId = evidence.adoptionEvidence.id,
            candidateToolId = evidence.subject.candidateToolId,
            candidateRecordFingerprint = evidence.subject.candidateRecordFingerprint,
            baselineDescriptorFingerprint = evidence.subject.baselineDescriptorFingerprint,
            policyId = policy.id,
            reservedInvocations = reserved,
            completedOutcomes = completed,
            successes = successes,
            expectedOutputs = expected,
            averageLatencyMs = averageLatency,
            decision = decision,
            reasons = reasons,
            outcomeEvidenceIds = outcomes.map { it.id }.sorted(),
            killSwitchEvidenceId = killSwitch?.id,
        )
    }

    private fun latencyWithinPolicy(candidateMean: Double, baselineMean: Double): Boolean = when {
        baselineMean == 0.0 -> candidateMean == 0.0
        else -> candidateMean / baselineMean <= policy.maximumLatencyRatioVsShadowBaseline
    }
}
