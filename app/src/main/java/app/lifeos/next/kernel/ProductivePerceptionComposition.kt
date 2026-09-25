package app.lifeos.next.kernel

import android.content.Context
import app.lifeos.core.data.artifact.EncryptedOwnerAssetReviewRepository
import app.lifeos.core.runtime.life.OwnerObservationPolicyLedger

internal data class ProductivePerceptionBinding(
    val contextRuntime: ProductivePerceptionContextRuntime,
    val photonIngress: CanonicalPhotonIngress,
    val hardwareSensorBridge: AndroidHardwareSensorBridge,
)

/**
 * B499 keeps ProductivePerceptionContextRuntime composition out of ProcessRuntimeInstaller.
 *
 * The composition preserves the existing authority split:
 * sensor registration != observation permission != effect authority.
 */
internal object ProductivePerceptionComposition {
    fun prepare(
        ownerObservationPolicy: OwnerObservationPolicyLedger,
    ): ProductivePerceptionContextRuntime =
        ProductivePerceptionContextRuntime(ownerObservationPolicy).also {
            ProductiveWorldGapAttentionRuntimeRegistry.install(it)
        }

    suspend fun bind(
        context: Context,
        kernel: LifeOsKernel,
        ownerObservationPolicy: OwnerObservationPolicyLedger,
        perceptionContext: ProductivePerceptionContextRuntime,
    ): ProductivePerceptionBinding {
        val appContext = context.applicationContext
        val photonIngress = CanonicalPhotonIngress(
            kernel = kernel,
            ownerAssetReviews = EncryptedOwnerAssetReviewRepository(appContext),
            ownerObservationPolicy = ownerObservationPolicy,
        )
        val hardwareSensorBridge = AndroidHardwareSensorBridge(
            context = appContext,
            photonIngress = photonIngress,
        )
        perceptionContext.attachHardwareBridge(hardwareSensorBridge)
        perceptionContext.attachNotificationBridge(
            LiveNotificationSensorBridge(photonIngress)
        )

        val appUsageSensorBridge =
            AndroidAppUsageSensorBridge(appContext, photonIngress)
        perceptionContext.attachAppUsageBridge(appUsageSensorBridge)
        ProductiveAppUsageSensorRuntimeRegistry.install(appUsageSensorBridge)

        perceptionContext.attachAppContentBridge(
            AndroidSemanticAppContentSensorBridge(photonIngress)
        )

        return ProductivePerceptionBinding(
            contextRuntime = perceptionContext,
            photonIngress = photonIngress,
            hardwareSensorBridge = hardwareSensorBridge,
        )
    }
}
