package app.lifeos.core.runtime.capability

sealed interface GeneratedToolGenesisResult {
    data class TrialReady(
        val record: GeneratedToolRecord,
        val sandboxProfileId: String,
    ) : GeneratedToolGenesisResult

    data class Rejected(
        val record: GeneratedToolRecord,
        val reasons: List<String>,
    ) : GeneratedToolGenesisResult
}

/**
 * Safe default entrypoint for future Genesis triggers. A generated artifact can
 * leave this coordinator only as TRIAL or REJECTED; it can never become ACTIVE
 * as a side effect of generation. J03 field evidence is explicitly handed from
 * workshop output into the lifecycle before trial admission.
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
                val toolId = workshopResult.record.manifest.toolId
                if (workshopResult.designFieldSnapshotIds.isNotEmpty()) {
                    lifecycle.bindDesignFieldSnapshots(toolId, workshopResult.designFieldSnapshotIds)
                }
                when (val trial = lifecycle.admitToTrial(toolId)) {
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
