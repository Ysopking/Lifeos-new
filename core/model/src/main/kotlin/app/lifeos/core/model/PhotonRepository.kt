package app.lifeos.core.model

data class PhotonLoadReport(
    val photons: List<Photon>,
    val unreadableFiles: List<String>,
)

/** Persistence contract used above the Android-specific encrypted store. */
interface PhotonRepository : PhotonStore {
    suspend fun load(id: PhotonId): Photon?
    suspend fun loadReport(): PhotonLoadReport
}

/**
 * Revision-aware extension. Existing PhotonRepository callers remain source-compatible while new
 * ingress/recovery code can use exact revisions and durable index queries.
 */
interface RevisionedPhotonRepository : PhotonRepository, PhotonIndexReader {
    suspend fun load(ref: PhotonRevisionRef): Photon?
    suspend fun latestRef(id: PhotonId): PhotonRevisionRef?
    suspend fun saveRevision(
        photon: Photon,
        expectedPreviousRevision: Long?,
    ): PhotonRevisionWriteResult
}
