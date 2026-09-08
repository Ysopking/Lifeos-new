package app.lifeos.core.runtime.boot

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId

enum class BootContextResolutionStatus {
    NONE,
    RESOLVED,
    UNRESOLVED,
}

data class RehydratedBootContext(
    val kind: BootContextKind,
    val contextId: String,
    val sourcePhotonId: PhotonId,
    val sourcePhotonRevision: Long,
    val sourceCreatedAt: java.time.Instant,
    val relevantPhotonIds: List<PhotonId>,
) {
    init {
        require(contextId.isNotBlank()) { "Rehydrated context id must not be blank" }
        require(sourcePhotonRevision > 0) { "Rehydrated context source revision must be positive" }
        require(relevantPhotonIds == relevantPhotonIds.distinct()) {
            "Relevant Photon ids must be unique"
        }
    }
}

data class BootContextResolution(
    val status: BootContextResolutionStatus,
    val candidates: List<RehydratedBootContext>,
) {
    init {
        require(candidates == candidates.sortedWith(rehydratedContextComparator))
        when (status) {
            BootContextResolutionStatus.NONE -> require(candidates.isEmpty())
            BootContextResolutionStatus.RESOLVED -> require(candidates.size == 1)
            BootContextResolutionStatus.UNRESOLVED -> require(candidates.size > 1)
        }
    }

    val resolved: RehydratedBootContext?
        get() = candidates.singleOrNull()
}

data class BootContextRehydrationResult(
    val generationId: BootGenerationId,
    val contexts: List<RehydratedBootContext>,
    val missingSourcePhotonIds: List<PhotonId>,
) {
    init {
        require(contexts == contexts.sortedWith(rehydratedContextComparator))
        require(missingSourcePhotonIds == missingSourcePhotonIds.distinct().sortedBy { it.value })
    }

    fun resolve(kind: BootContextKind? = null): BootContextResolution {
        val eligible = contexts.filter { kind == null || it.kind == kind }
        if (eligible.isEmpty()) {
            return BootContextResolution(BootContextResolutionStatus.NONE, emptyList())
        }

        val newestTimestamp = eligible.maxOf { it.sourceCreatedAt }
        val newest = eligible.filter { it.sourceCreatedAt == newestTimestamp }
            .sortedWith(rehydratedContextComparator)
        return if (newest.size == 1) {
            BootContextResolution(BootContextResolutionStatus.RESOLVED, newest)
        } else {
            BootContextResolution(BootContextResolutionStatus.UNRESOLVED, newest)
        }
    }
}

/**
 * Rebuilds active conversation/project/goal context strictly from the durable boot snapshot.
 * It never mutates source Photons and never invents a winner when equally recent contexts exist.
 */
class BootContextRehydrator(
    private val maxRelevantPhotonsPerContext: Int = 24,
) {
    init {
        require(maxRelevantPhotonsPerContext in 1..256)
    }

    fun rehydrate(snapshot: DurableBootSnapshot): BootContextRehydrationResult {
        val photonsById = snapshot.photons.groupBy { it.id }
        val missing = mutableListOf<PhotonId>()

        val activeContexts = snapshot.contexts
            .filter { it.explicitlyActive }
            .groupBy { it.kind to it.contextId }
            .mapNotNull { (_, projections) ->
                val selected = projections.maxWithOrNull(
                    compareBy<BootContextProjection>(
                        { it.sourceCreatedAt },
                        { it.sourcePhotonRevision },
                        { it.sourcePhotonId.value },
                    )
                ) ?: return@mapNotNull null

                val sourcePhoton = photonsById[selected.sourcePhotonId]
                    ?.firstOrNull { it.revision == selected.sourcePhotonRevision }
                if (sourcePhoton == null) {
                    missing += selected.sourcePhotonId
                    return@mapNotNull null
                }

                RehydratedBootContext(
                    kind = selected.kind,
                    contextId = selected.contextId,
                    sourcePhotonId = selected.sourcePhotonId,
                    sourcePhotonRevision = selected.sourcePhotonRevision,
                    sourceCreatedAt = selected.sourceCreatedAt,
                    relevantPhotonIds = relevantPhotons(snapshot.photons, selected, sourcePhoton)
                        .take(maxRelevantPhotonsPerContext)
                        .map { it.id },
                )
            }
            .sortedWith(rehydratedContextComparator)

        return BootContextRehydrationResult(
            generationId = snapshot.generationId,
            contexts = activeContexts,
            missingSourcePhotonIds = missing.distinct().sortedBy { it.value },
        )
    }

    private fun relevantPhotons(
        photons: List<Photon>,
        context: BootContextProjection,
        source: Photon,
    ): List<Photon> {
        val contextTag = "context:${context.kind.name.lowercase()}:${context.contextId}"
        return photons.filter { photon ->
            photon.id == source.id ||
                contextTag in photon.tags ||
                source.id in photon.provenance.parentIds ||
                photon.id in source.provenance.parentIds ||
                photon.relations.any { it.target == source.id } ||
                source.relations.any { it.target == photon.id }
        }.groupBy { it.id }
            .mapNotNull { (_, revisions) ->
                revisions.maxWithOrNull(
                    compareBy<Photon>({ it.revision }, { it.provenance.createdAt })
                )
            }
            .sortedWith(
                compareByDescending<Photon> { it.provenance.createdAt }
                    .thenByDescending { it.revision }
                    .thenBy { it.id.value }
            )
    }
}

internal val rehydratedContextComparator = compareBy<RehydratedBootContext>(
    { it.kind.name },
    { it.contextId },
    { it.sourceCreatedAt },
    { it.sourcePhotonId.value },
    { it.sourcePhotonRevision },
)
