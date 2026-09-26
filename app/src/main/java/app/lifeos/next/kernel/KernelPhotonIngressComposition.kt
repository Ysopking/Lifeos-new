package app.lifeos.next.kernel

import app.lifeos.core.model.Photon
import app.lifeos.core.model.RevisionedPhotonRepository
import app.lifeos.core.runtime.cognition.ContinuousCognitionEngine

/**
 * Keeps production ingress wiring out of the kernel owner.
 *
 * Production always installs DurableCognitionReconciler plus terminal recovery observation.
 * Therefore an accepted Photon without immediate TaskStore capacity is a durable backpressure
 * deferral and not lost cognition work.
 */
internal object KernelPhotonIngressComposition {
    fun create(
        photonStore: RevisionedPhotonRepository,
        continuousCognition: ContinuousCognitionEngine,
        onPhotonPersisted: (Photon) -> Unit,
    ): PhotonIngressCoordinator = PhotonIngressCoordinator(
        photonStore = photonStore,
        liveSubmissionBudget = LifeOsKernelDefaults.LIVE_SUBMISSION_BUDGET,
        submitCognition = continuousCognition::submit,
        onPhotonPersisted = onPhotonPersisted,
        durableDeferralSupported = true,
    )
}
