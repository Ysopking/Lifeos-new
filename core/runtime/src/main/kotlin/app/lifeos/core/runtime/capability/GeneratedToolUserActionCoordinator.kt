package app.lifeos.core.runtime.capability

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import java.time.Instant

/**
 * Trusted explicit-user boundary between a blocking capability gap and bounded Genesis.
 *
 * The request and approval are persisted and read back as exact immutable Photons before Genesis
 * is allowed to run. A request Photon, tag or provenance marker alone never grants build,
 * execution or promotion authority. The approval created here authorizes exactly one bounded
 * Genesis request and remains non-activating. An optional private trial suite may execute only
 * after Genesis has admitted the exact tool to TRIAL; exact owner-review-required tools stop before
 * that boundary and therefore cannot execute merely because generation was explicitly requested.
 */
class GeneratedToolUserActionCoordinator(
    private val requests: GeneratedToolRequestCoordinator,
    private val persist: suspend (Photon) -> Unit,
    private val load: suspend (PhotonId) -> Photon?,
    private val trialSuite: PrivateGeneratedToolTrialSuite? = null,
    private val now: () -> Instant = Instant::now,
) {
    suspend fun generateExplicitlyApproved(gap: CapabilityGap): GeneratedToolUserActionResult {
        val requestedAt = now()
        val (requestPhoton, createdRequest) = GeneratedToolRequestPhotonCodec.createRequestPhoton(
            gap = gap,
            createdAt = requestedAt,
        )
        persist(requestPhoton)

        val storedRequestPhoton = requireExactReadBack(
            expected = requestPhoton,
            label = "generated-tool-request",
        )
        val request = GeneratedToolRequestPhotonCodec.decodeRequest(storedRequestPhoton)
        require(request.id == createdRequest.id && request.matches(gap)) {
            "Persisted generated-tool request no longer matches the approved capability gap"
        }

        val approvalClock = now()
        val approvedAt = if (approvalClock.isBefore(request.requestedAt)) {
            request.requestedAt
        } else {
            approvalClock
        }
        val (approvalPhoton, _) = GeneratedToolRequestPhotonCodec.createApprovalPhoton(
            request = request,
            approvedAt = approvedAt,
        )
        persist(approvalPhoton)

        val storedApprovalPhoton = requireExactReadBack(
            expected = approvalPhoton,
            label = "tool-generation-approval",
        )
        val approval = GeneratedToolRequestPhotonCodec.decodeApproval(
            photon = storedApprovalPhoton,
            request = request,
        )
        require(!request.activationAllowed && !approval.activationAllowed) {
            "Generated-tool user evidence must remain non-activating"
        }

        val execution = requests.generateApproved(
            request = request,
            approval = approval,
            gap = gap,
        )
        val trials = when (execution) {
            is GeneratedToolRequestExecutionResult.Blocked -> null
            is GeneratedToolRequestExecutionResult.Completed -> when (val genesis = execution.genesis) {
                is GeneratedToolGenesisResult.Rejected -> null
                is GeneratedToolGenesisResult.OwnerReviewRequired -> null
                is GeneratedToolGenesisResult.TrialReady -> trialSuite?.execute(genesis.record)
            }
        }
        return GeneratedToolUserActionResult(
            requestPhoton = storedRequestPhoton,
            approvalPhoton = storedApprovalPhoton,
            execution = execution,
            trials = trials,
        )
    }

    private suspend fun requireExactReadBack(expected: Photon, label: String): Photon {
        val stored = requireNotNull(load(expected.id)) { "$label was not durable after persistence" }
        require(stored == expected) { "$label changed during persistence" }
        return stored
    }
}

data class GeneratedToolUserActionResult(
    val requestPhoton: Photon,
    val approvalPhoton: Photon,
    val execution: GeneratedToolRequestExecutionResult,
    val trials: PrivateGeneratedToolTrialSuiteResult?,
)
