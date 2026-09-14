package app.lifeos.next.kernel

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.runtime.PhotonIngressMarkerStore
import app.lifeos.core.runtime.PhotonIngressMode

/**
 * Single fail-closed ingress-mode classifier used immediately before productive kernel submission.
 * ORIGIN stays marker-free; DERIVED and REPLAY are durably marked before the Photon is persisted and
 * submitted so a crash cannot later reinterpret internal/replayed work as a causal root.
 */
internal object ProductivePhotonIngressClassification {
    suspend fun requireOrMark(
        photons: PhotonRepository,
        photon: Photon,
        mode: PhotonIngressMode,
    ) {
        val durableMode = PhotonIngressMarkerStore.mode(photons, photon)
        when (mode) {
            PhotonIngressMode.ORIGIN -> check(durableMode == PhotonIngressMode.ORIGIN) {
                "Photon ${photon.id.value}@${photon.revision} is already classified as $durableMode"
            }

            PhotonIngressMode.DERIVED,
            PhotonIngressMode.REPLAY -> {
                if (durableMode == PhotonIngressMode.ORIGIN) {
                    PhotonIngressMarkerStore.mark(photons, photon, mode)
                } else {
                    check(durableMode == mode) {
                        "Photon ${photon.id.value}@${photon.revision} cannot change ingress mode " +
                            "from $durableMode to $mode"
                    }
                }
            }
        }
    }
}
