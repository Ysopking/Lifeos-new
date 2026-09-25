package app.lifeos.core.runtime.research

import app.lifeos.core.field.StableFieldIds
import java.time.Instant

/**
 * B495 immutable owner-personal context head.
 *
 * It binds Personal World, explicit Owner Objective, Owner Agency and SEIN hypothesis revisions
 * without collapsing their epistemic roles. The snapshot grants neither truth nor execution.
 */
data class OwnerPersonalContextSnapshot private constructor(
    val personalWorldFingerprint: String,
    val ownerObjectiveFingerprint: String,
    val ownerAgencyFingerprint: String,
    val subjectiveStateFingerprint: String,
    val verifiedOutcomeFingerprints: List<String>,
    val asOf: Instant,
    val fingerprint: String,
) {
    init {
        require(personalWorldFingerprint.isNotBlank())
        require(ownerObjectiveFingerprint.isNotBlank())
        require(ownerAgencyFingerprint.isNotBlank())
        require(subjectiveStateFingerprint.isNotBlank())
        require(
            verifiedOutcomeFingerprints ==
                verifiedOutcomeFingerprints.distinct().sorted()
        )
        require(
            fingerprint == StableFieldIds.fingerprint(
                "owner-personal-context-snapshot/v1",
                personalWorldFingerprint,
                ownerObjectiveFingerprint,
                ownerAgencyFingerprint,
                subjectiveStateFingerprint,
                asOf.toString(),
                *verifiedOutcomeFingerprints
                    .map { "verified-outcome:$it" }
                    .toTypedArray(),
            )
        )
    }

    val factualWorldAuthority: Boolean
        get() = false

    val policyAuthority: Boolean
        get() = false

    val executionAuthority: Boolean
        get() = false

    companion object {
        fun create(
            personalWorld: PersonalWorldSnapshot,
            objective: OwnerObjectiveSnapshot,
            agency: OwnerAgencySnapshot,
            subjectiveState: SubjectiveStateHypothesis,
            verifiedOutcomes: Collection<VerifiedOutcomeLearningSignal> = emptyList(),
            asOf: Instant,
        ): OwnerPersonalContextSnapshot {
            require(agency.personalWorldFingerprint == personalWorld.fingerprint)
            require(agency.objectiveSnapshotFingerprint == objective.fingerprint)
            require(subjectiveState.personalWorldFingerprint == personalWorld.fingerprint)
            require(subjectiveState.ownerAgencyFingerprint == agency.fingerprint)

            val outcomeFingerprints = verifiedOutcomes
                .map { it.fingerprint }
                .distinct()
                .sorted()
            val fingerprint = StableFieldIds.fingerprint(
                "owner-personal-context-snapshot/v1",
                personalWorld.fingerprint,
                objective.fingerprint,
                agency.fingerprint,
                subjectiveState.fingerprint,
                asOf.toString(),
                *outcomeFingerprints
                    .map { "verified-outcome:$it" }
                    .toTypedArray(),
            )
            return OwnerPersonalContextSnapshot(
                personalWorldFingerprint = personalWorld.fingerprint,
                ownerObjectiveFingerprint = objective.fingerprint,
                ownerAgencyFingerprint = agency.fingerprint,
                subjectiveStateFingerprint = subjectiveState.fingerprint,
                verifiedOutcomeFingerprints = outcomeFingerprints,
                asOf = asOf,
                fingerprint = fingerprint,
            )
        }
    }
}
