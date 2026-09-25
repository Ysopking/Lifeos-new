package app.lifeos.core.runtime.research

import app.lifeos.core.field.StableFieldIds

enum class PersonalContextDeltaKind {
    PERSONAL_WORLD,
    OWNER_OBJECTIVE,
    OWNER_AGENCY,
    SUBJECTIVE_STATE,
    VERIFIED_OUTCOME,
}

data class PersonalContextDelta(
    val kind: PersonalContextDeltaKind,
    val previousFingerprint: String?,
    val currentFingerprint: String,
    val contextFingerprint: String,
    val fingerprint: String,
) {
    init {
        require(currentFingerprint.isNotBlank())
        require(contextFingerprint.isNotBlank())
        require(
            fingerprint == StableFieldIds.fingerprint(
                "personal-context-delta/v1",
                kind.name,
                previousFingerprint.orEmpty(),
                currentFingerprint,
                contextFingerprint,
            )
        )
    }

    val directWorldMutationAllowed: Boolean
        get() = false

    val executionAuthority: Boolean
        get() = false
}

/**
 * B496 computes exact context deltas for the always-on cognition/learning layer.
 *
 * Deltas are change evidence only. They do not execute actions and cannot promote inferred state
 * into owner-confirmed truth.
 */
class PersonalContextDeltaPlanner {
    fun diff(
        previous: OwnerPersonalContextSnapshot?,
        current: OwnerPersonalContextSnapshot,
    ): List<PersonalContextDelta> {
        val changes = buildList {
            addIfChanged(
                kind = PersonalContextDeltaKind.PERSONAL_WORLD,
                previous = previous?.personalWorldFingerprint,
                current = current.personalWorldFingerprint,
                context = current.fingerprint,
            )
            addIfChanged(
                kind = PersonalContextDeltaKind.OWNER_OBJECTIVE,
                previous = previous?.ownerObjectiveFingerprint,
                current = current.ownerObjectiveFingerprint,
                context = current.fingerprint,
            )
            addIfChanged(
                kind = PersonalContextDeltaKind.OWNER_AGENCY,
                previous = previous?.ownerAgencyFingerprint,
                current = current.ownerAgencyFingerprint,
                context = current.fingerprint,
            )
            addIfChanged(
                kind = PersonalContextDeltaKind.SUBJECTIVE_STATE,
                previous = previous?.subjectiveStateFingerprint,
                current = current.subjectiveStateFingerprint,
                context = current.fingerprint,
            )

            val previousOutcomes = previous?.verifiedOutcomeFingerprints.orEmpty().toSet()
            current.verifiedOutcomeFingerprints
                .filterNot(previousOutcomes::contains)
                .sorted()
                .forEach { outcome ->
                    add(
                        createDelta(
                            kind = PersonalContextDeltaKind.VERIFIED_OUTCOME,
                            previous = null,
                            current = outcome,
                            context = current.fingerprint,
                        )
                    )
                }
        }

        return changes
            .distinctBy { it.fingerprint }
            .sortedWith(
                compareBy<PersonalContextDelta> { it.kind.name }
                    .thenBy { it.currentFingerprint }
                    .thenBy { it.fingerprint }
            )
    }

    private fun MutableList<PersonalContextDelta>.addIfChanged(
        kind: PersonalContextDeltaKind,
        previous: String?,
        current: String,
        context: String,
    ) {
        if (previous == current) return
        add(createDelta(kind, previous, current, context))
    }

    private fun createDelta(
        kind: PersonalContextDeltaKind,
        previous: String?,
        current: String,
        context: String,
    ): PersonalContextDelta {
        val fingerprint = StableFieldIds.fingerprint(
            "personal-context-delta/v1",
            kind.name,
            previous.orEmpty(),
            current,
            context,
        )
        return PersonalContextDelta(
            kind = kind,
            previousFingerprint = previous,
            currentFingerprint = current,
            contextFingerprint = context,
            fingerprint = fingerprint,
        )
    }
}
