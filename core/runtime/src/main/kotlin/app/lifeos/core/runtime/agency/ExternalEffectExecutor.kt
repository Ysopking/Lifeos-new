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
    data class ChallengeRequired(
        val challengeId: String,
        val reason: String,
    ) : ExternalTransportResult {
        init {
            require(challengeId.isNotBlank())
            require(reason.isNotBlank())
        }
    }
}

fun interface ExternalEffectTransport {
    suspend fun execute(
        contract: ExternalActionContract,
        payload: ByteArray,
        challengeResolution: ExternalChallengeResolution?,
    ): ExternalTransportResult
}

fun interface ExternalObservationReconciler {
    suspend fun reconcile(
        contract: ExternalActionContract,
        previous: EffectReceipt,
    ): ExternalTransportResult
}

interface ExternalEffectExecutor {
    suspend fun execute(contract: ExternalActionContract): EffectReceipt
    suspend fun resumeAfterChallenge(
        contract: ExternalActionContract,
        resolution: ExternalChallengeResolution,
    ): EffectReceipt
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
    private val payloads: ExternalPayloadRepository,
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
            return when (previous.state) {
                ExternalEffectState.UNKNOWN_OUTCOME,
                ExternalEffectState.RESUMED -> reconcile(contract)

                ExternalEffectState.USER_CHALLENGE_REQUIRED,
                ExternalEffectState.WAITING_FOR_USER,
                ExternalEffectState.CONFIRMED,
                ExternalEffectState.REJECTED,
                ExternalEffectState.FAILED -> previous
            }
        }

        val payload = loadVerifiedPayload(contract) ?: return persist(
            contract = contract,
            state = ExternalEffectState.FAILED,
            detail = "external-payload-missing-or-corrupt",
        )
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
                transport.execute(contract, payload, null)
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

    override suspend fun resumeAfterChallenge(
        contract: ExternalActionContract,
        resolution: ExternalChallengeResolution,
    ): EffectReceipt {
        val previous = requireNotNull(receipts.load(contract.actionId)) {
            "Cannot resume external action without a durable challenge receipt"
        }
        require(previous.idempotencyKey == contract.idempotencyKey)
        require(
            previous.state == ExternalEffectState.WAITING_FOR_USER ||
                previous.state == ExternalEffectState.USER_CHALLENGE_REQUIRED
        ) { "External action is not waiting for a user challenge" }
        require(previous.challengeId == resolution.challengeId) {
            "External challenge resolution belongs to another challenge"
        }
        if (!resolution.confirmedByUser) {
            return persist(
                contract = contract,
                state = ExternalEffectState.REJECTED,
                detail = "external-challenge-not-confirmed",
                challengeId = previous.challengeId,
                challengeResolutionFingerprint = resolution.fingerprint,
            )
        }

        val payload = loadVerifiedPayload(contract) ?: return persist(
            contract = contract,
            state = ExternalEffectState.FAILED,
            detail = "external-payload-missing-or-corrupt",
            challengeId = previous.challengeId,
            challengeResolutionFingerprint = resolution.fingerprint,
        )
        val prepared = when (val result = policyGate.prepare(contract.requiredOwnerPolicy)) {
            is OwnerEffectPreparationResult.Ready -> result.preparation
            is OwnerEffectPreparationResult.Blocked -> return persist(
                contract = contract,
                state = ExternalEffectState.REJECTED,
                detail = "owner-policy-blocked-after-challenge",
                challengeId = previous.challengeId,
                challengeResolutionFingerprint = resolution.fingerprint,
            )
        }

        val exposed = try {
            policyGate.expose(
                request = contract.requiredOwnerPolicy,
                prepared = prepared,
            ) {
                persist(
                    contract = contract,
                    state = ExternalEffectState.RESUMED,
                    detail = "external-challenge-resumed",
                    challengeId = previous.challengeId,
                    challengeResolutionFingerprint = resolution.fingerprint,
                )
                transport.execute(contract, payload, resolution)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return persist(
                contract = contract,
                state = ExternalEffectState.UNKNOWN_OUTCOME,
                detail = error.message ?: "transport-exception-after-challenge",
                challengeId = previous.challengeId,
                challengeResolutionFingerprint = resolution.fingerprint,
            )
        }

        return when (exposed) {
            is OwnerEffectExposureResult.Blocked -> persist(
                contract = contract,
                state = ExternalEffectState.REJECTED,
                detail = "owner-policy-revoked-before-resumed-effect",
                challengeId = previous.challengeId,
                challengeResolutionFingerprint = resolution.fingerprint,
            )
            is OwnerEffectExposureResult.Exposed -> fromTransport(contract, exposed.value)
        }
    }

    override suspend fun reconcile(contract: ExternalActionContract): EffectReceipt {
        val previous = requireNotNull(receipts.load(contract.actionId)) {
            "Cannot reconcile external action without a durable receipt"
        }
        require(previous.idempotencyKey == contract.idempotencyKey)
        if (
            previous.state != ExternalEffectState.UNKNOWN_OUTCOME &&
            previous.state != ExternalEffectState.RESUMED
        ) return previous

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
            contract = contract,
            state = ExternalEffectState.WAITING_FOR_USER,
            detail = result.reason,
            challengeId = result.challengeId,
        )
    }

    private suspend fun persist(
        contract: ExternalActionContract,
        state: ExternalEffectState,
        detail: String,
        externalReference: String? = null,
        observationFingerprint: String? = null,
        challengeId: String? = null,
        challengeResolutionFingerprint: String? = null,
    ): EffectReceipt {
        val previous = receipts.load(contract.actionId)
        if (previous != null) {
            require(previous.idempotencyKey == contract.idempotencyKey) {
                "External action id reused with another idempotency key"
            }
            require(allowedTransition(previous.state, state)) {
                "Illegal external effect transition: " + previous.state + " -> " + state
            }
        } else {
            require(state != ExternalEffectState.RESUMED) {
                "External effect cannot start in RESUMED state"
            }
        }
        val receipt = EffectReceipt(
            actionId = contract.actionId,
            idempotencyKey = contract.idempotencyKey,
            state = state,
            recordedAt = now(),
            externalReference = externalReference,
            observationFingerprint = observationFingerprint,
            challengeId = challengeId,
            challengeResolutionFingerprint = challengeResolutionFingerprint,
            detail = detail,
        )
        receipts.save(receipt)
        return receipt
    }

    private fun allowedTransition(
        previous: ExternalEffectState,
        next: ExternalEffectState,
    ): Boolean {
        if (previous == next) {
            return previous in setOf(
                ExternalEffectState.UNKNOWN_OUTCOME,
                ExternalEffectState.WAITING_FOR_USER,
                ExternalEffectState.RESUMED,
            )
        }
        return when (previous) {
            ExternalEffectState.CONFIRMED,
            ExternalEffectState.REJECTED,
            ExternalEffectState.FAILED -> false

            ExternalEffectState.USER_CHALLENGE_REQUIRED,
            ExternalEffectState.WAITING_FOR_USER -> next in setOf(
                ExternalEffectState.RESUMED,
                ExternalEffectState.REJECTED,
                ExternalEffectState.FAILED,
            )

            ExternalEffectState.RESUMED -> next in setOf(
                ExternalEffectState.CONFIRMED,
                ExternalEffectState.REJECTED,
                ExternalEffectState.FAILED,
                ExternalEffectState.UNKNOWN_OUTCOME,
                ExternalEffectState.WAITING_FOR_USER,
            )

            ExternalEffectState.UNKNOWN_OUTCOME -> next in setOf(
                ExternalEffectState.CONFIRMED,
                ExternalEffectState.REJECTED,
                ExternalEffectState.FAILED,
                ExternalEffectState.WAITING_FOR_USER,
            )
        }
    }

    private suspend fun loadVerifiedPayload(contract: ExternalActionContract): ByteArray? {
        val payload = payloads.load(contract.payloadHandle) ?: return null
        return payload.takeIf {
            PayloadHandle.fromPayload(it) == contract.payloadHandle &&
                contract.payloadHandle.fingerprint == contract.payloadFingerprint
        }
    }
}


