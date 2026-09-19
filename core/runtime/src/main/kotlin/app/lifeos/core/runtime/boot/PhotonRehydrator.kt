package app.lifeos.core.runtime.boot

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonPhase
import app.lifeos.core.model.PhotonRevisionRef
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
    val cold: List<PhotonRevisionRef>,
    val assessments: List<PhotonIntegrityAssessment>,
    val unreadableFiles: List<String>,
    val deferredWarm: List<PhotonRevisionRef> = emptyList(),
) {
    val loadedPhotons: List<Photon> = hot + warm

    @Deprecated("Boot no longer materializes the complete Photon history")
    val allPhotons: List<Photon> get() = loadedPhotons

    val restoredCount: Long =
        (hot.size + warm.size + cold.size + deferredWarm.size).toLong()
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
    fun assess(
        photons: List<Photon>,
        knownPhotonIds: Set<PhotonId> = photons.mapTo(hashSetOf()) { it.id },
    ): List<PhotonIntegrityAssessment> {
        val duplicateIds = photons
            .groupingBy { it.id }
            .eachCount()
            .filterValues { it > 1 }
            .keys
        val knownIds = knownPhotonIds

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
    private val bootReadSession: BootReadSession? = null,
) {
    suspend fun rehydrate(): PhotonRehydrationResult {
        val sessionSnapshot = bootReadSession?.snapshot()
        val fallbackReport = if (sessionSnapshot == null) repository.loadReport() else null
        val photons = sessionSnapshot?.photons ?: checkNotNull(fallbackReport).photons
        val unreadable = sessionSnapshot?.readFailures
            ?.filter { it.source == BootSnapshotSource.PHOTON }
            ?.mapNotNull { it.entry }
            ?: checkNotNull(fallbackReport).unreadableFiles

        // Journal integrity is bounded by the durable cognition index and therefore does not
        // require all user Photon payloads to be decrypted into the boot snapshot.
        CognitionJournalIntegrityVerifier(repository, journalIndex).verify()

        val knownPhotonIds = sessionSnapshot
            ?.photonEntries
            ?.mapTo(hashSetOf()) { it.ref.photonId }
            ?.takeIf { it.isNotEmpty() }
            ?: photons.mapTo(hashSetOf()) { it.id }
        val assessments = validator.assess(photons, knownPhotonIds)
        val quarantined = assessments
            .asSequence()
            .filter { it.state == PhotonIntegrityState.QUARANTINED }
            .map { it.photonId }
            .toSet()

        if (sessionSnapshot != null && sessionSnapshot.photonEntries.isNotEmpty()) {
            val byRef = photons.associateBy { PhotonRevisionRef(it.id, it.revision) }
            return PhotonRehydrationResult(
                hot = sessionSnapshot.hotPhotonRefs
                    .mapNotNull(byRef::get)
                    .filterNot { it.id in quarantined },
                warm = sessionSnapshot.warmPhotonRefs
                    .mapNotNull(byRef::get)
                    .filterNot { it.id in quarantined },
                cold = sessionSnapshot.coldPhotonRefs,
                assessments = assessments,
                unreadableFiles = unreadable,
                deferredWarm = sessionSnapshot.deferredWarmPhotonRefs,
            )
        }

        val hot = mutableListOf<Photon>()
        val warm = mutableListOf<Photon>()
        val cold = mutableListOf<PhotonRevisionRef>()

        photons.forEach { photon ->
            if (photon.id in quarantined) return@forEach
            when (hydrationPolicy.tier(photon)) {
                PhotonHydrationTier.HOT -> hot += photon
                PhotonHydrationTier.WARM -> warm += photon
                PhotonHydrationTier.COLD -> cold += PhotonRevisionRef(photon.id, photon.revision)
            }
        }

        return PhotonRehydrationResult(
            hot = hot,
            warm = warm,
            cold = cold,
            assessments = assessments,
            unreadableFiles = unreadable,
        )
    }
}
