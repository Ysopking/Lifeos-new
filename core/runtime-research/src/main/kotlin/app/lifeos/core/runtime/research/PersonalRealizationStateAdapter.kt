package app.lifeos.core.runtime.research

import app.lifeos.core.runtime.reasoning.RealizationComponentKind
import app.lifeos.core.runtime.reasoning.RealizationComponentRef

object PersonalRealizationStateAdapter {
    fun component(
        snapshot: OwnerPersonalContextSnapshot,
    ): RealizationComponentRef =
        RealizationComponentRef(
            kind = RealizationComponentKind.PERSONAL_CONTEXT,
            representationId = "owner-personal-context:${snapshot.fingerprint}",
            semanticFingerprint = snapshot.fingerprint,
            provenanceFingerprints = snapshot.verifiedOutcomeFingerprints,
        )
}
