package app.lifeos.next.kernel

import app.lifeos.core.model.Photon
import app.lifeos.core.runtime.PhotonIngressMode
import app.lifeos.core.runtime.artifact.ArtifactCoordinator
import app.lifeos.core.runtime.artifact.ArtifactGenerationCoordinator
import app.lifeos.core.runtime.artifact.OwnerAssetReviewCoordinator
import app.lifeos.core.runtime.artifact.OwnerAssetReviewRepository
import app.lifeos.core.runtime.livedata.LiveDataHub
import app.lifeos.core.runtime.livedata.LiveDataPhotonIngress
import app.lifeos.core.runtime.life.PerceptionFusionEngine
import app.lifeos.core.runtime.policy.OwnerObservationDecision
import app.lifeos.core.runtime.policy.OwnerObservationPolicyLedger
import app.lifeos.core.runtime.policy.OwnerObservationRequest
import app.lifeos.core.runtime.policy.OwnerObservationType

/**
 * Single productive Android ingress for Photons that must become immediately visible to the live
 * kernel and its durable ContinuousCognition pipeline.
 *
 * DERIVED/REPLAY classification is persisted before task submission. The causal task observer can
 * therefore suppress a duplicate root pass even if the process dies after durabilization.
 *
 * A Photon that is already persisted/live is deliberately submitted again with the same canonical
 * revision identity. The durable cognition/task layer owns cross-process idempotency, so this closes
 * the crash window between Photon persistence/live exposure and durable cognitive submission.
 */
class CanonicalPhotonIngress(
    private val kernel: LifeOsKernel,
    ownerAssetReviews: OwnerAssetReviewRepository? = null,
    private val ownerObservationPolicy: OwnerObservationPolicyLedger? = null,
) {
    private val informationObservationFusion = PerceptionFusionEngine()
    private val artifactIngress by lazy {
        CanonicalArtifactPhotonIngress(::ingestWithReceipt)
    }

    /** Productive collaborative-artifact runtime bound to the same canonical Photon ingress. */
    val artifacts: ArtifactCoordinator by lazy {
        ArtifactCoordinator(
            photons = kernel.photonStore,
            ingress = artifactIngress,
        )
    }

    /**
     * Productive M01 live-data hub. Connector/account state and external deltas use the same
     * revisioned Photon store and canonical cognition ingress as every other Origin Photon.
     */
    val liveData: LiveDataHub by lazy {
        LiveDataHub(
            photons = kernel.photonStore,
            ingress = LiveDataPhotonIngress { photon ->
                ingest(photon, PhotonIngressMode.ORIGIN)
            },
        )
    }

    /**
     * Productive document/code/image generation provenance for callers that intentionally publish
     * immediately. Owner-reviewed image generation uses a separate staging coordinator instead.
     */
    val artifactGeneration: ArtifactGenerationCoordinator by lazy {
        ArtifactGenerationCoordinator(
            artifacts = artifacts,
            ingress = artifactIngress,
        )
    }

    /** Present only when product composition supplied the encrypted owner-review vault. */
    val ownerAssetReview: OwnerAssetReviewCoordinator? = ownerAssetReviews?.let { repository ->
        OwnerAssetReviewCoordinator(
            reviews = repository,
            photons = kernel.photonStore,
            ingress = artifactIngress,
            authorizedOwnerActorId = PrivateOwnerPolicyBaseline.ownerActorId.value,
            onApproved = GeneratedToolOwnerReviewRuntime::applyApproved,
        )
    }

    init {
        LiveDataHubRuntimeRegistry.install(liveData)
        ownerAssetReview?.let { reviews ->
            OwnerAssetReviewRuntimeRegistry.install(reviews)
            ImageArtifactLifecycleRuntimeRegistry.install(
                photons = kernel.photonStore,
                reviews = reviews,
            )
        }
        WebDeepSearchRuntime.installSource(
            loadPhoton = kernel.photonStore::load,
            persistPhoton = { photon ->
                // Web findings/cache entries are derived evidence produced by DeepSearch, never
                // owner-origin input. Keep causal ingress classification exact.
                ingest(photon, PhotonIngressMode.DERIVED)
                photon
            },
        )
        LiveNotificationPhotonIngress.install { observation ->
            val policy = ownerObservationPolicy
            val authorized = if (policy == null) {
                null
            } else {
                when (
                    val decision = policy.evaluate(
                        OwnerObservationRequest(
                            actorId = PrivateOwnerPolicyBaseline.ownerActorId,
                            observationType = OwnerObservationType.NOTIFICATION,
                            resource = observation.sourceResource,
                            scope = PrivateOwnerObservationPolicyBaseline.NOTIFICATION_SCOPE,
                            sensorId = observation.sourceId,
                        )
                    )
                ) {
                    is OwnerObservationDecision.Allowed ->
                        observation.authorizedBy(decision.grantId.value)
                    is OwnerObservationDecision.Blocked ->
                        null
                }
            }

            if (authorized != null) {
                val photon = informationObservationFusion
                    .fuse(
                        listOf(
                            authorized.toPerceptionSignal(
                                salience = 0.6,
                            )
                        )
                    )
                    .photons
                    .single()
                ingest(photon, PhotonIngressMode.ORIGIN)
            }
        }
    }

    suspend fun ingest(
        photon: Photon,
        mode: PhotonIngressMode = PhotonIngressMode.ORIGIN,
    ) {
        ingestWithReceipt(photon, mode)
    }

    /**
     * Same canonical ingress semantics as [ingest], while returning the kernel submission receipt to
     * callers whose contract must retain the productive Photon result.
     */
    suspend fun ingestWithReceipt(
        photon: Photon,
        mode: PhotonIngressMode = PhotonIngressMode.ORIGIN,
    ): PhotonSubmissionResult {
        val live = kernel.bootstrapState.value.photons.firstOrNull { it.id == photon.id }
        if (live != null) {
            check(live.revision <= photon.revision) {
                "Refusing stale live Photon ingress for ${photon.id.value}"
            }
            if (live.revision == photon.revision) {
                check(live == photon) {
                    "Conflicting live Photon state for ${photon.id.value}@${photon.revision}"
                }
            }
        }

        val submission = kernel.persistAndIngest(photon, mode)
        check(submission.processingQueued) {
            submission.processingFailure ?: "Photon cognitive work was not durabilized"
        }
        return submission
    }
}
