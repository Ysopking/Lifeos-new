package app.lifeos.core.runtime.extension

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.runtime.evolution.ExtensionEvolutionAdmission

data class AutonomousExpansionGoldEvidence private constructor(
    val id: String,
    val gapId: String,
    val claimGraphId: String,
    val candidateId: String,
    val workshopArtifactId: String,
    val validationBundleId: String,
    val evolutionSubjectIds: List<String>,
    val promotionProofId: String,
    val hotSwapAuthorizationId: String,
    val rollbackProofId: String,
    val rollbackAuthorizationId: String,
    val activatedSnapshotId: String,
    val restoredSnapshotId: String,
) {
    init {
        listOf(
            id,
            gapId,
            claimGraphId,
            candidateId,
            workshopArtifactId,
            validationBundleId,
            promotionProofId,
            hotSwapAuthorizationId,
            rollbackProofId,
            rollbackAuthorizationId,
            activatedSnapshotId,
            restoredSnapshotId,
        ).forEach { require(it.isNotBlank()) }
        require(evolutionSubjectIds.isNotEmpty() && evolutionSubjectIds.none { it.isBlank() })
        require(id == expectedId()) { "Autonomous expansion GOLD evidence id mismatch" }
    }

    val activationAllowed: Boolean
        get() = false

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "autonomous-expansion-gold/v1",
        gapId,
        claimGraphId,
        candidateId,
        workshopArtifactId,
        validationBundleId,
        promotionProofId,
        hotSwapAuthorizationId,
        rollbackProofId,
        rollbackAuthorizationId,
        activatedSnapshotId,
        restoredSnapshotId,
        *evolutionSubjectIds.sorted().toTypedArray(),
    )

    private fun expectedId(): String = "extension-expansion-gold:${fingerprint()}"

    companion object {
        fun create(
            gap: ExtensionGap,
            graph: DeepSearchClaimGraph,
            candidate: ExtensionCandidate,
            workshop: ExtensionWorkshopArtifact,
            validation: ExtensionValidationBundle,
            evolution: ExtensionEvolutionAdmission,
            promotionProof: ExtensionPromotionProof,
            hotSwapAuthorization: ExtensionHotSwapAuthorization,
            hotSwapResult: ExtensionHotSwapResult.Applied,
            rollbackProof: SelfHealingExtensionRollbackProof,
            rollbackAuthorization: ExtensionRollbackAuthorization,
            rollbackResult: ExtensionHotSwapResult.RolledBack,
        ): AutonomousExpansionGoldEvidence {
            require(candidate.gapId == gap.id) {
                "GOLD candidate is not bound to the supplied gap"
            }
            require(candidate.claimGraphId == graph.id) {
                "GOLD candidate is not bound to the supplied DeepSearch claim graph"
            }
            require(workshop.extensionCandidateId == candidate.id)
            require(validation.workshopArtifactId == workshop.id)
            require(evolution.candidateId == candidate.id)
            require(evolution.validationBundleId == validation.id)
            require(evolution.subjects.any { it.id == promotionProof.subjectId }) {
                "GOLD promotion proof references an unknown evolution subject"
            }
            require(promotionProof.validationBundleId == validation.id)
            require(hotSwapAuthorization.subjectId == promotionProof.subjectId)
            require(hotSwapAuthorization.promotionEvidenceId == promotionProof.id)
            require(hotSwapResult.authorizationId == hotSwapAuthorization.id)
            require(
                hotSwapResult.currentHead.activeSnapshotId ==
                    hotSwapAuthorization.targetSnapshotId
            )
            require(rollbackProof.restoreSnapshotId == hotSwapResult.previousHead.activeSnapshotId)
            require(rollbackAuthorization.rollbackEvidenceId == rollbackProof.id)
            require(rollbackAuthorization.restoreSnapshotId == rollbackProof.restoreSnapshotId)
            require(rollbackResult.authorizationId == rollbackAuthorization.id)
            require(rollbackResult.previousHead == hotSwapResult.currentHead)
            require(
                rollbackResult.currentHead.activeSnapshotId ==
                    hotSwapResult.previousHead.activeSnapshotId
            ) {
                "GOLD rollback did not restore the exact predecessor snapshot"
            }

            require(!candidate.activationAllowed)
            require(!workshop.activationAllowed)
            require(!validation.activationAllowed)
            require(!validation.promotionAllowed)
            require(!evolution.activationAllowed)
            require(!promotionProof.activationAllowed)
            require(!hotSwapAuthorization.activationAllowed)

            if (ExtensionKind.WORLD_EQUATION_PACK in candidate.requestedKinds) {
                require(candidate.requiresEvolutionPromotion) {
                    "World equation candidate must require Controlled Evolution"
                }
                require(promotionProof.holdoutEvidenceId.isNotBlank()) {
                    "World equation GOLD requires holdout evidence"
                }
                require(promotionProof.shadowEvidenceId.isNotBlank()) {
                    "World equation GOLD requires shadow evidence"
                }
                require(promotionProof.trialEvidenceId.isNotBlank()) {
                    "World equation GOLD requires trial evidence"
                }
                require(rollbackProof.id.isNotBlank() && rollbackAuthorization.id.isNotBlank()) {
                    "World equation GOLD requires exact rollback evidence"
                }
            }

            val subjectIds = evolution.subjects.map { it.id }.sorted()
            val fingerprint = StableFieldIds.fingerprint(
                "autonomous-expansion-gold/v1",
                gap.id,
                graph.id,
                candidate.id,
                workshop.id,
                validation.id,
                promotionProof.id,
                hotSwapAuthorization.id,
                rollbackProof.id,
                rollbackAuthorization.id,
                hotSwapResult.currentHead.activeSnapshotId,
                rollbackResult.currentHead.activeSnapshotId,
                *subjectIds.toTypedArray(),
            )
            return AutonomousExpansionGoldEvidence(
                id = "extension-expansion-gold:$fingerprint",
                gapId = gap.id,
                claimGraphId = graph.id,
                candidateId = candidate.id,
                workshopArtifactId = workshop.id,
                validationBundleId = validation.id,
                evolutionSubjectIds = subjectIds,
                promotionProofId = promotionProof.id,
                hotSwapAuthorizationId = hotSwapAuthorization.id,
                rollbackProofId = rollbackProof.id,
                rollbackAuthorizationId = rollbackAuthorization.id,
                activatedSnapshotId = hotSwapResult.currentHead.activeSnapshotId,
                restoredSnapshotId = rollbackResult.currentHead.activeSnapshotId,
            )
        }
    }
}

/**
 * B160 is a GOLD contract/evidence assembler, not a second runtime loop.
 *
 * It proves the full authority chain after the individual subsystems have executed. It cannot
 * activate extensions itself.
 */
object AutonomousExpansionGoldContract {
    const val WORLD_EQUATION_INVARIANT =
        "NO_WORLD_EQUATION_ACTIVATION_WITHOUT_HOLDOUT_SHADOW_TRIAL_ROLLBACK"
}
