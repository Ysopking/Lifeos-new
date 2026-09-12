package app.lifeos.core.runtime.life

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.StableCognitiveIds
import java.time.Instant

enum class RetentionClass { HOT, WARM, ARCHIVE }

data class MemoryIntegrityIssue(
    val photonId: PhotonId,
    val message: String,
)

data class MemoryIntegrityReport(
    val issues: List<MemoryIntegrityIssue>,
    val checkedPhotons: Int,
    val fingerprint: String,
) {
    val healthy: Boolean get() = issues.isEmpty()
}

class MemoryIntegrityVerifier {
    fun verify(photons: Collection<Photon>): MemoryIntegrityReport {
        val latest = photons.groupBy { it.id }.mapValues { (_, revisions) -> revisions.maxBy { it.revision } }
        val ids = latest.keys
        val issues = latest.values.flatMap { photon ->
            buildList {
                photon.provenance.parentIds
                    .filterNot(ids::contains)
                    .sortedBy { it.value }
                    .forEach { missing ->
                        add(MemoryIntegrityIssue(photon.id, "missing-parent:${missing.value}"))
                    }
                photon.relations
                    .map { it.target }
                    .filterNot(ids::contains)
                    .sortedBy { it.value }
                    .forEach { missing ->
                        add(MemoryIntegrityIssue(photon.id, "missing-relation:${missing.value}"))
                    }
            }
        }.sortedWith(compareBy<MemoryIntegrityIssue> { it.photonId.value }.thenBy { it.message })
        return MemoryIntegrityReport(
            issues = issues,
            checkedPhotons = latest.size,
            fingerprint = StableCognitiveIds.fingerprint(
                "memory-integrity/v1",
                latest.size.toString(),
                *issues.flatMap { listOf(it.photonId.value, it.message) }.toTypedArray(),
            ),
        )
    }
}

data class MemoryRetentionDecision(
    val photonId: PhotonId,
    val retentionClass: RetentionClass,
    val reason: String,
    val stage: MemoryStage = when (retentionClass) {
        RetentionClass.HOT -> MemoryStage.HOT
        RetentionClass.WARM -> MemoryStage.WARM
        RetentionClass.ARCHIVE -> MemoryStage.COLD
    },
)

data class MemoryRetentionPlan(
    val decisions: List<MemoryRetentionDecision>,
    val fingerprint: String,
)

/**
 * Backwards-compatible Block G facade over the four-stage LongTermMemoryEngine.
 * RetentionClass stays available to old callers; MemoryStage is the authoritative new lifecycle.
 * Destructive deletion remains explicitly outside this seam.
 */
class CognitiveMemoryCompactor(
    private val longTermMemory: LongTermMemoryEngine = LongTermMemoryEngine(),
) {
    fun plan(
        photons: Collection<Photon>,
        accessLedger: MemoryAccessLedger = MemoryAccessLedger(),
        now: Instant = Instant.now(),
    ): MemoryRetentionPlan {
        val projection = longTermMemory.project(photons, accessLedger, now)
        val decisions = projection.decisions.map { decision ->
            val retention = when (decision.toStage) {
                MemoryStage.HOT -> RetentionClass.HOT
                MemoryStage.WARM -> RetentionClass.WARM
                MemoryStage.COLD, MemoryStage.CRYSTALLIZED -> RetentionClass.ARCHIVE
            }
            MemoryRetentionDecision(
                photonId = decision.photonId,
                retentionClass = retention,
                reason = decision.reason,
                stage = decision.toStage,
            )
        }.sortedBy { it.photonId.value }
        return MemoryRetentionPlan(
            decisions = decisions,
            fingerprint = StableCognitiveIds.fingerprint(
                "memory-retention-plan/v2",
                *decisions.flatMap {
                    listOf(it.photonId.value, it.retentionClass.name, it.stage.name, it.reason)
                }.toTypedArray(),
            ),
        )
    }
}
