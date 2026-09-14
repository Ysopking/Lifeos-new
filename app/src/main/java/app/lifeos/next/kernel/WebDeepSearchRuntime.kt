package app.lifeos.next.kernel

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.runtime.deepsearch.DeepSearchExternalRuntimeRegistry
import app.lifeos.core.runtime.deepsearch.DeepSearchPermissionRuntimeRegistry
import app.lifeos.core.runtime.policy.OwnerPolicyLedger

/**
 * Product composition bridge only. Authority stays in OwnerPolicyLedger and the shared capability
 * registry; search state stays in the existing V12 mission/checkpoint runtime.
 */
internal object WebDeepSearchRuntime {
    @Volatile
    private var ownerPolicy: OwnerPolicyLedger? = null

    fun installPolicy(policy: OwnerPolicyLedger) {
        ownerPolicy = policy
        DeepSearchPermissionRuntimeRegistry.install(OwnerPolicyDeepSearchPermissionGate(policy))
    }

    fun installSource(
        loadPhoton: suspend (PhotonId) -> Photon?,
        persistPhoton: suspend (Photon) -> Photon,
    ) {
        val policy = ownerPolicy ?: run {
            DeepSearchExternalRuntimeRegistry.install(emptyList())
            return
        }
        DeepSearchExternalRuntimeRegistry.install(
            listOf(
                AndroidWebDeepSearchSource(
                    ownerPolicy = policy,
                    transport = DuckDuckGoHtmlSearchTransport(),
                    loadPhoton = loadPhoton,
                    persistPhoton = persistPhoton,
                )
            )
        )
    }
}
