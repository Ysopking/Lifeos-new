package app.lifeos.core.runtime.life

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.StableCognitiveIds

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
)

data class MemoryRetentionPlan(
    val decisions: List<MemoryRetentionDecision>,
    val fingerprint: String,
)

/** Block G compaction policy. It classifies only; destructive deletion remains outside this seam. */
class CognitiveMemoryCompactor {
    fun plan(photons: Collection<Photon>): MemoryRetentionPlan {
        val latest = photons.groupBy { it.id }.mapValues { (_, revisions) -> revisions.maxBy { it.revision } }.values
        val decisions = latest.map { photon ->
            when {
                "chat" in photon.tags || "goal" in photon.tags || photon.semanticMass >= 0.8 ->
                    MemoryRetentionDecision(photon.id, RetentionClass.HOT, "active-or-high-mass")
                photon.confidence >= 0.8 || photon.semanticMass >= 0.4 ->
                    MemoryRetentionDecision(photon.id, RetentionClass.WARM, "reliable-or-relevant")
                else -> MemoryRetentionDecision(photon.id, RetentionClass.ARCHIVE, "low-active-salience")
            }
        }.sortedBy { it.photonId.value }
        return MemoryRetentionPlan(
            decisions = decisions,
            fingerprint = StableCognitiveIds.fingerprint(
                "memory-retention-plan/v1",
                *decisions.flatMap { listOf(it.photonId.value, it.retentionClass.name, it.reason) }.toTypedArray(),
            ),
        )
    }
}
