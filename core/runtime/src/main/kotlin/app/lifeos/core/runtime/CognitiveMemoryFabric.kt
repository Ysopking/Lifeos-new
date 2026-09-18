package app.lifeos.core.runtime

import app.lifeos.core.model.GraphActivityClass
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.model.PhotonRevisionRef

/** Memory tier is an access strategy, never a truth/authority rank. */
enum class CognitiveMemoryTier { L0_HOT_WORKING, L1_ACTIVE_WORLD, L2_LONG_TERM, L3_ARCHIVE }

data class CognitiveMemoryEntry(
    val ref: PhotonRevisionRef,
    val activity: GraphActivityClass,
    val lastAccessRevision: Long,
    val activeMatter: Boolean = false,
    val activeConversation: Boolean = false,
) { init { require(lastAccessRevision > 0) } }

class MemoryTierPolicy {
    fun tierFor(entry: CognitiveMemoryEntry): CognitiveMemoryTier = when {
        entry.activeConversation || entry.activeMatter -> CognitiveMemoryTier.L0_HOT_WORKING
        entry.activity == GraphActivityClass.HOT -> CognitiveMemoryTier.L1_ACTIVE_WORLD
        entry.activity == GraphActivityClass.WARM -> CognitiveMemoryTier.L2_LONG_TERM
        else -> CognitiveMemoryTier.L3_ARCHIVE
    }

    fun partition(entries: Collection<CognitiveMemoryEntry>): Map<CognitiveMemoryTier, List<CognitiveMemoryEntry>> =
        entries.sortedWith(compareBy<CognitiveMemoryEntry>({ it.ref.photonId.value }, { it.ref.revision }))
            .groupBy(::tierFor)
}

data class CognitiveMemoryLayout(
    val l0: Set<PhotonRevisionRef>,
    val l1: Set<PhotonRevisionRef>,
    val l2: Set<PhotonRevisionRef>,
    val l3: Set<PhotonRevisionRef>,
) {
    fun tierOf(ref: PhotonRevisionRef): CognitiveMemoryTier? = when (ref) {
        in l0 -> CognitiveMemoryTier.L0_HOT_WORKING
        in l1 -> CognitiveMemoryTier.L1_ACTIVE_WORLD
        in l2 -> CognitiveMemoryTier.L2_LONG_TERM
        in l3 -> CognitiveMemoryTier.L3_ARCHIVE
        else -> null
    }

    companion object {
        fun empty() = CognitiveMemoryLayout(emptySet(), emptySet(), emptySet(), emptySet())
    }
}

/**
 * Physical memory fabric over the canonical PhotonRepository.
 *
 * L0/L1 retain concrete Photon objects in RAM. L2/L3 retain only exact revision references and are
 * hydrated lazily from the authoritative repository. Demotion therefore releases RAM without
 * changing or deleting evidence.
 */
class CognitiveMemoryFabric(
    private val photons: PhotonRepository,
    private val policy: MemoryTierPolicy = MemoryTierPolicy(),
) {
    @Volatile
    private var currentLayout: CognitiveMemoryLayout = CognitiveMemoryLayout.empty()

    @Volatile
    private var resident: Map<PhotonRevisionRef, Photon> = emptyMap()

    fun layout(): CognitiveMemoryLayout = currentLayout

    fun residentCount(): Int = resident.size

    fun resident(ref: PhotonRevisionRef): Photon? = resident[ref]

    suspend fun hydrate(ref: PhotonRevisionRef): Photon? {
        resident[ref]?.let { return it }
        val loaded = photons.load(ref.photonId) ?: return null
        return loaded.takeIf { it.revision == ref.revision }
    }

    fun hotContext(limit: Int): List<Photon> {
        require(limit > 0)
        val order = currentLayout.l0 + currentLayout.l1
        return order.asSequence()
            .mapNotNull(resident::get)
            .take(limit)
            .toList()
    }

    fun rebuild(
        entries: Collection<CognitiveMemoryEntry>,
        availablePhotons: Collection<Photon>,
        maxResidentPhotons: Int,
    ): CognitiveMemoryLayout {
        require(maxResidentPhotons > 0)
        val byRef = availablePhotons.associateBy { PhotonRevisionRef(it.id, it.revision) }
        val partition = policy.partition(entries)
        val l0Candidates = partition[CognitiveMemoryTier.L0_HOT_WORKING].orEmpty()
        val l1Candidates = partition[CognitiveMemoryTier.L1_ACTIVE_WORLD].orEmpty()
        val l2 = partition[CognitiveMemoryTier.L2_LONG_TERM].orEmpty()
        val l3 = partition[CognitiveMemoryTier.L3_ARCHIVE].orEmpty()

        val residentRefs = sequenceOf(l0Candidates, l1Candidates)
            .flatten()
            .sortedWith(
                compareByDescending<CognitiveMemoryEntry> { it.activeConversation }
                    .thenByDescending { it.activeMatter }
                    .thenByDescending { it.lastAccessRevision }
                    .thenByDescending { it.ref.revision }
                    .thenBy { it.ref.stableKey }
            )
            .take(maxResidentPhotons)
            .map { it.ref }
            .toCollection(linkedSetOf())

        val residentMap = residentRefs.mapNotNull { ref ->
            byRef[ref]?.let { ref to it }
        }.toMap(linkedMapOf())

        val l0Refs = l0Candidates.map { it.ref }.filterTo(linkedSetOf()) { it in residentMap }
        val l1Refs = l1Candidates.map { it.ref }.filterTo(linkedSetOf()) { it in residentMap }
        val overflowHot = (l0Candidates + l1Candidates).map { it.ref }
            .filterTo(linkedSetOf()) { it !in residentMap }

        val layout = CognitiveMemoryLayout(
            l0 = l0Refs,
            l1 = l1Refs,
            l2 = l2.mapTo(linkedSetOf()) { it.ref } + overflowHot,
            l3 = l3.mapTo(linkedSetOf()) { it.ref },
        )
        resident = residentMap
        currentLayout = layout
        return layout
    }
}
