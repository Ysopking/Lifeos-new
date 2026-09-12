package app.lifeos.next.kernel

import app.lifeos.core.model.Photon
import app.lifeos.core.runtime.PhotonIngressMarkerStore
import app.lifeos.core.runtime.PhotonIngressMode

/**
 * Single productive Android ingress for Photons that must become immediately visible to the live
 * kernel and its durable ContinuousCognition pipeline.
 *
 * DERIVED/REPLAY classification is persisted before task submission. The causal task observer can
 * therefore suppress a duplicate root pass even if the process dies after durabilization.
 */
class CanonicalPhotonIngress(
    private val kernel: LifeOsKernel,
) {
    suspend fun ingest(
        photon: Photon,
        mode: PhotonIngressMode = PhotonIngressMode.ORIGIN,
    ) {
        val persisted = kernel.photonStore.load(photon.id)
        if (persisted != null) {
            check(persisted.revision <= photon.revision) {
                "Refusing stale Photon ingress for ${photon.id.value}: " +
                    "${photon.revision} < ${persisted.revision}"
            }
            if (persisted.revision == photon.revision) {
                check(persisted == photon) {
                    "Conflicting Photon ingress state for ${photon.id.value}@${photon.revision}"
                }
            }
        }

        val durableMode = PhotonIngressMarkerStore.mode(kernel.photonStore, photon)
        when (mode) {
            PhotonIngressMode.ORIGIN -> check(durableMode == PhotonIngressMode.ORIGIN) {
                "Photon ${photon.id.value}@${photon.revision} is already classified as $durableMode"
            }
            PhotonIngressMode.DERIVED,
            PhotonIngressMode.REPLAY -> {
                if (durableMode == PhotonIngressMode.ORIGIN) {
                    PhotonIngressMarkerStore.mark(kernel.photonStore, photon, mode)
                } else {
                    check(durableMode == mode) {
                        "Photon ${photon.id.value}@${photon.revision} cannot change ingress mode " +
                            "from $durableMode to $mode"
                    }
                }
            }
        }

        val live = kernel.bootstrapState.value.photons.firstOrNull { it.id == photon.id }
        if (live != null) {
            check(live.revision <= photon.revision) {
                "Refusing stale live Photon ingress for ${photon.id.value}"
            }
            if (live.revision == photon.revision) {
                check(live == photon) {
                    "Conflicting live Photon state for ${photon.id.value}@${photon.revision}"
                }
                return
            }
        }

        val submission = kernel.persistAndIngest(photon)
        check(submission.processingQueued) {
            submission.processingFailure ?: "Photon cognitive work was not durabilized"
        }
    }
}
