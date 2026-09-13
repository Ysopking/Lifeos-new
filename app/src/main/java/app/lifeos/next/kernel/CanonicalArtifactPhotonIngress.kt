package app.lifeos.next.kernel

import app.lifeos.core.model.Photon
import app.lifeos.core.runtime.PhotonIngressMode
import app.lifeos.core.runtime.artifact.ArtifactPhotonIngress
import app.lifeos.core.runtime.artifact.ArtifactReentryReceipt

/**
 * Productive Android binding for collaborative artifacts. Finalized artifacts are always DERIVED:
 * they must enter the common durable Photon lifecycle without seeding a second causal root.
 */
class CanonicalArtifactPhotonIngress(
    private val submit: suspend (Photon, PhotonIngressMode) -> PhotonSubmissionResult,
) : ArtifactPhotonIngress {
    override suspend fun ingest(photon: Photon): ArtifactReentryReceipt {
        val receipt = submit(photon, PhotonIngressMode.DERIVED)
        return ArtifactReentryReceipt(
            accepted = receipt.processingQueued,
            durableTaskId = null,
        )
    }
}
