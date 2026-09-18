package app.lifeos.core.runtime.cognition

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonIndexCursor
import app.lifeos.core.model.PhotonIndexOrder
import app.lifeos.core.model.PhotonIndexQuery
import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.RevisionedPhotonRepository

internal suspend fun loadCognitionJournalPhotons(
    repository: PhotonRepository,
    kind: CognitionJournalKind,
): List<Photon> {
    if (repository is RevisionedPhotonRepository) {
        val refs = queryCognitionJournalRefs(
            repository = repository,
            allTags = setOf(
                COGNITION_JOURNAL_ROOT_TAG,
                "cognition-journal-kind:${kind.tag}",
            ),
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
        val refs = queryCognitionJournalRefs(
            repository = repository,
            allTags = setOf(COGNITION_JOURNAL_ROOT_TAG),
        )
        return loadCognitionJournalPhotons(repository, refs)
    }

    return repository.loadReport().photons.filter {
        it.mimeType == COGNITION_JOURNAL_MIME &&
            COGNITION_JOURNAL_ROOT_TAG in it.tags
    }
}

private suspend fun queryCognitionJournalRefs(
    repository: RevisionedPhotonRepository,
    allTags: Set<String>,
): List<PhotonRevisionRef> {
    val refs = mutableListOf<PhotonRevisionRef>()
    var cursor: PhotonIndexCursor? = null
    while (true) {
        val page = repository.query(
            PhotonIndexQuery(
                mimeTypes = setOf(COGNITION_JOURNAL_MIME),
                allTags = allTags,
                latestOnly = true,
                order = PhotonIndexOrder.IDENTITY,
                after = cursor,
                limit = PhotonIndexQuery.HARD_PAGE_LIMIT,
            )
        )
        refs += page
        require(refs.size <= CognitionJournalIndexSnapshot.MAX_ENTRIES) {
            "Cognition journal query exceeds bounded capacity"
        }
        if (page.size < PhotonIndexQuery.HARD_PAGE_LIMIT) return refs
        cursor = PhotonIndexCursor(
            order = PhotonIndexOrder.IDENTITY,
            lastRef = page.last(),
        )
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
