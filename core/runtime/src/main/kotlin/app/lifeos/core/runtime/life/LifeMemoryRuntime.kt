package app.lifeos.core.runtime.life

import app.lifeos.core.model.CanonicalPhotonState
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.StableCognitiveIds
import java.time.Instant

/** Legacy projection retained for callers while MemoryStage becomes the four-stage source of truth. */
enum class LifeMemoryTier {
    HOT,
    WARM,
    ARCHIVE,
}

private fun MemoryStage.toLegacyTier(): LifeMemoryTier = when (this) {
    MemoryStage.HOT -> LifeMemoryTier.HOT
    MemoryStage.WARM -> LifeMemoryTier.WARM
    MemoryStage.COLD, MemoryStage.CRYSTALLIZED -> LifeMemoryTier.ARCHIVE
}

data class LifeMemoryEntry(
    val photonId: PhotonId,
    val revision: Long,
    val stateHash: String,
    val tags: Set<String>,
    val semanticMass: Double,
    val confidence: Double,
    val tier: LifeMemoryTier,
    val stage: MemoryStage = when (tier) {
        LifeMemoryTier.HOT -> MemoryStage.HOT
        LifeMemoryTier.WARM -> MemoryStage.WARM
        LifeMemoryTier.ARCHIVE -> MemoryStage.COLD
    },
)

data class LifeMemorySnapshot(
    val entries: List<LifeMemoryEntry>,
    val fingerprint: String,
) {
    fun findByTag(tag: String): List<LifeMemoryEntry> = entries.filter { tag in it.tags }
    fun contains(id: PhotonId): Boolean = entries.any { it.photonId == id }
    fun findByStage(stage: MemoryStage): List<LifeMemoryEntry> = entries.filter { it.stage == stage }
}

/**
 * Block C boot/life-memory projection. The encrypted Photon repository stays authoritative.
 * Four-stage temporal compaction is projected by LongTermMemoryEngine without mutating evidence.
 */
class LifeMemoryIndex(
    private val longTermMemory: LongTermMemoryEngine = LongTermMemoryEngine(),
) {
    fun rebuild(
        photons: Collection<Photon>,
        accessLedger: MemoryAccessLedger = MemoryAccessLedger(),
        now: Instant = Instant.now(),
    ): LifeMemorySnapshot {
        val canonical = photons
            .groupBy { it.id }
            .mapValues { (_, revisions) -> revisions.maxBy { it.revision } }
            .values
            .sortedWith(compareBy<Photon> { it.provenance.createdAt }.thenBy { it.id.value })
        val projection = longTermMemory.project(canonical, accessLedger, now)
        val stages = projection.decisions.associate { it.photonId to it.toStage }

        val entries = canonical.map { photon ->
            val stage = stages.getValue(photon.id)
            LifeMemoryEntry(
                photonId = photon.id,
                revision = photon.revision,
                stateHash = CanonicalPhotonState.inputHash(photon).value,
                tags = photon.tags.toSortedSet(),
                semanticMass = photon.semanticMass,
                confidence = photon.confidence,
                tier = stage.toLegacyTier(),
                stage = stage,
            )
        }
        return LifeMemorySnapshot(
            entries = entries,
            fingerprint = StableCognitiveIds.fingerprint(
                "life-memory-snapshot/v2",
                *entries.flatMap { entry ->
                    listOf(
                        entry.photonId.value,
                        entry.revision.toString(),
                        entry.stateHash,
                        entry.stage.name,
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
    val coldCount: Int = 0,
    val crystallizedCount: Int = 0,
    val longTermProjection: LongTermMemoryProjection? = null,
)

class BootLifeMemoryRehydrator(
    private val index: LifeMemoryIndex = LifeMemoryIndex(),
    private val longTermMemory: LongTermMemoryEngine = LongTermMemoryEngine(),
) {
    fun rehydrate(
        photons: Collection<Photon>,
        accessLedger: MemoryAccessLedger = MemoryAccessLedger(),
        now: Instant = Instant.now(),
    ): BootLifeMemoryReport {
        val snapshot = index.rebuild(photons, accessLedger, now)
        val projection = longTermMemory.project(photons, accessLedger, now)
        val cold = snapshot.entries.count { it.stage == MemoryStage.COLD }
        val crystallized = snapshot.entries.count { it.stage == MemoryStage.CRYSTALLIZED }
        return BootLifeMemoryReport(
            snapshot = snapshot,
            hotCount = snapshot.entries.count { it.stage == MemoryStage.HOT },
            warmCount = snapshot.entries.count { it.stage == MemoryStage.WARM },
            archiveCount = cold + crystallized,
            coldCount = cold,
            crystallizedCount = crystallized,
            longTermProjection = projection,
        )
    }
}
