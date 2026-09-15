package app.lifeos.core.runtime.buildstudio

import app.lifeos.core.runtime.health.HealthGate
import app.lifeos.core.runtime.health.HealthGatePurpose
import app.lifeos.core.runtime.health.HealthGateResult
import app.lifeos.core.runtime.health.HealthNodeId
import app.lifeos.core.runtime.policy.OwnerEffectExposureResult
import app.lifeos.core.runtime.policy.OwnerEffectPreparationResult
import app.lifeos.core.runtime.policy.OwnerEffectRequest
import app.lifeos.core.runtime.policy.OwnerEffectType
import app.lifeos.core.runtime.policy.OwnerPolicyEffectGate
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Runtime implementation must stage without making the candidate productive. */
interface HotSwapRuntimeAdapter {
    suspend fun stage(candidate: VerifiedRuntimeCandidate): HotSwapStage
    suspend fun activate(stage: HotSwapStage)
    suspend fun rollback(stage: HotSwapStage)

    /** Reconstructs a stable stage handle from durable activation state after process restart. */
    suspend fun recoverStage(snapshot: HotSwapActivationSnapshot): HotSwapStage
}

fun interface HotSwapHealthVerifier {
    suspend fun verify(stage: HotSwapStage): HotSwapHealthEvidence
}

fun interface HotSwapOwnerEffectRequestFactory {
    fun request(candidate: VerifiedRuntimeCandidate): OwnerEffectRequest
}

/** Observational only. The owning activation ledger is always persisted before this is invoked. */
fun interface HotSwapActivationTraceRecorder {
    suspend fun record(snapshot: HotSwapActivationSnapshot)
}

sealed interface DurableHotSwapResult {
    val snapshot: HotSwapActivationSnapshot

    data class Activated(
        override val snapshot: HotSwapActivationSnapshot,
        val replayedTerminal: Boolean = false,
    ) : DurableHotSwapResult

    data class RolledBack(
        override val snapshot: HotSwapActivationSnapshot,
        val reason: String,
        val recoveredAfterRestart: Boolean = false,
    ) : DurableHotSwapResult {
        init { require(reason.isNotBlank()) }
    }

    data class Blocked(
        override val snapshot: HotSwapActivationSnapshot,
        val reason: String,
    ) : DurableHotSwapResult {
        init { require(reason.isNotBlank()) }
    }

    /** Rollback itself failed; state deliberately remains non-terminal for mandatory restart retry. */
    data class RecoveryRequired(
        override val snapshot: HotSwapActivationSnapshot,
        val reason: String,
    ) : DurableHotSwapResult {
        init { require(reason.isNotBlank()) }
    }
}

/**
 * Restart-safe activation boundary.
 *
 * Ordering is deliberate: verified candidate -> owner-policy preparation -> non-productive stage ->
 * HealthGate + explicit health verification -> durable activation preparation -> owner-policy JIT
 * recheck immediately around the productive side effect -> durable activation outcome. Any failure
 * after staging attempts rollback. Any non-terminal state found after restart is rolled back instead
 * of resumed, so a crash can never silently upgrade an uncommitted candidate.
 */
