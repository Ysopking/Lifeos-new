package app.lifeos.next.kernel

import app.lifeos.core.model.Photon

/**
 * Process bridge that sends automation/evolution output back through the productive kernel ingestion
 * path. It is installed immediately after kernel construction; pre-kernel persistence may still use
 * the caller's encrypted fallback store.
 */
object LifeOsAutomationPhotonBridge {
    @Volatile
    private var ingestor: (suspend (Photon) -> Photon)? = null

    fun install(value: suspend (Photon) -> Photon) {
        ingestor = value
    }

    suspend fun ingestIfInstalled(photon: Photon): Photon? = ingestor?.invoke(photon)
}
