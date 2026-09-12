package app.lifeos.core.runtime.cognition

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonPhase
import app.lifeos.core.model.Provenance
import java.time.Instant

internal fun cognitionJournalPhoton(kind: CognitionJournalKind, stableId: String, at: Instant, content: String): Photon = Photon(
    id = CognitionJournalIdentity.photonId(kind.tag, stableId),
    content = content,
    mimeType = COGNITION_JOURNAL_MIME,
    phase = PhotonPhase.ARCHIVED,
    semanticMass = 0.0,
    energy = 0.0,
    provenance = Provenance("cognition-journal", "lifeos-runtime", at),
    tags = setOf(
        "internal",
        COGNITION_JOURNAL_ROOT_TAG,
        "cognition-journal-kind:${kind.tag}",
        "cognition-journal-schema:1",
    ),
)
