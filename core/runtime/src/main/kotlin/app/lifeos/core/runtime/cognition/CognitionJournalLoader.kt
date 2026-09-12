package app.lifeos.core.runtime.cognition

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonRepository

internal suspend fun loadCognitionJournalPhotons(
    repository: PhotonRepository,
    kind: CognitionJournalKind,
): List<Photon> = repository.loadAll()
    .filter {
        COGNITION_JOURNAL_ROOT_TAG in it.tags &&
            "cognition-journal-kind:${kind.tag}" in it.tags
    }
    .sortedBy { it.id.value }