class DurableHotSwapCoordinator(
    private val ledger: HotSwapActivationLedger,
    private val runtime: HotSwapRuntimeAdapter,
    private val healthGate: HealthGate,
    private val healthVerifier: HotSwapHealthVerifier,
    private val ownerPolicy: OwnerPolicyEffectGate,
    private val ownerRequestFactory: HotSwapOwnerEffectRequestFactory,
    private val now: () -> Instant = Instant::now,
    private val traceRecorder: HotSwapActivationTraceRecorder? = null,
) {
    suspend fun activate(candidate: VerifiedRuntimeCandidate): DurableHotSwapResult {
        var snapshot = ledger.open(candidate)
        terminal(snapshot)?.let { return it }
        if (snapshot.state != HotSwapActivationState.PREPARED) {
            return recoverOne(snapshot)
        }

        val request = ownerRequestFactory.request(candidate)
        require(request.effect == OwnerEffectType.PROVIDER_ACTIVATION) {
            "Hot-swap activation must use PROVIDER_ACTIVATION owner effect"
        }
        val prepared = when (val result = ownerPolicy.prepare(request)) {
            is OwnerEffectPreparationResult.Blocked -> {
                snapshot = ledger.markBlocked(
                    snapshot,
                    "owner-policy-blocked:${result.assessment.reasonCodes.joinToString(",") { it.name }}",
                )
                traceTerminal(snapshot)
                return DurableHotSwapResult.Blocked(snapshot, snapshot.lastDetail ?: "owner-policy-blocked")
            }
            is OwnerEffectPreparationResult.Ready -> result
        }

        var stage: HotSwapStage? = null
        try {
            stage = runtime.stage(candidate)
            require(stage.verifiedCandidateId == candidate.id) { "Runtime stage changed verified candidate identity" }
            require(stage.candidateId == candidate.candidateId) { "Runtime stage changed candidate identity" }
            snapshot = ledger.markStaged(snapshot, stage)

            val nodeId = healthNode(candidate)
            val permit = when (val gate = healthGate.acquire(nodeId, now(), HealthGatePurpose.NORMAL)) {
                is HealthGateResult.Granted -> gate.permit
                is HealthGateResult.BlockedByProtection -> {
                    return rollback(
                        snapshot,
                        stage,
                        "health-gate-protection:${gate.state.mode.name}",
                    )
                }
                is HealthGateResult.BlockedByQuarantine -> {
                    return rollback(snapshot, stage, "health-gate-quarantine:${gate.entry.nodeId.value}")
                }
                is HealthGateResult.BlockedByCircuit -> {
                    return rollback(snapshot, stage, "health-gate-circuit:${gate.state.name}")
                }
            }

            val health = try {
                healthVerifier.verify(stage)
            } catch (cancelled: CancellationException) {
                healthGate.onFailure(permit, now())
                throw cancelled
            } catch (error: Exception) {
                healthGate.onFailure(permit, now())
                return rollback(snapshot, stage, "health-verifier-exception:${safeFailure(error)}")
            }
            if (!health.healthy) {
                healthGate.onFailure(permit, now())
                return rollback(snapshot, stage, "health-verification-failed:${health.detail}")
            }
            healthGate.onSuccess(permit)
            snapshot = ledger.markHealthVerified(snapshot, health)
            snapshot = ledger.markActivationPrepared(snapshot, prepared.assessment.decisionId.value)

            when (val exposure = ownerPolicy.expose(request, prepared.preparation) {
                runtime.activate(stage)
            }) {
                is OwnerEffectExposureResult.Blocked -> {
                    return rollback(
                        snapshot,
                        stage,
                        "owner-policy-jit-blocked:${exposure.assessment.reasonCodes.joinToString(",") { it.name }}",
                    )
                }
                is OwnerEffectExposureResult.Exposed -> Unit
            }

            snapshot = ledger.markActivated(snapshot)
            traceTerminal(snapshot)
            return DurableHotSwapResult.Activated(snapshot)
        } catch (cancelled: CancellationException) {
            val durable = snapshot
            val staged = stage
            if (staged != null && !durable.terminal) {
                withContext(NonCancellable) {
                    rollback(durable, staged, "activation-cancelled")
                }
            }
            throw cancelled
        } catch (error: Exception) {
            val staged = stage
            return if (staged != null && !snapshot.terminal) {
                rollback(snapshot, staged, "activation-exception:${safeFailure(error)}")
            } else {
                snapshot = ledger.markBlocked(snapshot, "stage-exception:${safeFailure(error)}")
                traceTerminal(snapshot)
                DurableHotSwapResult.Blocked(snapshot, snapshot.lastDetail ?: "stage-exception")
            }
        }
    }

    /** Mandatory boot/recovery hook: every uncommitted activation is reverted, never resumed. */
    suspend fun recoverPending(): List<DurableHotSwapResult> =
        ledger.pending().map { snapshot -> recoverOne(snapshot) }

    private suspend fun recoverOne(snapshot: HotSwapActivationSnapshot): DurableHotSwapResult {
        if (snapshot.terminal) return requireNotNull(terminal(snapshot))
        if (snapshot.stageId == null) {
            val rolled = ledger.markRolledBack(snapshot, "restart-before-stage-fail-closed")
            traceTerminal(rolled)
            return DurableHotSwapResult.RolledBack(
                rolled,
                rolled.lastDetail ?: "restart-before-stage-fail-closed",
                recoveredAfterRestart = true,
            )
        }
        val stage = try {
            runtime.recoverStage(snapshot)
        } catch (error: Exception) {
            return DurableHotSwapResult.RecoveryRequired(
                snapshot,
                "stage-recovery-failed:${safeFailure(error)}",
            )
        }
        return rollback(
            snapshot,
            stage,
            "restart-nonterminal-activation-fail-closed",
            recoveredAfterRestart = true,
        )
    }

    private suspend fun rollback(
        snapshot: HotSwapActivationSnapshot,
        stage: HotSwapStage,
        reason: String,
        recoveredAfterRestart: Boolean = false,
    ): DurableHotSwapResult {
        return try {
            withContext(NonCancellable) { runtime.rollback(stage) }
            val rolled = ledger.markRolledBack(snapshot, reason)
            traceTerminal(rolled)
            DurableHotSwapResult.RolledBack(rolled, reason, recoveredAfterRestart)
        } catch (error: Exception) {
            DurableHotSwapResult.RecoveryRequired(
                snapshot,
                "rollback-failed:${safeFailure(error)};cause:$reason",
            )
        }
    }

    private fun terminal(snapshot: HotSwapActivationSnapshot): DurableHotSwapResult? = when (snapshot.state) {
        HotSwapActivationState.ACTIVATED -> DurableHotSwapResult.Activated(snapshot, replayedTerminal = true)
        HotSwapActivationState.ROLLED_BACK -> DurableHotSwapResult.RolledBack(
            snapshot,
            snapshot.lastDetail ?: "rolled-back",
        )
        HotSwapActivationState.BLOCKED -> DurableHotSwapResult.Blocked(
            snapshot,
            snapshot.lastDetail ?: "blocked",
        )
        else -> null
    }

    private suspend fun traceTerminal(snapshot: HotSwapActivationSnapshot) {
        val recorder = traceRecorder ?: return
        try {
            recorder.record(snapshot)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // V15 projection is observational. Durable activation state remains authoritative.
        }
    }

    private fun healthNode(candidate: VerifiedRuntimeCandidate): HealthNodeId =
        HealthNodeId("buildstudio-hot-swap:${candidate.candidateId.take(96)}")

    private fun safeFailure(error: Exception): String =
        "${error::class.simpleName ?: "Exception"}:${error.message.orEmpty().take(160)}"
}
