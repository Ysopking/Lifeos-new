package app.lifeos.core.runtime.policy

import app.lifeos.core.runtime.capability.CapabilityId
import app.lifeos.core.runtime.resource.ResourceBudgetReservationId
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.CancellationException

@JvmInline
value class OwnerPolicyDecisionId(val value: String) {
    init {
        require(value.startsWith(PREFIX)) { "Invalid owner policy decision id prefix" }
        require(value.removePrefix(PREFIX).matches(Regex("[0-9a-f]{64}"))) {
            "Invalid owner policy decision id digest"
        }
    }

    override fun toString(): String = value

    companion object {
        const val PREFIX = "owner-policy-decision:"
    }
}

enum class OwnerPolicyReasonCode {
    NO_ACTIVE_GRANT_FOR_ACTOR,
    EFFECT_NOT_GRANTED,
    SCOPE_NOT_GRANTED,
    RESOURCE_NOT_GRANTED,
    GRANT_NOT_CURRENTLY_VALID,
    CAPABILITY_OR_PROVIDER_VERSION_NOT_GRANTED,
    BUDGET_RESERVATION_REQUIRED_OR_MISMATCHED,
    NO_OWNER_POLICY_GRANT_MATCHED,
    POLICY_HISTORY_INVALID,
    POLICY_UNAVAILABLE,
    PREPARED_REQUEST_MISMATCH,
    POLICY_CHANGED_SINCE_PREPARATION,
}

enum class OwnerPolicyEvaluationMode {
    LIVE,
    SIMULATION,
}

/**
 * Explainable V14 assessment around the existing OwnerPolicyDecision. Decision identity is stable
 * for the exact request, policy revision and outcome; simulation/live mode deliberately does not
 * alter identity so a dry-run can be correlated with the later live decision if policy is unchanged.
 */
data class OwnerPolicyAssessment(
    val decisionId: OwnerPolicyDecisionId,
    val policyRevision: Long,
    val mode: OwnerPolicyEvaluationMode,
    val requestFingerprint: String,
    val allowed: Boolean,
    val grantId: OwnerPolicyGrantId? = null,
    val budgetReservationId: ResourceBudgetReservationId? = null,
    val reasonCodes: List<OwnerPolicyReasonCode> = emptyList(),
    val reasons: List<String> = emptyList(),
) {
    init {
        require(policyRevision >= 0L)
        require(requestFingerprint.matches(Regex("[0-9a-f]{64}")))
        if (allowed) {
            require(grantId != null)
            require(reasonCodes.isEmpty())
            require(reasons.isEmpty())
        } else {
            require(grantId == null)
            require(reasonCodes.isNotEmpty())
        }
    }
}

data class OwnerEffectPreparation(
    val requestFingerprint: String,
    val decisionId: OwnerPolicyDecisionId,
    val policyRevision: Long,
    val grantId: OwnerPolicyGrantId,
    val budgetReservationId: ResourceBudgetReservationId?,
)

sealed interface OwnerEffectPreparationResult {
    data class Ready(
        val preparation: OwnerEffectPreparation,
        val assessment: OwnerPolicyAssessment,
    ) : OwnerEffectPreparationResult

    data class Blocked(val assessment: OwnerPolicyAssessment) : OwnerEffectPreparationResult
}

sealed interface OwnerEffectExposureResult<out T> {
    data class Exposed<T>(
        val value: T,
        val assessment: OwnerPolicyAssessment,
    ) : OwnerEffectExposureResult<T>

    data class Blocked(val assessment: OwnerPolicyAssessment) : OwnerEffectExposureResult<Nothing>
}

/**
 * Single JIT exposure gate over OwnerPolicyLedger.
 *
 * prepare() is only a snapshot/binding and never authorizes a later side effect by itself. expose()
 * always reloads policy immediately before invoking the supplied host-effect lambda. Any corrupt or
 * unavailable policy history is converted into an explicit fail-closed assessment and the lambda is
 * not invoked. A prepared binding is exact-request and exact-policy-revision scoped, so restart,
 * revocation or any intervening policy mutation forces a fresh preparation.
 */
