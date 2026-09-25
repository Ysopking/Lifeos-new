package app.lifeos.core.runtime.research

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.runtime.reasoning.RealizationComponentKind
import app.lifeos.core.runtime.reasoning.RealizationComponentRef

object PersonalRealizationStateAdapter {
    fun component(
        snapshot: OwnerPersonalContextSnapshot,
    ): RealizationComponentRef {
        val semanticFingerprint = StableFieldIds.fingerprint(
            "owner-personal-context-semantic/v1",
            snapshot.personalWorldFingerprint,
            snapshot.ownerObjectiveFingerprint,
            snapshot.ownerAgencyFingerprint,
            snapshot.subjectiveStateFingerprint,
            *snapshot.verifiedOutcomeFingerprints
                .map { "verified-outcome:$it" }
                .toTypedArray(),
        )
        return RealizationComponentRef(
            kind = RealizationComponentKind.PERSONAL_CONTEXT,
            representationId = "owner-personal-context:${snapshot.fingerprint}",
            semanticFingerprint = semanticFingerprint,
            provenanceFingerprints = snapshot.verifiedOutcomeFingerprints,
        )
    }
}
