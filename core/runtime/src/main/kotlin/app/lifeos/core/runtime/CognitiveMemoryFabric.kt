package app.lifeos.core.runtime

import app.lifeos.core.model.GraphActivityClass
import app.lifeos.core.model.PhotonRevisionRef

/** Physical memory tiers are policy, never truth or authority. */
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
