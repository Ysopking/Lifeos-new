package app.lifeos.next.kernel

import app.lifeos.core.runtime.android.HardwareSensorBridgeAdapter
import app.lifeos.core.runtime.android.HardwareSensorObservationRuntime
import app.lifeos.core.runtime.android.HardwareSensorSource
import app.lifeos.core.runtime.life.AppSensorRegistry
import app.lifeos.core.runtime.life.OwnerAuthorizedAppObservationIngress
import app.lifeos.core.runtime.policy.OwnerActorId
import app.lifeos.core.runtime.policy.OwnerObservationPolicyLedger

/**
 * Product composition for B468. This factory does not grant observation permission and does not
 * activate hardware. It only binds an already-configured physical source to the B467 canonical
 * authorized-observation Photon ingress.
 */
class ProductiveHardwareSensorRuntimeFactory(
    private val registry: AppSensorRegistry,
    private val observationPolicy: OwnerObservationPolicyLedger,
    private val scope: String,
    private val actorId: OwnerActorId = PrivateOwnerPolicyBaseline.ownerActorId,
) {
    init {
        require(scope.isNotBlank())
    }

    fun create(
        source: HardwareSensorSource,
    ): HardwareSensorObservationRuntime =
        HardwareSensorObservationRuntime(
            adapter = HardwareSensorBridgeAdapter(source),
            registry = registry,
            authorization = OwnerAuthorizedAppObservationIngress(
                observationPolicy = observationPolicy,
                actorId = actorId,
                scope = scope,
            ),
            commitAuthorized = { observation ->
                AuthorizedObservationPhotonIngress.ingest(observation)
                Unit
            },
        )
}
