package app.lifeos.core.runtime.cognition

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonIndexQuery
import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.RevisionedPhotonRepository

internal suspend fun loadCognitionJournalPhotons(
    repository: PhotonRepository,
    kind: CognitionJournalKind,
): List<Photon> {
    if (repository is RevisionedPhotonRepository) {
        val refs = repository.query(
            PhotonIndexQuery(
                mimeTypes = setOf(COGNITION_JOURNAL_MIME),
                allTags = setOf(
                    COGNITION_JOURNAL_ROOT_TAG,
                    "cognition-journal-kind:${kind.tag}",
                ),
                latestOnly = true,
                limit = CognitionJournalIndexSnapshot.MAX_ENTRIES,
            )
        )
        return loadCognitionJournalPhotons(repository, refs)
            .sortedBy { it.id.value }
    }

    return repository.loadReport().photons
        .filter {
            COGNITION_JOURNAL_ROOT_TAG in it.tags &&
                "cognition-journal-kind:${kind.tag}" in it.tags
        }
        .sortedBy { it.id.value }
}

internal suspend fun loadAllCognitionJournalPhotons(
    repository: PhotonRepository,
): List<Photon> {
    if (repository is RevisionedPhotonRepository) {
        val refs = repository.query(
            PhotonIndexQuery(
                mimeTypes = setOf(COGNITION_JOURNAL_MIME),
                allTags = setOf(COGNITION_JOURNAL_ROOT_TAG),
                latestOnly = true,
                limit = CognitionJournalIndexSnapshot.MAX_ENTRIES,
            )
        )
        return loadCognitionJournalPhotons(repository, refs)
    }

    return repository.loadReport().photons.filter {
        it.mimeType == COGNITION_JOURNAL_MIME &&
            COGNITION_JOURNAL_ROOT_TAG in it.tags
    }
}

internal suspend fun loadCognitionJournalPhotons(
    repository: PhotonRepository,
    refs: List<PhotonRevisionRef>,
): List<Photon> = refs.map { ref ->
    val photon = if (repository is RevisionedPhotonRepository) {
        repository.load(ref)
    } else {
        repository.load(ref.photonId)?.takeIf { it.revision == ref.revision }
    }
    photon ?: throw CognitionJournalCorruptionException(
        "Missing cognition journal Photon ${ref.stableKey}"
    )
}

internal suspend fun loadCognitionJournalPhoton(
    repository: PhotonRepository,
    kind: CognitionJournalKind,
    stableId: String,
): Photon? = repository.load(CognitionJournalIdentity.photonId(kind.tag, stableId))
    ?.takeIf {
        COGNITION_JOURNAL_ROOT_TAG in it.tags &&
            "cognition-journal-kind:${kind.tag}" in it.tags
    }
