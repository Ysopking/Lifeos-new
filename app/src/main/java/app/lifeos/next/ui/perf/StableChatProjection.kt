package app.lifeos.next.ui.perf

import app.lifeos.core.model.Photon
import app.lifeos.core.runtime.chat.ChatEvent
import app.lifeos.core.runtime.chat.ConversationProjector
import app.lifeos.next.ui.chat.ChatTimelineItem
import app.lifeos.next.ui.chat.ChatTimelineProjector

data class PhotonRevisionKey(
    val photonId: String,
    val revision: Long,
)

data class StableChatProjectionResult(
    val events: List<ChatEvent>,
    val timeline: List<ChatTimelineItem>,
)

/**
 * Bounded UI-only cache for chat projection work.
 *
 * The cache holds exactly one projection. Its identity is the canonical Photon id/revision vector;
 * runtime boot/readiness updates that do not change that vector therefore do not rebuild the full
 * conversation and image ancestry projection. Productive repositories remain the only cold-start
 * truth and any revision change invalidates the cache.
 */
class StableChatProjection(
    private val conversationId: String = ConversationProjector.DEFAULT_CONVERSATION_ID,
) {
    private var cachedKey: List<PhotonRevisionKey>? = null
    private var cachedProjection: StableChatProjectionResult? = null

    internal var recomputationCount: Int = 0
        private set

    fun project(photons: Iterable<Photon>): StableChatProjectionResult {
        val snapshot = photons.toList()
        val key = canonicalPhotonRevisionKey(snapshot)
        val existing = cachedProjection
        if (existing != null && cachedKey == key) return existing

        val projected = StableChatProjectionResult(
            events = ConversationProjector.project(snapshot, conversationId),
            timeline = ChatTimelineProjector.project(snapshot, conversationId),
        )
        cachedKey = key
        cachedProjection = projected
        recomputationCount += 1
        return projected
    }

    fun clear() {
        cachedKey = null
        cachedProjection = null
    }
}

fun canonicalPhotonRevisionKey(photons: Iterable<Photon>): List<PhotonRevisionKey> = photons
    .map { photon -> PhotonRevisionKey(photon.id.value, photon.revision) }
    .sortedWith(compareBy<PhotonRevisionKey> { it.photonId }.thenBy { it.revision })
