package app.lifeos.core.runtime.life

import app.lifeos.core.model.CanonicalPhotonState
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.StableCognitiveIds

enum class LifeMemoryTier {
    HOT,
    WARM,
    ARCHIVE,
}

data class LifeMemoryEntry(
    val photonId: PhotonId,
    val revision: Long,
    val stateHash: String,
    val tags: Set<String>,
    val semanticMass: Double,
    val confidence: Double,
    val tier: LifeMemoryTier,
)

data class LifeMemorySnapshot(
    val entries: List<LifeMemoryEntry>,
    val fingerprint: String,
) {
    fun findByTag(tag: String): List<LifeMemoryEntry> = entries.filter { tag in it.tags }
    fun contains(id: PhotonId): Boolean = entries.any { it.photonId == id }
}

/**
 * Block C boot/life-memory projection. The encrypted Photon repository stays authoritative; this
 * index is a deterministic projection that can be rebuilt after every cold start.
 */
class LifeMemoryIndex {
    fun rebuild(photons: Collection<Photon>): LifeMemorySnapshot {
        val canonical = photons
            .groupBy { it.id }
            .mapValues { (_, revisions) -> revisions.maxBy { it.revision } }
            .values
            .sortedWith(compareBy<Photon> { it.provenance.createdAt }.thenBy { it.id.value })

        val entries = canonical.map { photon ->
            val tier = when {
                "chat" in photon.tags || "goal" in photon.tags || photon.semanticMass >= 0.75 -> LifeMemoryTier.HOT
                photon.semanticMass >= 0.35 || photon.confidence >= 0.8 -> LifeMemoryTier.WARM
                else -> LifeMemoryTier.ARCHIVE
            }
            LifeMemoryEntry(
                photonId = photon.id,
                revision = photon.revision,
                stateHash = CanonicalPhotonState.inputHash(photon).value,
                tags = photon.tags.toSortedSet(),
                semanticMass = photon.semanticMass,
                confidence = photon.confidence,
                tier = tier,
            )
        }
        return LifeMemorySnapshot(
            entries = entries,
            fingerprint = StableCognitiveIds.fingerprint(
                "life-memory-snapshot/v1",
                *entries.flatMap { entry ->
                    listOf(
                        entry.photonId.value,
                        entry.revision.toString(),
                        entry.stateHash,
                        entry.tier.name,
                    )
                }.toTypedArray(),
            ),
        )
    }
}

data class BootLifeMemoryReport(
    val snapshot: LifeMemorySnapshot,
    val hotCount: Int,
    val warmCount: Int,
    val archiveCount: Int,
)

class BootLifeMemoryRehydrator(
    private val index: LifeMemoryIndex = LifeMemoryIndex(),
) {
    fun rehydrate(photons: Collection<Photon>): BootLifeMemoryReport {
        val snapshot = index.rebuild(photons)
        return BootLifeMemoryReport(
            snapshot = snapshot,
            hotCount = snapshot.entries.count { it.tier == LifeMemoryTier.HOT },
            warmCount = snapshot.entries.count { it.tier == LifeMemoryTier.WARM },
            archiveCount = snapshot.entries.count { it.tier == LifeMemoryTier.ARCHIVE },
        )
    }
}