object ExternalEffectRuntimeRegistry {
    @Volatile
    private var installed: ExternalEffectExecutor? = null

    fun install(executor: ExternalEffectExecutor) {
        installed = executor
    }

    fun currentOrNull(): ExternalEffectExecutor? = installed
}

object ExternalTransportRuntimeRegistry {
    private val transports = java.util.concurrent.ConcurrentHashMap<String, ExternalEffectTransport>()
    private val reconcilers = java.util.concurrent.ConcurrentHashMap<String, ExternalObservationReconciler>()

    fun install(
        scheme: String,
        transport: ExternalEffectTransport,
        reconciler: ExternalObservationReconciler,
    ) {
        require(scheme.matches(Regex("[a-z][a-z0-9+.-]*")))
        transports[scheme] = transport
        reconcilers[scheme] = reconciler
    }

    fun transport(): ExternalEffectTransport = ExternalEffectTransport { contract, payload, challenge ->
        val delegate = transports[contract.endpoint.scheme]
            ?: return@ExternalEffectTransport ExternalTransportResult.ChallengeRequired(
                challengeId = "transport:${contract.endpoint.scheme}",
                reason = "no-transport-installed-for:${contract.endpoint.scheme}",
            )
        delegate.execute(contract, payload, challenge)
    }

    fun reconciler(): ExternalObservationReconciler = ExternalObservationReconciler { contract, previous ->
        val delegate = reconcilers[contract.endpoint.scheme]
            ?: return@ExternalObservationReconciler ExternalTransportResult.Unknown(
                previous.externalReference
            )
        delegate.reconcile(contract, previous)
    }
}