class OwnerPolicyEffectGate(
    private val ledger: OwnerPolicyLedger,
) {
    suspend fun simulate(request: OwnerEffectRequest): OwnerPolicyAssessment =
        assess(request, OwnerPolicyEvaluationMode.SIMULATION)

    suspend fun assessLive(request: OwnerEffectRequest): OwnerPolicyAssessment =
        assess(request, OwnerPolicyEvaluationMode.LIVE)

    suspend fun prepare(request: OwnerEffectRequest): OwnerEffectPreparationResult {
        val assessment = assessLive(request)
        if (!assessment.allowed) return OwnerEffectPreparationResult.Blocked(assessment)
        return OwnerEffectPreparationResult.Ready(
            preparation = OwnerEffectPreparation(
                requestFingerprint = assessment.requestFingerprint,
                decisionId = assessment.decisionId,
                policyRevision = assessment.policyRevision,
                grantId = requireNotNull(assessment.grantId),
                budgetReservationId = assessment.budgetReservationId,
            ),
            assessment = assessment,
        )
    }

    suspend fun <T> expose(
        request: OwnerEffectRequest,
        prepared: OwnerEffectPreparation? = null,
        effect: suspend () -> T,
    ): OwnerEffectExposureResult<T> {
        val current = assessLive(request)
        if (!current.allowed) return OwnerEffectExposureResult.Blocked(current)

        if (prepared != null) {
            if (prepared.requestFingerprint != current.requestFingerprint) {
                return OwnerEffectExposureResult.Blocked(
                    syntheticBlock(
                        request = request,
                        policyRevision = current.policyRevision,
                        code = OwnerPolicyReasonCode.PREPARED_REQUEST_MISMATCH,
                        reason = "prepared-request-mismatch",
                    )
                )
            }
            if (
                prepared.policyRevision != current.policyRevision ||
                prepared.grantId != current.grantId ||
                prepared.budgetReservationId != current.budgetReservationId
            ) {
                return OwnerEffectExposureResult.Blocked(
                    syntheticBlock(
                        request = request,
                        policyRevision = current.policyRevision,
                        code = OwnerPolicyReasonCode.POLICY_CHANGED_SINCE_PREPARATION,
                        reason = "policy-changed-since-preparation",
                    )
                )
            }
        }

        return OwnerEffectExposureResult.Exposed(
            value = effect(),
            assessment = current,
        )
    }

    private suspend fun assess(
        request: OwnerEffectRequest,
        mode: OwnerPolicyEvaluationMode,
    ): OwnerPolicyAssessment {
        val fingerprint = requestFingerprint(request)
        val decision = try {
            ledger.evaluate(request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (invalid: IllegalArgumentException) {
            return syntheticBlock(
                request = request,
                policyRevision = 0L,
                code = OwnerPolicyReasonCode.POLICY_HISTORY_INVALID,
                reason = "policy-history-invalid",
                mode = mode,
            )
        } catch (invalid: IllegalStateException) {
            return syntheticBlock(
                request = request,
                policyRevision = 0L,
                code = OwnerPolicyReasonCode.POLICY_HISTORY_INVALID,
                reason = "policy-history-invalid",
                mode = mode,
            )
        } catch (_: Exception) {
            return syntheticBlock(
                request = request,
                policyRevision = 0L,
                code = OwnerPolicyReasonCode.POLICY_UNAVAILABLE,
                reason = "policy-unavailable",
                mode = mode,
            )
        }

        return when (decision) {
            is OwnerPolicyDecision.Allowed -> OwnerPolicyAssessment(
                decisionId = decisionId(
                    requestFingerprint = fingerprint,
                    policyRevision = decision.policyRevision,
                    outcome = "allowed",
                    grantId = decision.grantId,
                    reasonCodes = emptyList(),
                ),
                policyRevision = decision.policyRevision,
                mode = mode,
                requestFingerprint = fingerprint,
                allowed = true,
                grantId = decision.grantId,
                budgetReservationId = decision.budgetReservationId,
            )
            is OwnerPolicyDecision.Blocked -> {
                val codes = decision.reasons.map(::reasonCode).distinct().sortedBy { it.name }
                OwnerPolicyAssessment(
                    decisionId = decisionId(
                        requestFingerprint = fingerprint,
                        policyRevision = decision.policyRevision,
                        outcome = "blocked",
                        grantId = null,
                        reasonCodes = codes,
                    ),
                    policyRevision = decision.policyRevision,
                    mode = mode,
                    requestFingerprint = fingerprint,
                    allowed = false,
                    reasonCodes = codes,
                    reasons = decision.reasons,
                )
            }
        }
    }

    private fun syntheticBlock(
        request: OwnerEffectRequest,
        policyRevision: Long,
        code: OwnerPolicyReasonCode,
        reason: String,
        mode: OwnerPolicyEvaluationMode = OwnerPolicyEvaluationMode.LIVE,
    ): OwnerPolicyAssessment {
        val fingerprint = requestFingerprint(request)
        return OwnerPolicyAssessment(
            decisionId = decisionId(
                requestFingerprint = fingerprint,
                policyRevision = policyRevision,
                outcome = "blocked",
                grantId = null,
                reasonCodes = listOf(code),
            ),
            policyRevision = policyRevision,
            mode = mode,
            requestFingerprint = fingerprint,
            allowed = false,
            reasonCodes = listOf(code),
            reasons = listOf(reason),
        )
    }

    private fun reasonCode(reason: String): OwnerPolicyReasonCode = when {
        reason == "no-active-grant-for-actor" -> OwnerPolicyReasonCode.NO_ACTIVE_GRANT_FOR_ACTOR
        reason.startsWith("effect-not-granted:") -> OwnerPolicyReasonCode.EFFECT_NOT_GRANTED
        reason.startsWith("scope-not-granted:") -> OwnerPolicyReasonCode.SCOPE_NOT_GRANTED
        reason.startsWith("resource-not-granted:") -> OwnerPolicyReasonCode.RESOURCE_NOT_GRANTED
        reason == "grant-not-currently-valid" -> OwnerPolicyReasonCode.GRANT_NOT_CURRENTLY_VALID
        reason == "capability-or-provider-version-not-granted" ->
            OwnerPolicyReasonCode.CAPABILITY_OR_PROVIDER_VERSION_NOT_GRANTED
        reason == "budget-reservation-required-or-mismatched" ->
            OwnerPolicyReasonCode.BUDGET_RESERVATION_REQUIRED_OR_MISMATCHED
        else -> OwnerPolicyReasonCode.NO_OWNER_POLICY_GRANT_MATCHED
    }

    companion object {
        fun requestFingerprint(request: OwnerEffectRequest): String = exactFingerprint(
            "owner-effect-request/v1",
            request.actorId.value,
            request.effect.name,
            request.resource,
            request.scope,
            request.capabilityId?.value,
            request.providerVersion,
            request.budgetAccountId?.value,
            request.budgetReservationId?.value,
        )

        private fun decisionId(
            requestFingerprint: String,
            policyRevision: Long,
            outcome: String,
            grantId: OwnerPolicyGrantId?,
            reasonCodes: List<OwnerPolicyReasonCode>,
        ): OwnerPolicyDecisionId = OwnerPolicyDecisionId(
            OwnerPolicyDecisionId.PREFIX + exactFingerprint(
                "owner-policy-decision/v1",
                requestFingerprint,
                policyRevision.toString(),
                outcome,
                grantId?.value,
                reasonCodes.sortedBy { it.name }.joinToString(",") { it.name },
            )
        )

        /** Case-sensitive, length-delimited identity. Resource paths/URLs must never be lowercased. */
        private fun exactFingerprint(vararg parts: String?): String {
            val digest = MessageDigest.getInstance("SHA-256")
            parts.forEach { part ->
                if (part == null) {
                    digest.update(byteArrayOf(0))
                } else {
                    val bytes = part.toByteArray(Charsets.UTF_8)
                    digest.update(byteArrayOf(1))
                    digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
                    digest.update(bytes)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
