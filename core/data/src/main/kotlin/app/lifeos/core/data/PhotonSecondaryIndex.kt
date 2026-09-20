package app.lifeos.core.data

import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonIndexEntry
import app.lifeos.core.model.PhotonIndexOrder
import app.lifeos.core.model.PhotonIndexQuery
import app.lifeos.core.model.PhotonPhase
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.matches

/**
 * Immutable, reconstructible secondary projection over one Photon index snapshot.
 *
 * The encrypted primary index remains authoritative metadata. This projection exists only in
 * memory and can always be rebuilt from [PhotonIndexEntry] values. Querying therefore no longer
 * needs to filter and sort the complete primary map while EncryptedPhotonStore holds its mutex.
 */
internal class PhotonSecondaryIndex private constructor(
    private val entries: Map<PhotonRevisionRef, PhotonIndexEntry>,
    private val byId: Map<PhotonId, Set<PhotonRevisionRef>>,
    private val byPhase: Map<PhotonPhase, Set<PhotonRevisionRef>>,
    private val byMime: Map<String, Set<PhotonRevisionRef>>,
    private val byTag: Map<String, Set<PhotonRevisionRef>>,
    private val ordered: Map<PhotonIndexOrder, List<PhotonRevisionRef>>,
) {
    fun query(query: PhotonIndexQuery): List<PhotonRevisionRef> {
        val constraints = mutableListOf<Set<PhotonRevisionRef>>()

        if (query.ids.isNotEmpty()) {
            constraints += union(query.ids.map { byId[it].orEmpty() })
        }
        if (query.phases.isNotEmpty()) {
            constraints += union(query.phases.map { byPhase[it].orEmpty() })
        }
        if (query.mimeTypes.isNotEmpty()) {
            constraints += union(query.mimeTypes.map { byMime[it].orEmpty() })
        }
        query.allTags.forEach { tag ->
            constraints += byTag[tag].orEmpty()
        }
        if (query.anyTags.isNotEmpty()) {
            constraints += union(query.anyTags.map { byTag[it].orEmpty() })
        }

        val candidateRefs: Collection<PhotonRevisionRef> =
            constraints.minByOrNull { it.size } ?: entries.keys
        val orderedMatches = ordered.getValue(query.order)
            .asSequence()
            .filter { it in candidateRefs }
            .filter { entries.getValue(it).matches(query) }
            .toList()

        val startIndex = query.after?.let { cursor ->
            val cursorIndex = orderedMatches.indexOf(cursor.lastRef)
            require(cursorIndex >= 0) {
                "Photon index cursor is not present in the filtered result set"
            }
            cursorIndex + 1
        } ?: 0

        return orderedMatches
            .asSequence()
            .drop(startIndex)
            .take(query.limit)
            .toList()
    }

    companion object {
        fun build(entries: Collection<PhotonIndexEntry>): PhotonSecondaryIndex {
            val entryMap = entries.associateByTo(
                linkedMapOf(),
                PhotonIndexEntry::ref,
            )
            require(entryMap.size == entries.size) {
                "Photon secondary index cannot contain duplicate revision refs"
            }

            val byId = linkedMapOf<PhotonId, MutableSet<PhotonRevisionRef>>()
            val byPhase = linkedMapOf<PhotonPhase, MutableSet<PhotonRevisionRef>>()
            val byMime = linkedMapOf<String, MutableSet<PhotonRevisionRef>>()
            val byTag = linkedMapOf<String, MutableSet<PhotonRevisionRef>>()

            entryMap.values.forEach { entry ->
                byId.getOrPut(entry.ref.photonId, ::linkedSetOf) += entry.ref
                byPhase.getOrPut(entry.phase, ::linkedSetOf) += entry.ref
                byMime.getOrPut(entry.mimeType, ::linkedSetOf) += entry.ref
                entry.tags.sorted().forEach { tag ->
                    byTag.getOrPut(tag, ::linkedSetOf) += entry.ref
                }
            }

            return PhotonSecondaryIndex(
                entries = entryMap,
                byId = freeze(byId),
                byPhase = freeze(byPhase),
                byMime = freeze(byMime),
                byTag = freeze(byTag),
                ordered = mapOf(
                    PhotonIndexOrder.IDENTITY to orderedRefs(
                        entryMap.values,
                        compareBy<PhotonIndexEntry> { it.ref.photonId.value }
                            .thenBy { it.ref.revision },
                    ),
                    PhotonIndexOrder.NEWEST_FIRST to orderedRefs(
                        entryMap.values,
                        compareByDescending<PhotonIndexEntry> { it.createdAt }
                            .thenBy { it.ref.photonId.value }
                            .thenByDescending { it.ref.revision },
                    ),
                    PhotonIndexOrder.OLDEST_FIRST to orderedRefs(
                        entryMap.values,
                        compareBy<PhotonIndexEntry> { it.createdAt }
                            .thenBy { it.ref.photonId.value }
                            .thenBy { it.ref.revision },
                    ),
                    PhotonIndexOrder.HIGHEST_SEMANTIC_MASS to orderedRefs(
                        entryMap.values,
                        compareByDescending<PhotonIndexEntry> { it.semanticMass }
                            .thenByDescending { it.createdAt }
                            .thenBy { it.ref.photonId.value },
                    ),
                    PhotonIndexOrder.HIGHEST_CONFIDENCE to orderedRefs(
                        entryMap.values,
                        compareByDescending<PhotonIndexEntry> { it.confidence }
                            .thenByDescending { it.createdAt }
                            .thenBy { it.ref.photonId.value },
                    ),
                ),
            )
        }

        private fun <K> freeze(
            source: Map<K, MutableSet<PhotonRevisionRef>>,
        ): Map<K, Set<PhotonRevisionRef>> =
            source.mapValues { (_, refs) -> refs.toSet() }

        private fun orderedRefs(
            entries: Collection<PhotonIndexEntry>,
            comparator: Comparator<PhotonIndexEntry>,
        ): List<PhotonRevisionRef> =
            entries.sortedWith(comparator).map(PhotonIndexEntry::ref)

        private fun union(
            groups: Iterable<Set<PhotonRevisionRef>>,
        ): Set<PhotonRevisionRef> = buildSet {
            groups.forEach(::addAll)
        }
    }
}
