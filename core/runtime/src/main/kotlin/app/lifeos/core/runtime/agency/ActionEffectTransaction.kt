package app.lifeos.core.runtime.agency

import app.lifeos.core.runtime.policy.OwnerEffectPreparationResult
import app.lifeos.core.runtime.policy.OwnerEffectRequest
import app.lifeos.core.runtime.policy.OwnerPolicyAssessment
import app.lifeos.core.runtime.policy.OwnerPolicyEffectGate
import app.lifeos.core.runtime.trace.DecisionTraceId
import app.lifeos.core.runtime.trace.LifecycleDecisionTraceRecorder
import app.lifeos.core.runtime.trace.LifecycleDecisionTraceRuntimeRegistry
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.CancellationException

@JvmInline
value class ActionContractId(val value: String) {
    init {
        require(value.startsWith(PREFIX))
        require(value.removePrefix(PREFIX).matches(Regex("[0-9a-f]{64}")))
    }

    override fun toString(): String = value

    companion object { const val PREFIX = "action-contract:" }
}

/**
 * Immutable side-effect contract. Any mutation to request, expected effect or causal intent creates
 * a different id and therefore invalidates a previous preparation/approval binding.
 */
data class ActionContract(
    val id: ActionContractId,
    val traceId: DecisionTraceId,
    val intentId: String,
    val request: OwnerEffectRequest,
    val expectedEffectFingerprint: String,
    val frozenAt: Instant,
) {
    init {
        require(intentId.isNotBlank())
        require(expectedEffectFingerprint.isNotBlank())
        require(id == expectedId(traceId, intentId, request, expectedEffectFingerprint, frozenAt))
    }

    val idempotencyKey: String get() = id.value

    companion object {
        fun create(
            traceId: DecisionTraceId,
            intentId: String,
            request: OwnerEffectRequest,
            expectedEffectFingerprint: String,
            frozenAt: Instant,
        ): ActionContract = ActionContract(
            id = expectedId(traceId, intentId, request, expectedEffectFingerprint, frozenAt),
            traceId = traceId,
            intentId = intentId,
            request = request,
            expectedEffectFingerprint = expectedEffectFingerprint,
            frozenAt = frozenAt,
        )

        private fun expectedId(
            traceId: DecisionTraceId,
            intentId: String,
            request: OwnerEffectRequest,
            expectedEffectFingerprint: String,
            frozenAt: Instant,
        ): ActionContractId = ActionContractId(
            ActionContractId.PREFIX + exactSha256(
                "action-contract/v1",
                traceId.value,
                intentId,
                OwnerPolicyEffectGate.requestFingerprint(request),
                expectedEffectFingerprint,
                frozenAt.toString(),
            )
        )
    }
}

enum class ActionEffectStatus {
    SUCCEEDED,
    DENIED,
    UNKNOWN_OUTCOME,
}

data class ActionEffectVerification(
    val confirmed: Boolean,
    val observedEffectId: String? = null,
    val detail: String? = null,
) {
    init {
        require(observedEffectId == null || observedEffectId.isNotBlank())
        require(detail == null || detail.isNotBlank())
        require(!confirmed || observedEffectId != null) {
            "Confirmed action effects require an observed external effect id"
        }
    }
}

data class ActionEffectReceipt(
    val contractId: ActionContractId,
    val traceId: DecisionTraceId,
    val status: ActionEffectStatus,
    val policyAssessment: OwnerPolicyAssessment,
    val observedEffectId: String? = null,
    val detail: String? = null,
    val recordedAt: Instant,
) {
    init {
        require(observedEffectId == null || observedEffectId.isNotBlank())
        require(detail == null || detail.isNotBlank())
        if (status == ActionEffectStatus.SUCCEEDED) require(observedEffectId != null)
    }
}

data class ActionEffectResult<T>(
    val receipt: ActionEffectReceipt,
    val output: T? = null,
)

fun interface ActionEffectExecutor<T> {
    suspend fun execute(contract: ActionContract): T
}

