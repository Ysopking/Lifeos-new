package app.lifeos.next.ui.memory

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonIndexCursor
import app.lifeos.core.model.PhotonIndexOrder
import app.lifeos.core.runtime.query.ProductivePhotonQueryService
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class MemoryPhotonWindow(
    val photons: List<Photon>,
    val next: PhotonIndexCursor?,
    val hasMore: Boolean,
    val loadedPages: Int,
) {
    init {
        require(loadedPages >= 0)
    }
}

/**
 * Bounded memory-source window over the productive Photon repository.
 *
 * The pager never treats the ViewModel list as authority. It keeps a finite recent working set and
 * resolves older source Photons explicitly through ProductivePhotonQueryService.
 */
class MemoryPhotonPager(
    private val queries: ProductivePhotonQueryService,
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
    private val maxLoadedPages: Int = DEFAULT_MAX_LOADED_PAGES,
) {
    private val mutex = Mutex()
    private val maxLoadedPhotons = Math.multiplyExact(pageSize, maxLoadedPages)

    private var initialized = false
    private var loadedPages = 0
    private var loaded: List<Photon> = emptyList()
    private var next: PhotonIndexCursor? = null

    init {
        require(pageSize in 1..app.lifeos.core.model.PhotonIndexQuery.HARD_PAGE_LIMIT)
        require(maxLoadedPages in 1..32)
    }

    suspend fun refreshFront(
        ambientPhotons: Iterable<Photon> = emptyList(),
    ): MemoryPhotonWindow = mutex.withLock {
        val page = queries.latest(
            limit = pageSize,
            order = PhotonIndexOrder.NEWEST_FIRST,
        )
        loaded = mergeBounded(page.photons + ambientPhotons + loaded)
        if (!initialized) {
            initialized = true
            loadedPages = 1
            next = page.next
        } else if (loadedPages <= 1) {
            next = page.next
        }
        snapshotLocked()
    }

    suspend fun loadMore(): MemoryPhotonWindow = mutex.withLock {
        if (!initialized || next == null || loadedPages >= maxLoadedPages) {
            return@withLock snapshotLocked()
        }
        val cursor = requireNotNull(next)
        val page = try {
            queries.latest(
                limit = pageSize,
                after = cursor,
                order = PhotonIndexOrder.NEWEST_FIRST,
            )
        } catch (_: IllegalArgumentException) {
            initialized = false
            loadedPages = 0
            loaded = emptyList()
            next = null
            return@withLock MemoryPhotonWindow(
                photons = emptyList(),
                next = null,
                hasMore = false,
                loadedPages = 0,
            )
        }
        loaded = mergeBounded(loaded + page.photons)
        loadedPages += 1
        next = page.next
        snapshotLocked()
    }

    suspend fun seedFallback(
        photons: Iterable<Photon>,
    ): MemoryPhotonWindow = mutex.withLock {
        loaded = mergeBounded(photons)
        initialized = true
        loadedPages = if (loaded.isEmpty()) 0 else 1
        next = null
        snapshotLocked()
    }

    suspend fun clear() = mutex.withLock {
        initialized = false
        loadedPages = 0
        loaded = emptyList()
        next = null
    }

    private fun mergeBounded(candidates: Iterable<Photon>): List<Photon> =
        candidates
            .groupBy { it.id }
            .mapNotNull { (_, revisions) ->
                revisions.maxWithOrNull(
                    compareBy<Photon>({ it.revision }, { it.provenance.createdAt })
                )
            }
            .sortedWith(
                compareByDescending<Photon> { it.provenance.createdAt }
                    .thenByDescending { it.revision }
                    .thenBy { it.id.value }
            )
            .take(maxLoadedPhotons)

    private fun snapshotLocked(): MemoryPhotonWindow =
        MemoryPhotonWindow(
            photons = loaded,
            next = next,
            hasMore = next != null && loadedPages < maxLoadedPages,
            loadedPages = loadedPages,
        )

    companion object {
        const val DEFAULT_PAGE_SIZE = 128
        const val DEFAULT_MAX_LOADED_PAGES = 8
        const val DEFAULT_MAX_LOADED_PHOTONS =
            DEFAULT_PAGE_SIZE * DEFAULT_MAX_LOADED_PAGES
    }
}
