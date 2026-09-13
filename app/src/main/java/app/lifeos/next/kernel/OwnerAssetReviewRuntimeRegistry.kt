package app.lifeos.next.kernel

import app.lifeos.core.runtime.artifact.OwnerAssetReviewCoordinator

/** Process-local bridge for producers constructed before CanonicalPhotonIngress installs the review runtime. */
internal object OwnerAssetReviewRuntimeRegistry {
    @Volatile
    private var coordinator: OwnerAssetReviewCoordinator? = null

    fun install(value: OwnerAssetReviewCoordinator) {
        coordinator = value
    }

    fun currentOrNull(): OwnerAssetReviewCoordinator? = coordinator
}