fun interface ActionEffectVerifier<T> {
    suspend fun verify(contract: ActionContract, output: T): ActionEffectVerification
}

/**
 * Canonical external-effect boundary:
 *
 * PLAN/FREEZE happens before this coordinator. execute() then performs AUTHORIZE -> PREPARE ->
 * COMMIT -> VERIFY -> RECEIPT. It never retries an exception from COMMIT because the external
 * outcome may already have happened; such cases are explicitly UNKNOWN_OUTCOME and must be
 * reconciled by observation before any later retry.
 */
class ActionEffectTransactionCoordinator(
    private val effectGate: OwnerPolicyEffectGate,
    private val traceRecorder: LifecycleDecisionTraceRecorder? =
        LifecycleDecisionTraceRuntimeRegistry.currentOrNull(),
    private val now: () -> Instant = Instant::now,
) {
    suspend fun <T> execute(
        contract: ActionContract,
        executor: ActionEffectExecutor<T>,
        verifier: ActionEffectVerifier<T>,
    ): ActionEffectResult<T> {
        val preparation = effectGate.prepare(contract.request)
        if (preparation is OwnerEffectPreparationResult.Blocked) {
            return result(
                contract = contract,
                status = ActionEffectStatus.DENIED,
                assessment = preparation.assessment,
                detail = "owner-policy-denied-before-commit",
            )
        }
        preparation as OwnerEffectPreparationResult.Ready

        val exposure = try {
            effectGate.expose(
                request = contract.request,
                prepared = preparation.preparation,
            ) {
                executor.execute(contract)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return result(
                contract = contract,
                status = ActionEffectStatus.UNKNOWN_OUTCOME,
                assessment = preparation.assessment,
                detail = error.message ?: error::class.simpleName.orEmpty().ifBlank { "commit-outcome-unknown" },
            )
        }

        return when (exposure) {
            is app.lifeos.core.runtime.policy.OwnerEffectExposureResult.Blocked -> result(
                contract = contract,
                status = ActionEffectStatus.DENIED,
                assessment = exposure.assessment,
                detail = "owner-policy-changed-before-commit",
            )
            is app.lifeos.core.runtime.policy.OwnerEffectExposureResult.Exposed -> {
                val verification = try {
                    verifier.verify(contract, exposure.value)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    return result(
                        contract = contract,
                        status = ActionEffectStatus.UNKNOWN_OUTCOME,
                        assessment = exposure.assessment,
                        output = exposure.value,
                        detail = error.message ?: error::class.simpleName.orEmpty().ifBlank {
                            "effect-verification-unavailable"
                        },
                    )
                }
                result(
                    contract = contract,
                    status = if (verification.confirmed) {
                        ActionEffectStatus.SUCCEEDED
                    } else {
                        ActionEffectStatus.UNKNOWN_OUTCOME
                    },
                    assessment = exposure.assessment,
                    output = exposure.value,
                    observedEffectId = verification.observedEffectId,
                    detail = verification.detail ?: if (verification.confirmed) {
                        "effect-verified"
                    } else {
                        "effect-not-yet-verifiable"
                    },
                )
            }
        }
    }

    private suspend fun <T> result(
        contract: ActionContract,
        status: ActionEffectStatus,
        assessment: OwnerPolicyAssessment,
        output: T? = null,
        observedEffectId: String? = null,
        detail: String? = null,
    ): ActionEffectResult<T> {
        val receipt = ActionEffectReceipt(
            contractId = contract.id,
            traceId = contract.traceId,
            status = status,
            policyAssessment = assessment,
            observedEffectId = observedEffectId,
            detail = detail,
            recordedAt = now(),
        )
        traceRecorder?.recordActionEffect(contract, receipt)
        return ActionEffectResult(receipt = receipt, output = output)
    }
}

private fun exactSha256(vararg parts: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    parts.forEach { part ->
        val bytes = part.toByteArray(Charsets.UTF_8)
        digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
        digest.update(bytes)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
