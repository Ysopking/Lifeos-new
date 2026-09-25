package app.lifeos.core.runtime.boot

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonPhase
import app.lifeos.core.model.PhotonIndexOrder
import app.lifeos.core.model.PhotonIndexQuery
import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.RevisionedPhotonRepository
import app.lifeos.core.runtime.cognition.CognitionJournalIndex
import app.lifeos.core.runtime.cognition.CognitionJournalIntegrityVerifier
import kotlinx.coroutines.CancellationException

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
    val allPhotons: List<Photon> = hot + warm,
    val deferredRefs: List<PhotonRevisionRef> = emptyList(),
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
    fun assess(
        photons: List<Photon>,
        knownIds: Set<PhotonId> = photons.mapTo(hashSetOf()) { it.id },
    ): List<PhotonIntegrityAssessment> {
        val duplicateIds = photons
            .groupingBy { it.id }
            .eachCount()
            .filterValues { it > 1 }
            .keys
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
    private val criticalHydration: Boolean = false,
    private val criticalHotLimit: Int = DEFAULT_CRITICAL_HOT_LIMIT,
) {
    init {
        require(criticalHotLimit in 1..PhotonIndexQuery.HARD_PAGE_LIMIT) {
            "Critical Photon hydration limit must fit one bounded index page"
        }
    }

    suspend fun rehydrate(): PhotonRehydrationResult {
        val revisioned = repository as? RevisionedPhotonRepository
        return if (criticalHydration && revisioned != null) {
            rehydrateCritical(revisioned)
        } else {
            rehydrateAll()
        }
    }

    suspend fun rehydrateAll(): PhotonRehydrationResult {
        val sessionReport = bootReadSession?.photonReport()
        val fallbackReport = if (sessionReport == null) repository.loadReport() else null
        val report = sessionReport ?: checkNotNull(fallbackReport)
        val photons = report.photons
        val unreadable = report.unreadableFiles

        val verifier = CognitionJournalIntegrityVerifier(repository, journalIndex)
        if (sessionReport != null) verifier.verify(photons) else verifier.verify()

        return classify(
            photons = photons,
            unreadable = unreadable,
            knownIds = photons.mapTo(hashSetOf()) { it.id },
            allPhotons = photons,
        )
    }

    private suspend fun rehydrateCritical(
        revisioned: RevisionedPhotonRepository,
    ): PhotonRehydrationResult {
        val index = revisioned.indexReport()
        val explicitHot = revisioned.query(
            PhotonIndexQuery(
                allTags = setOf(HOT_TAG),
                latestOnly = true,
                order = PhotonIndexOrder.HIGHEST_SEMANTIC_MASS,
                limit = criticalHotLimit,
            )
        )
        val active = revisioned.query(
            PhotonIndexQuery(
                phases = setOf(PhotonPhase.ACTIVE, PhotonPhase.REFLECTING),
                latestOnly = true,
                order = PhotonIndexOrder.HIGHEST_SEMANTIC_MASS,
                limit = criticalHotLimit,
            )
        )
        val selected = (explicitHot + active)
            .distinct()
            .take(criticalHotLimit)
        val unreadable = index.unreadableRevisionFiles.toMutableList()
        val loadedRefs = linkedSetOf<PhotonRevisionRef>()
        val photons = buildList {
            selected.forEach { photonRef ->
                val photon = try {
                    revisioned.load(photonRef)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    unreadable += "revision:${photonRef.photonId.value}:${photonRef.revision}"
                    null
                }
                if (photon == null) {
                    unreadable += "missing:${photonRef.photonId.value}:${photonRef.revision}"
                } else {
                    loadedRefs += photonRef
                    add(photon)
                }
            }
        }
        val allRefs = index.latestRefs.values
            .sortedWith(compareBy<PhotonRevisionRef> { it.photonId.value }.thenBy { it.revision })

        return classify(
            photons = photons,
            unreadable = unreadable.distinct().sorted(),
            knownIds = index.latestRefs.keys,
            allPhotons = photons,
            deferredRefs = allRefs.filterNot(loadedRefs::contains),
        )
    }

    private fun classify(
        photons: List<Photon>,
        unreadable: List<String>,
        knownIds: Set<PhotonId>,
        allPhotons: List<Photon>,
        deferredRefs: List<PhotonRevisionRef> = emptyList(),
    ): PhotonRehydrationResult {
        val assessments = validator.assess(photons, knownIds)
        val quarantined = assessments
            .asSequence()
            .filter { it.state == PhotonIntegrityState.QUARANTINED }
            .map { it.photonId }
            .toSet()
        val hot = mutableListOf<Photon>()
        val warm = mutableListOf<Photon>()
        val cold = mutableListOf<PhotonId>()

        photons.forEach { photon ->
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
            unreadableFiles = unreadable,
            allPhotons = allPhotons,
            deferredRefs = deferredRefs,
        )
    }

    private companion object {
        const val HOT_TAG = "hot"
        const val DEFAULT_CRITICAL_HOT_LIMIT = 128
    }
}
