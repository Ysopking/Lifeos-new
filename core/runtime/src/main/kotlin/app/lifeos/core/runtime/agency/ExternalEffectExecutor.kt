package app.lifeos.core.runtime.agency

import app.lifeos.core.runtime.policy.OwnerEffectPreparation
import app.lifeos.core.runtime.policy.OwnerEffectPreparationResult
import app.lifeos.core.runtime.policy.OwnerEffectExposureResult
import app.lifeos.core.runtime.policy.OwnerPolicyEffectGate
import java.time.Instant
import kotlinx.coroutines.CancellationException

sealed interface ExternalTransportResult {
    data class Confirmed(
        val externalReference: String,
        val observationFingerprint: String?,
    ) : ExternalTransportResult
    data class Rejected(val reason: String) : ExternalTransportResult
    data class Failed(val reason: String) : ExternalTransportResult
    data class Unknown(val externalReference: String? = null) : ExternalTransportResult
    data class ChallengeRequired(val reason: String) : ExternalTransportResult
}

fun interface ExternalEffectTransport {
    suspend fun execute(contract: ExternalActionContract): ExternalTransportResult
}

fun interface ExternalObservationReconciler {
    suspend fun reconcile(
        contract: ExternalActionContract,
        previous: EffectReceipt,
    ): ExternalTransportResult
}

interface ExternalEffectExecutor {
    suspend fun execute(contract: ExternalActionContract): EffectReceipt
    suspend fun reconcile(contract: ExternalActionContract): EffectReceipt
}

/**
 * JIT OwnerPolicy gate around an idempotent endpoint transport.
 *
 * UNKNOWN_OUTCOME is never sent again. A subsequent execute() call reconciles the external
 * observation instead. The receipt store is the durable idempotency boundary for host effects.
 */
class PolicyGatedExternalEffectExecutor(
    private val policyGate: OwnerPolicyEffectGate,
    private val receipts: ExternalEffectReceiptRepository,
    private val transport: ExternalEffectTransport,
    private val observationReconciler: ExternalObservationReconciler,
    private val now: () -> Instant = Instant::now,
) : ExternalEffectExecutor {
    override suspend fun execute(contract: ExternalActionContract): EffectReceipt {
        val previous = receipts.load(contract.actionId)
        if (previous != null) {
            require(previous.idempotencyKey == contract.idempotencyKey) {
                "External action id reused with another idempotency key"
            }
            return if (previous.state == ExternalEffectState.UNKNOWN_OUTCOME) {
                reconcile(contract)
            } else {
                previous
            }
        }

        val prepared = when (val result = policyGate.prepare(contract.requiredOwnerPolicy)) {
            is OwnerEffectPreparationResult.Ready -> result.preparation
            is OwnerEffectPreparationResult.Blocked -> return persist(
                contract,
                ExternalEffectState.REJECTED,
                "owner-policy-blocked",
            )
        }

        val exposed = try {
            policyGate.expose(
                request = contract.requiredOwnerPolicy,
                prepared = prepared,
            ) {
                transport.execute(contract)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return persist(
                contract,
                ExternalEffectState.UNKNOWN_OUTCOME,
                error.message ?: "transport-exception",
            )
        }

        return when (exposed) {
            is OwnerEffectExposureResult.Blocked -> persist(
                contract,
                ExternalEffectState.REJECTED,
                "owner-policy-revoked-before-effect",
            )
            is OwnerEffectExposureResult.Exposed -> fromTransport(contract, exposed.value)
        }
    }

    override suspend fun reconcile(contract: ExternalActionContract): EffectReceipt {
        val previous = requireNotNull(receipts.load(contract.actionId)) {
            "Cannot reconcile external action without a durable receipt"
        }
        require(previous.idempotencyKey == contract.idempotencyKey)
        if (previous.state != ExternalEffectState.UNKNOWN_OUTCOME) return previous

        val result = try {
            observationReconciler.reconcile(contract, previous)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return previous
        }
        return fromTransport(contract, result)
    }

    private suspend fun fromTransport(
        contract: ExternalActionContract,
        result: ExternalTransportResult,
    ): EffectReceipt = when (result) {
        is ExternalTransportResult.Confirmed -> persist(
            contract,
            ExternalEffectState.CONFIRMED,
            detail = "external-effect-confirmed",
            externalReference = result.externalReference,
            observationFingerprint = result.observationFingerprint,
        )
        is ExternalTransportResult.Rejected -> persist(
            contract,
            ExternalEffectState.REJECTED,
            result.reason,
        )
        is ExternalTransportResult.Failed -> persist(
            contract,
            ExternalEffectState.FAILED,
            result.reason,
        )
        is ExternalTransportResult.Unknown -> persist(
            contract,
            ExternalEffectState.UNKNOWN_OUTCOME,
            "external-outcome-unknown",
            externalReference = result.externalReference,
        )
        is ExternalTransportResult.ChallengeRequired -> persist(
            contract,
            ExternalEffectState.USER_CHALLENGE_REQUIRED,
            result.reason,
        )
    }

    private suspend fun persist(
        contract: ExternalActionContract,
        state: ExternalEffectState,
        detail: String,
        externalReference: String? = null,
        observationFingerprint: String? = null,
    ): EffectReceipt {
        val receipt = EffectReceipt(
            actionId = contract.actionId,
            idempotencyKey = contract.idempotencyKey,
            state = state,
            recordedAt = now(),
            externalReference = externalReference,
            observationFingerprint = observationFingerprint,
            detail = detail,
        )
        receipts.save(receipt)
        return receipt
    }
}
