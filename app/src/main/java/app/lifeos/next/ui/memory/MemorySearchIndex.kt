package app.lifeos.next.ui.memory

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.next.ui.perf.PhotonRevisionKey
import app.lifeos.next.ui.perf.canonicalPhotonRevisionKey
import java.util.Locale

/**
 * Bounded in-memory search acceleration over already-authorized local Photon metadata.
 *
 * Only normalized search strings are bounded. The exact latest-revision Photon references remain
 * the current runtime snapshot, and sources outside the precomputed window fall back to on-demand
 * normalization so search correctness is never traded for cache size.
 */
class MemorySearchIndex private constructor(
    private val revisionKey: List<PhotonRevisionKey>,
    private val latest: List<Photon>,
    private val latestById: Map<PhotonId, Photon>,
    private val normalizedTextById: Map<PhotonId, String>,
) {
    val indexedSourceCount: Int get() = normalizedTextById.size
    val latestSourceCount: Int get() = latest.size

    fun latestRevisions(): List<Photon> = latest

    fun source(photonId: PhotonId): Photon? = latestById[photonId]

    fun matchesSource(photonId: PhotonId, normalizedQuery: String): Boolean {
        if (normalizedQuery.isBlank()) return photonId in latestById
        val source = latestById[photonId] ?: return false
        val searchable = normalizedTextById[photonId] ?: source.normalizedSearchText()
        return searchable.contains(normalizedQuery)
    }

    fun matchesSource(photon: Photon, normalizedQuery: String): Boolean =
        matchesSource(photon.id, normalizedQuery)

    fun hasSameRevisionSet(photons: Iterable<Photon>): Boolean =
        revisionKey == canonicalPhotonRevisionKey(photons)

    companion object {
        const val DEFAULT_MAX_INDEXED_SOURCES = 1024

        fun reuseOrBuild(
            existing: MemorySearchIndex?,
            photons: Iterable<Photon>,
            maxIndexedSources: Int = DEFAULT_MAX_INDEXED_SOURCES,
        ): MemorySearchIndex {
            require(maxIndexedSources > 0) { "maxIndexedSources must be positive" }
            val snapshot = photons.toList()
            if (existing?.hasSameRevisionSet(snapshot) == true) return existing
            return build(snapshot, maxIndexedSources)
        }

        fun build(
            photons: Iterable<Photon>,
            maxIndexedSources: Int = DEFAULT_MAX_INDEXED_SOURCES,
        ): MemorySearchIndex {
            require(maxIndexedSources > 0) { "maxIndexedSources must be positive" }
            val snapshot = photons.toList()
            val latest = snapshot
                .groupBy { it.id }
                .map { (_, revisions) -> revisions.maxBy { it.revision } }
                .sortedWith(compareBy<Photon> { it.provenance.createdAt }.thenBy { it.id.value })
            val latestById = latest.associateBy { it.id }
            val indexed = latest
                .asSequence()
                .sortedWith(
                    compareByDescending<Photon> { it.provenance.createdAt }
                        .thenByDescending { it.id.value }
                )
                .take(maxIndexedSources)
                .associate { photon -> photon.id to photon.normalizedSearchText() }
            return MemorySearchIndex(
                revisionKey = canonicalPhotonRevisionKey(snapshot),
                latest = latest,
                latestById = latestById,
                normalizedTextById = indexed,
            )
        }
    }
}

private fun Photon.normalizedSearchText(): String = buildString {
    append(content).append('\n')
    append(tags.sorted().joinToString(" ")).append('\n')
    append(provenance.source).append('\n')
    append(provenance.actor)
}.lowercase(Locale.ROOT)
