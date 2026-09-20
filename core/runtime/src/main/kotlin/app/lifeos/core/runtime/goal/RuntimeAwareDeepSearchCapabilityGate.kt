package app.lifeos.core.runtime.goal

import app.lifeos.core.runtime.capability.GeneratedToolRuntimeProcessRegistry
import app.lifeos.core.runtime.deepsearch.CapabilityRegistryDeepSearchGate
import app.lifeos.core.runtime.deepsearch.DeepSearchCapabilityGate
import app.lifeos.core.runtime.deepsearch.DeepSearchSourceAuthorization
import app.lifeos.core.runtime.deepsearch.DeepSearchSourceDescriptor
import app.lifeos.core.runtime.deepsearch.DeepSearchSourceKind
import app.lifeos.core.runtime.deepsearch.RuntimeDeepSearchPermissionGate

/** Narrow bridge from runtime capability ownership into the isolated DeepSearch module. */
object RuntimeAwareDeepSearchCapabilityGate : DeepSearchCapabilityGate {
    override suspend fun authorize(source: DeepSearchSourceDescriptor): DeepSearchSourceAuthorization {
        if (source.kind == DeepSearchSourceKind.LOCAL) {
            return DeepSearchSourceAuthorization(true, "local-source")
        }
        val capabilities = GeneratedToolRuntimeProcessRegistry.capabilities()
            ?: return DeepSearchSourceAuthorization(false, "productive-capability-registry-unavailable")
        return CapabilityRegistryDeepSearchGate(
            registry = capabilities,
            permissionGate = RuntimeDeepSearchPermissionGate,
        ).authorize(source)
    }
}
