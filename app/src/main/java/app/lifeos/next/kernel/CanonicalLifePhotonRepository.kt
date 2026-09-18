package app.lifeos.next.kernel

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonIndexCursor
import app.lifeos.core.model.PhotonIndexOrder
import app.lifeos.core.model.PhotonIndexQuery
import app.lifeos.core.model.PhotonLoadReport
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.RevisionedPhotonRepository
import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.runtime.PhotonIngressMode

/**
 * Repository boundary for the productive life-memory subsystem.
 *
 * Authoritative source evidence/state and generated memory projections become live/durable runtime
 * Photons. Pure persistence-management records (source checkpoints and memory-access events) remain
 * repository-only so cursor/accounting state cannot accidentally seed cognition.
 */
internal class CanonicalLifePhotonRepository(
    private val delegate: PhotonRepository,
    private val productiveIngress: suspend (Photon, PhotonIngressMode) -> Unit,
) : PhotonRepository {
    override suspend fun save(photon: Photon) {
        val mode = productiveMode(photon)
        if (mode == null) {
            delegate.save(photon)
        } else {
            productiveIngress(photon, mode)
        }
    }

    override suspend fun load(id: PhotonId): Photon? = delegate.load(id)

    override suspend fun loadReport(): PhotonLoadReport = delegate.loadReport()

    override suspend fun loadAll(): List<Photon> = delegate.loadAll()

    override suspend fun delete(id: PhotonId) = delegate.delete(id)

    /**
     * Reconciles productive life Photons that may have been persisted by an older build or by a
     * process that died between repository persistence and durable cognitive submission.
     */
    suspend fun reconcilePersisted(): Int {
        var reconciled = 0
        val candidates = if (delegate is RevisionedPhotonRepository) {
            val refs = linkedSetOf<PhotonRevisionRef>()
            PRODUCTIVE_TAGS.sorted().forEach { tag ->
                var cursor: PhotonIndexCursor? = null
                var exhausted = false
                repeat(MAX_RECONCILIATION_PAGES_PER_TAG) {
                    val page = delegate.query(
                        PhotonIndexQuery(
                            allTags = setOf(tag),
                            latestOnly = true,
                            order = PhotonIndexOrder.OLDEST_FIRST,
                            after = cursor,
                            limit = PhotonIndexQuery.HARD_PAGE_LIMIT,
                        )
                    )
                    refs += page
                    if (page.size < PhotonIndexQuery.HARD_PAGE_LIMIT) {
                        exhausted = true
                        return@repeat
                    }
                    cursor = PhotonIndexCursor(
                        order = PhotonIndexOrder.OLDEST_FIRST,
                        lastRef = page.last(),
                    )
                }
                if (!exhausted) {
                    val overflow = delegate.query(
                        PhotonIndexQuery(
                            allTags = setOf(tag),
                            latestOnly = true,
                            order = PhotonIndexOrder.OLDEST_FIRST,
                            after = cursor,
                            limit = 1,
                        )
                    )
                    require(overflow.isEmpty()) {
                        "Productive life reconciliation exceeds bounded capacity for tag: $tag"
                    }
                }
            }
            refs.mapNotNull { delegate.load(it) }
        } else {
            delegate.loadAll()
        }
        candidates
            .sortedWith(compareBy<Photon> { it.provenance.createdAt }.thenBy { it.id.value }.thenBy { it.revision })
            .forEach { photon ->
                val mode = productiveMode(photon) ?: return@forEach
                productiveIngress(photon, mode)
                reconciled += 1
            }
        return reconciled
    }

    internal companion object {
        private const val MAX_RECONCILIATION_PAGES_PER_TAG: Int = 16

        private val PRODUCTIVE_TAGS = setOf(
            "life-source-evidence",
            "life-source-gap",
            "memory-atom",
            "memory-crystal",
            "initial-data-bootstrap",
        )

        fun productiveMode(photon: Photon): PhotonIngressMode? = when {
            "life-source-evidence" in photon.tags -> PhotonIngressMode.ORIGIN
            "life-source-gap" in photon.tags -> PhotonIngressMode.ORIGIN
            "memory-atom" in photon.tags -> PhotonIngressMode.DERIVED
            "memory-crystal" in photon.tags -> PhotonIngressMode.DERIVED
            "initial-data-bootstrap" in photon.tags -> PhotonIngressMode.DERIVED
            else -> null
        }
    }
}
