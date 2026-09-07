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
