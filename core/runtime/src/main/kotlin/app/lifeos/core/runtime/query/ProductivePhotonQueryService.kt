package app.lifeos.core.runtime.query

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonIndexCursor
import app.lifeos.core.model.PhotonIndexOrder
import app.lifeos.core.model.PhotonIndexQuery
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.RevisionedPhotonRepository

data class ProductivePhotonPage(
    val photons: List<Photon>,
    val next: PhotonIndexCursor?,
) {
    init {
        require(photons.size <= PhotonIndexQuery.HARD_PAGE_LIMIT)
    }
}

fun interface GoalOutcomeLookup {
    suspend fun candidates(
        goalPhotonId: PhotonId,
        limit: Int,
    ): List<Photon>

    /**
     * Optional exact-id recovery path for already committed durable outcomes. Implementations
     * backed by the productive revisioned Photon store should override this so a completed plan
     * does not depend on bounded parent scans or index timing to rehydrate its exact outcome.
     */
    suspend fun exactOutcome(id: PhotonId): Photon? = null
}

class ProductivePhotonQueryService(
    private val photons: RevisionedPhotonRepository,
) : GoalOutcomeLookup {
    override suspend fun exactOutcome(id: PhotonId): Photon? =
        photons.latestRef(id)?.let { ref -> photons.load(ref) }

    suspend fun exactRevision(ref: PhotonRevisionRef): Photon? =
        photons.load(ref)

    suspend fun exact(
        ids: Set<PhotonId>,
        limit: Int = PhotonIndexQuery.DEFAULT_PAGE_LIMIT,
    ): List<Photon> {
        if (ids.isEmpty()) return emptyList()
        require(limit in 1..PhotonIndexQuery.HARD_PAGE_LIMIT)
        return page(
            PhotonIndexQuery(
                ids = ids,
                latestOnly = true,
                order = PhotonIndexOrder.IDENTITY,
                limit = minOf(limit, ids.size),
            )
        ).photons
    }

    suspend fun tags(
        allTags: Set<String>,
        limit: Int = PhotonIndexQuery.DEFAULT_PAGE_LIMIT,
        after: PhotonIndexCursor? = null,
        order: PhotonIndexOrder = PhotonIndexOrder.IDENTITY,
    ): ProductivePhotonPage {
        require(allTags.isNotEmpty())
        return page(
            PhotonIndexQuery(
                allTags = allTags,
                latestOnly = true,
                includeTombstoned = false,
                order = order,
                after = after,
                limit = limit,
            )
        )
    }

    suspend fun page(query: PhotonIndexQuery): ProductivePhotonPage {
        val refs = photons.query(query)
        val loaded = refs.map { ref ->
            requireNotNull(photons.load(ref)) {
                "Photon index references missing exact revision: ${ref.photonId.value}@${ref.revision}"
            }
        }
        val next = if (refs.size == query.limit) {
            PhotonIndexCursor(query.order, refs.last())
        } else {
            null
        }
        return ProductivePhotonPage(loaded, next)
    }

    suspend fun latest(
        limit: Int = PhotonIndexQuery.DEFAULT_PAGE_LIMIT,
        after: PhotonIndexCursor? = null,
        order: PhotonIndexOrder = PhotonIndexOrder.NEWEST_FIRST,
    ): ProductivePhotonPage = page(
        PhotonIndexQuery(
            latestOnly = true,
            includeTombstoned = false,
            order = order,
            after = after,
            limit = limit,
        )
    )

    suspend fun byParent(
        parentId: PhotonId,
        requiredTags: Set<String> = emptySet(),
        limit: Int = PhotonIndexQuery.DEFAULT_PAGE_LIMIT,
        maxPages: Int = DEFAULT_PARENT_SCAN_PAGES,
    ): List<Photon> {
        require(limit in 1..PhotonIndexQuery.HARD_PAGE_LIMIT)
        require(maxPages in 1..MAX_PARENT_SCAN_PAGES)
        val matches = mutableListOf<Photon>()
        var cursor: PhotonIndexCursor? = null
        repeat(maxPages) {
            if (matches.size >= limit) return matches.take(limit)
            val page = latest(
                limit = PhotonIndexQuery.HARD_PAGE_LIMIT,
                after = cursor,
                order = PhotonIndexOrder.NEWEST_FIRST,
            )
            page.photons
                .asSequence()
                .filter { parentId in it.provenance.parentIds }
                .filter { it.tags.containsAll(requiredTags) }
                .forEach { photon ->
                    if (matches.size < limit) matches += photon
                }
            cursor = page.next
            if (cursor == null) return matches
        }
        return matches
    }

    suspend fun goalContext(
        goalPhotonId: PhotonId,
        requiredTags: Set<String> = emptySet(),
        limit: Int = PhotonIndexQuery.DEFAULT_PAGE_LIMIT,
    ): List<Photon> = byParent(
        parentId = goalPhotonId,
        requiredTags = requiredTags,
        limit = limit,
    )

    override suspend fun candidates(
        goalPhotonId: PhotonId,
        limit: Int,
    ): List<Photon> = byParent(
        parentId = goalPhotonId,
        limit = limit,
    )

    companion object {
        const val DEFAULT_PARENT_SCAN_PAGES: Int = 4
        const val MAX_PARENT_SCAN_PAGES: Int = 16
        const val MAX_GOAL_OUTCOME_CANDIDATES: Int = 64
    }
}
