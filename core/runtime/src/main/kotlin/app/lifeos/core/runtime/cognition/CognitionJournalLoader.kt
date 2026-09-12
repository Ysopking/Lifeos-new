package app.lifeos.core.runtime.cognition

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonRepository

internal suspend fun loadCognitionJournalPhotons(
    repository: PhotonRepository,
    kind: CognitionJournalKind,
): List<Photon> = repository.loadReport().photons
    .filter {
        COGNITION_JOURNAL_ROOT_TAG in it.tags &&
            "cognition-journal-kind:${kind.tag}" in it.tags
    }
    .sortedBy { it.id.value }

internal suspend fun loadCognitionJournalPhoton(
    repository: PhotonRepository,
    kind: CognitionJournalKind,
    stableId: String,
): Photon? = repository.load(CognitionJournalIdentity.photonId(kind.tag, stableId))
    ?.takeIf {
        COGNITION_JOURNAL_ROOT_TAG in it.tags &&
            "cognition-journal-kind:${kind.tag}" in it.tags
    }
