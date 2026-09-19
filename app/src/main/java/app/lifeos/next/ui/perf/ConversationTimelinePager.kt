package app.lifeos.next.ui.perf

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonIndexCursor
import app.lifeos.core.model.PhotonIndexOrder
import app.lifeos.core.runtime.chat.ConversationProjector
import app.lifeos.core.runtime.query.ProductivePhotonQueryService
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ConversationTimelineWindow(
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
 * Bounded productive chat window. The repository remains authoritative; only a finite number of
 * pages are retained in the ViewModel process. Front refreshes preserve already loaded older pages.
 */
class ConversationTimelinePager(
    private val queries: ProductivePhotonQueryService,
    private val conversationId: String = ConversationProjector.DEFAULT_CONVERSATION_ID,
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
    private val maxLoadedPages: Int = DEFAULT_MAX_LOADED_PAGES,
) {
    private val mutex = Mutex()
    private val conversationTag = "${ConversationProjector.CONVERSATION_PREFIX}$conversationId"
    private val maxLoadedPhotons = Math.multiplyExact(pageSize, maxLoadedPages)

    private var initialized = false
    private var loadedPages = 0
    private var loaded: List<Photon> = emptyList()
    private var next: PhotonIndexCursor? = null

    init {
        require(conversationId.isNotBlank())
        require(pageSize in 1..app.lifeos.core.model.PhotonIndexQuery.HARD_PAGE_LIMIT)
        require(maxLoadedPages in 1..32)
    }

    suspend fun refreshFront(
        ambientPhotons: Iterable<Photon> = emptyList(),
    ): ConversationTimelineWindow = mutex.withLock {
        val page = queries.tags(
            allTags = setOf(conversationTag),
            limit = pageSize,
            order = PhotonIndexOrder.NEWEST_FIRST,
        )
        val ambient = ambientPhotons.filter { it.isAmbientConversationCandidate(conversationTag) }
        loaded = mergeBounded(page.photons + ambient + loaded)
        if (!initialized) {
            loadedPages = 1
            next = page.next
            initialized = true
        } else if (loadedPages <= 1) {
            next = page.next
        }
        snapshotLocked()
    }

    suspend fun loadMore(): ConversationTimelineWindow = mutex.withLock {
        if (!initialized || next == null || loadedPages >= maxLoadedPages) {
            return@withLock snapshotLocked()
        }
        val cursor = requireNotNull(next)
        val page = try {
            queries.tags(
                allTags = setOf(conversationTag),
                limit = pageSize,
                after = cursor,
                order = PhotonIndexOrder.NEWEST_FIRST,
            )
        } catch (_: IllegalArgumentException) {
            // A concurrently advanced revision can invalidate an exact cursor. Reset to the new
            // front rather than guessing an ordering across stale revision identity.
            initialized = false
            loadedPages = 0
            loaded = emptyList()
            next = null
            return@withLock ConversationTimelineWindow(
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
    ): ConversationTimelineWindow = mutex.withLock {
        loaded = mergeBounded(photons.filter { it.isAmbientConversationCandidate(conversationTag) })
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

    private fun snapshotLocked(): ConversationTimelineWindow {
        val canLoadMore = next != null && loadedPages < maxLoadedPages
        return ConversationTimelineWindow(
            photons = loaded,
            next = next,
            hasMore = canLoadMore,
            loadedPages = loadedPages,
        )
    }

    private fun Photon.isAmbientConversationCandidate(conversationTag: String): Boolean =
        conversationTag in tags ||
            "genesis-proposal" in tags ||
            "autonomous-request" in tags ||
            "tool-workshop-outcome" in tags ||
            "evolution-handoff" in tags

    companion object {
        const val DEFAULT_PAGE_SIZE = 64
        const val DEFAULT_MAX_LOADED_PAGES = 8
    }
}
