package app.lifeos.core.runtime.extension

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonIndexOrder
import app.lifeos.core.model.PhotonIndexQuery
import app.lifeos.core.model.PhotonPhase
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.RevisionedPhotonRepository

data class ExtensionPhotonQuery(
    val ids: Set<PhotonId> = emptySet(),
    val phases: Set<PhotonPhase> = emptySet(),
    val mimeTypes: Set<String> = emptySet(),
    val allTags: Set<String> = emptySet(),
    val latestOnly: Boolean = true,
    val order: PhotonIndexOrder = PhotonIndexOrder.IDENTITY,
    val limit: Int = DEFAULT_LIMIT,
) {
    init {
        require(limit in 1..MAX_LIMIT) {
            "Extension Photon query limit must be between 1 and $MAX_LIMIT"
        }
        require(mimeTypes.none { it.isBlank() }) { "Extension Photon mime types must not be blank" }
        require(allTags.none { it.isBlank() }) { "Extension Photon tags must not be blank" }
    }

    internal fun toIndexQuery(): PhotonIndexQuery = PhotonIndexQuery(
        ids = ids,
        phases = phases,
        mimeTypes = mimeTypes,
        allTags = allTags,
        latestOnly = latestOnly,
        includeTombstoned = false,
        order = order,
        limit = limit,
    )

    companion object {
        const val DEFAULT_LIMIT: Int = 64
        const val MAX_LIMIT: Int = 256
    }
}

data class ExtensionPhotonQueryResult(
    val refs: List<PhotonRevisionRef>,
    val photons: List<Photon>,
) {
    init {
        require(refs.size == photons.size)
        refs.zip(photons).forEach { (ref, photon) ->
            require(photon.id == ref.photonId && photon.revision == ref.revision) {
                "Extension Photon query result must preserve exact revision identity"
            }
        }
    }
}

/**
 * B150 bounded read facade for extensions.
 *
 * It delegates discovery to the durable Photon index, then loads only the exact returned revisions.
 * It intentionally exposes no loadAll/full-vault operation and never returns tombstoned heads.
 */
class ExtensionPhotonReader(
    private val photons: RevisionedPhotonRepository,
) {
    suspend fun query(query: ExtensionPhotonQuery): ExtensionPhotonQueryResult {
        val refs = photons.query(query.toIndexQuery())
        require(refs.size <= query.limit) {
            "Photon index returned more refs than the bounded extension query requested"
        }

        val loaded = refs.map { ref ->
            val photon = requireNotNull(photons.load(ref)) {
                "Indexed Photon revision disappeared before exact extension load"
            }
            require(photon.id == ref.photonId && photon.revision == ref.revision) {
                "Photon repository returned a different revision than requested"
            }
            photon
        }

        return ExtensionPhotonQueryResult(
            refs = refs,
            photons = loaded,
        )
    }
}
