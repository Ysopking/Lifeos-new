package app.lifeos.core.runtime.reasoning

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.model.PhotonId
import java.util.concurrent.atomic.AtomicReference

data class MetaCandidateRef(
    val photonId: PhotonId,
    val sourceRevision: Long,
    val domain: MetaDomainFamily,
    val observationFingerprint: String,
    val comparisonFingerprint: String,
    val stateFingerprint: String,
) {
    init {
        require(sourceRevision > 0L)
        require(observationFingerprint.isNotBlank())
        require(comparisonFingerprint.isNotBlank())
        require(stateFingerprint.isNotBlank())
    }

    val fingerprint: String = StableFieldIds.fingerprint(
        "meta-candidate-ref/v1",
        photonId.value,
        sourceRevision.toString(),
        domain.name,
        observationFingerprint,
        comparisonFingerprint,
        stateFingerprint,
    )
}

data class MetaCandidateGroup(
    val equivalenceCandidateFingerprint: String,
    val candidates: List<MetaCandidateRef>,
    val fingerprint: String,
) {
    init {
        require(equivalenceCandidateFingerprint.isNotBlank())
        require(candidates.size >= 2)
        require(
            candidates == candidates
                .distinctBy { it.fingerprint }
                .sortedWith(
                    compareBy<MetaCandidateRef> { it.photonId.value }
                        .thenBy { it.sourceRevision }
                        .thenBy { it.fingerprint }
                )
        )
    }
}

class MetaCandidateIndex {
    private val snapshot = AtomicReference<List<MetaCandidateGroup>>(emptyList())

    fun replace(projections: Collection<MetaTheoryMemoryProjection>) {
        val groups = projections
            .groupBy { it.descriptor.equivalenceCandidateFingerprint }
            .mapNotNull { (equivalence, members) ->
                val candidates = members
                    .map { projection ->
                        MetaCandidateRef(
                            photonId = projection.projection.photon.id,
                            sourceRevision = projection.projection.photon.revision,
                            domain = projection.descriptor.domain,
                            observationFingerprint =
                                projection.descriptor.observationSignature.fingerprint,
                            comparisonFingerprint =
                                projection.descriptor.observationSignature.comparisonFingerprint,
                            stateFingerprint = projection.descriptor.stateFingerprint,
                        )
                    }
                    .distinctBy { it.fingerprint }
                    .sortedWith(
                        compareBy<MetaCandidateRef> { it.photonId.value }
                            .thenBy { it.sourceRevision }
                            .thenBy { it.fingerprint }
                    )
                if (candidates.size < 2) {
                    null
                } else {
                    MetaCandidateGroup(
                        equivalenceCandidateFingerprint = equivalence,
                        candidates = candidates,
                        fingerprint = StableFieldIds.fingerprint(
                            "meta-candidate-group/v1",
                            equivalence,
                            *candidates.map { it.fingerprint }.toTypedArray(),
                        ),
                    )
                }
            }
            .sortedBy { it.equivalenceCandidateFingerprint }
        snapshot.set(groups)
    }

    fun ambiguousGroups(): List<MetaCandidateGroup> = snapshot.get()
}

object MetaCandidateIndexRuntimeRegistry {
    private val current = AtomicReference<MetaCandidateIndex?>(null)

    fun install(index: MetaCandidateIndex) {
        current.set(index)
    }

    fun currentOrNull(): MetaCandidateIndex? = current.get()

    fun requireCurrent(): MetaCandidateIndex =
        requireNotNull(current.get()) { "Meta candidate index is not installed" }

    fun clearForTestOnly() {
        current.set(null)
    }
}
