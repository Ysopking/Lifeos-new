package app.lifeos.core.runtime.research

import app.lifeos.core.field.StableFieldIds
import java.time.Instant

enum class OwnerAgencyDimension {
    FINANCIAL,
    TIME,
    INFORMATION,
    MOBILITY,
    SOCIAL,
    CAPABILITY,
    RESOURCE,
    DECISION,
}

data class OwnerAgencySignal(
    val dimension: OwnerAgencyDimension,
    val availability: Double,
    val confidence: Double,
    val evidenceIds: List<String>,
) {
    init {
        require(availability.isFinite() && availability in 0.0..1.0)
        require(confidence.isFinite() && confidence in 0.0..1.0)
        require(evidenceIds == evidenceIds.distinct().sorted())
    }

    val fingerprint: String = StableFieldIds.fingerprint(
        "owner-agency-signal/v1",
        dimension.name,
        java.lang.Double.toHexString(availability),
        java.lang.Double.toHexString(confidence),
        *evidenceIds.toTypedArray(),
    )
}

data class OwnerObjectiveSnapshot private constructor(
    val activeGoalPlanIds: List<String>,
    val objectiveFingerprints: List<String>,
    val constraintFingerprints: List<String>,
    val asOf: Instant,
    val fingerprint: String,
) {
    init {
        require(activeGoalPlanIds == activeGoalPlanIds.distinct().sorted())
        require(objectiveFingerprints == objectiveFingerprints.distinct().sorted())
        require(constraintFingerprints == constraintFingerprints.distinct().sorted())
        require(
            fingerprint == StableFieldIds.fingerprint(
                "owner-objective-snapshot/v1",
                asOf.toString(),
                *activeGoalPlanIds.map { "goal:$it" }.toTypedArray(),
                *objectiveFingerprints.map { "objective:$it" }.toTypedArray(),
                *constraintFingerprints.map { "constraint:$it" }.toTypedArray(),
            )
        )
    }

    val inferredPreferenceAuthority: Boolean
        get() = false

    companion object {
        fun create(
            activeGoalPlanIds: Collection<String>,
            objectiveFingerprints: Collection<String>,
            constraintFingerprints: Collection<String>,
            asOf: Instant,
        ): OwnerObjectiveSnapshot {
            val goals = activeGoalPlanIds.distinct().sorted()
            val objectives = objectiveFingerprints.distinct().sorted()
            val constraints = constraintFingerprints.distinct().sorted()
            val fingerprint = StableFieldIds.fingerprint(
                "owner-objective-snapshot/v1",
                asOf.toString(),
                *goals.map { "goal:$it" }.toTypedArray(),
                *objectives.map { "objective:$it" }.toTypedArray(),
                *constraints.map { "constraint:$it" }.toTypedArray(),
            )
            return OwnerObjectiveSnapshot(
                activeGoalPlanIds = goals,
                objectiveFingerprints = objectives,
                constraintFingerprints = constraints,
                asOf = asOf,
                fingerprint = fingerprint,
            )
        }
    }
}

data class OwnerAgencySnapshot private constructor(
    val objectiveSnapshotFingerprint: String,
    val personalWorldFingerprint: String,
    val signals: List<OwnerAgencySignal>,
    val asOf: Instant,
    val fingerprint: String,
) {
    init {
        require(objectiveSnapshotFingerprint.isNotBlank())
        require(personalWorldFingerprint.isNotBlank())
        require(
            signals == signals.distinctBy { it.dimension }.sortedBy { it.dimension.name }
        )
        require(
            fingerprint == StableFieldIds.fingerprint(
                "owner-agency-snapshot/v1",
                objectiveSnapshotFingerprint,
                personalWorldFingerprint,
                asOf.toString(),
                *signals.map { it.fingerprint }.toTypedArray(),
            )
        )
    }

    val executionAuthority: Boolean
        get() = false

    val ownerPolicyAuthority: Boolean
        get() = false

    companion object {
        fun create(
            objective: OwnerObjectiveSnapshot,
            personalWorld: PersonalWorldSnapshot,
            signals: Collection<OwnerAgencySignal>,
            asOf: Instant,
        ): OwnerAgencySnapshot {
            val canonical = signals
                .sortedWith(
                    compareByDescending<OwnerAgencySignal> { it.confidence }
                        .thenByDescending { it.availability }
                        .thenBy { it.fingerprint }
                )
                .distinctBy { it.dimension }
                .sortedBy { it.dimension.name }

            val fingerprint = StableFieldIds.fingerprint(
                "owner-agency-snapshot/v1",
                objective.fingerprint,
                personalWorld.fingerprint,
                asOf.toString(),
                *canonical.map { it.fingerprint }.toTypedArray(),
            )
            return OwnerAgencySnapshot(
                objectiveSnapshotFingerprint = objective.fingerprint,
                personalWorldFingerprint = personalWorld.fingerprint,
                signals = canonical,
                asOf = asOf,
                fingerprint = fingerprint,
            )
        }
    }
}
