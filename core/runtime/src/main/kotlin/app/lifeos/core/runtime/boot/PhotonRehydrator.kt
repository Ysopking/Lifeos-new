package app.lifeos.core.runtime.boot

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonPhase
import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.runtime.cognition.CognitionJournalIndex
import app.lifeos.core.runtime.cognition.CognitionJournalIntegrityVerifier

enum class PhotonHydrationTier {
    HOT,
    WARM,
    COLD,
}

enum class PhotonIntegrityState {
    VALID,
    ORPHANED,
    QUARANTINED,
}

data class PhotonIntegrityAssessment(
    val photonId: PhotonId,
    val state: PhotonIntegrityState,
    val issues: List<String> = emptyList(),
)

data class PhotonRehydrationResult(
    val hot: List<Photon>,
    val warm: List<Photon>,
    val cold: List<PhotonId>,
    val assessments: List<PhotonIntegrityAssessment>,
    val unreadableFiles: List<String>,
) {
    val restoredCount: Long = (hot.size + warm.size + cold.size).toLong()
    val quarantined: Set<PhotonId> = assessments
        .asSequence()
        .filter { it.state == PhotonIntegrityState.QUARANTINED }
        .map { it.photonId }
        .toSet()
}

fun interface PhotonHydrationPolicy {
    fun tier(photon: Photon): PhotonHydrationTier
}

object DefaultPhotonHydrationPolicy : PhotonHydrationPolicy {
    override fun tier(photon: Photon): PhotonHydrationTier = when {
        "hot" in photon.tags -> PhotonHydrationTier.HOT
        photon.phase == PhotonPhase.ACTIVE || photon.phase == PhotonPhase.REFLECTING -> PhotonHydrationTier.HOT
        photon.phase == PhotonPhase.ARCHIVED -> PhotonHydrationTier.COLD
        else -> PhotonHydrationTier.WARM
    }
}

class PhotonIntegrityValidator {
    fun assess(photons: List<Photon>): List<PhotonIntegrityAssessment> {
        val duplicateIds = photons
            .groupingBy { it.id }
            .eachCount()
            .filterValues { it > 1 }
            .keys
        val knownIds = photons.mapTo(hashSetOf()) { it.id }

        return photons.map { photon ->
            val issues = buildList {
                if (photon.id in duplicateIds) add("duplicate-photon-id")
                photon.relations
                    .asSequence()
                    .filter { it.target !in knownIds }
                    .forEach { add("missing-relation-target:${it.target.value}") }
            }
            val state = when {
                photon.id in duplicateIds -> PhotonIntegrityState.QUARANTINED
                issues.isNotEmpty() -> PhotonIntegrityState.ORPHANED
                else -> PhotonIntegrityState.VALID
            }
            PhotonIntegrityAssessment(photon.id, state, issues)
        }
    }
}

class PhotonRehydrator(
    private val repository: PhotonRepository,
    private val hydrationPolicy: PhotonHydrationPolicy = DefaultPhotonHydrationPolicy,
    private val validator: PhotonIntegrityValidator = PhotonIntegrityValidator(),
    private val journalIndex: CognitionJournalIndex? = null,
) {
    suspend fun rehydrate(): PhotonRehydrationResult {
        // Internal cognition journals share the encrypted Photon repository. Validate their
        // schema and deterministic identities before any journal Photon can participate in boot.
        CognitionJournalIntegrityVerifier(repository, journalIndex).verify()

        val report = repository.loadReport()
        val assessments = validator.assess(report.photons)
        val quarantined = assessments
            .asSequence()
            .filter { it.state == PhotonIntegrityState.QUARANTINED }
            .map { it.photonId }
            .toSet()

        val hot = mutableListOf<Photon>()
        val warm = mutableListOf<Photon>()
        val cold = mutableListOf<PhotonId>()

        report.photons.forEach { photon ->
            if (photon.id in quarantined) return@forEach
            when (hydrationPolicy.tier(photon)) {
                PhotonHydrationTier.HOT -> hot += photon
                PhotonHydrationTier.WARM -> warm += photon
                PhotonHydrationTier.COLD -> cold += photon.id
            }
        }

        return PhotonRehydrationResult(
            hot = hot,
            warm = warm,
            cold = cold,
            assessments = assessments,
            unreadableFiles = report.unreadableFiles,
        )
    }
}
