package app.lifeos.core.runtime.capability

import app.lifeos.core.runtime.artifact.OwnerAssetReviewCandidateId

sealed interface GeneratedToolGenesisResult {
    data class TrialReady(
        val record: GeneratedToolRecord,
        val sandboxProfileId: String,
    ) : GeneratedToolGenesisResult

    data class OwnerReviewRequired(
        val record: GeneratedToolRecord,
        val candidateId: OwnerAssetReviewCandidateId,
    ) : GeneratedToolGenesisResult

    data class Rejected(
        val record: GeneratedToolRecord,
        val reasons: List<String>,
    ) : GeneratedToolGenesisResult
}

/**
 * Safe default entrypoint for future Genesis triggers. A generated artifact can
 * leave this coordinator only as owner-review-required, TRIAL or REJECTED; it can never become
 * ACTIVE as a side effect of generation.
 */
class GeneratedToolGenesisCoordinator(
    private val workshop: ToolWorkshopCoordinator,
    private val lifecycle: GeneratedToolLifecycleCoordinator,
) {
    suspend fun generateFor(gap: CapabilityGap): GeneratedToolGenesisResult {
        return when (val workshopResult = workshop.generate(gap)) {
            is ToolWorkshopResult.Rejected -> GeneratedToolGenesisResult.Rejected(
                record = workshopResult.record,
                reasons = listOf(workshopResult.reason),
            )

            is ToolWorkshopResult.Verified -> {
                val reviewGate = GeneratedToolOwnerReviewGateRegistry.currentOrNull()
                if (reviewGate != null) {
                    GeneratedToolGenesisResult.OwnerReviewRequired(
                        record = workshopResult.record,
                        candidateId = reviewGate.stage(workshopResult.record),
                    )
                } else {
                    when (val trial = lifecycle.admitToTrial(workshopResult.record.manifest.toolId)) {
                        is GeneratedToolTrialAdmissionResult.TrialStarted ->
                            GeneratedToolGenesisResult.TrialReady(
                                record = trial.record,
                                sandboxProfileId = trial.sandbox.profileId,
                            )

                        is GeneratedToolTrialAdmissionResult.Rejected ->
                            GeneratedToolGenesisResult.Rejected(
                                record = trial.record,
                                reasons = trial.reasons,
                            )
                    }
                }
            }
        }
    }
}
