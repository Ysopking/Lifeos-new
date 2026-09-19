package app.lifeos.core.runtime.deepsearch

import app.lifeos.core.runtime.capability.GeneratedToolRuntimeProcessRegistry

/**
 * Process-local source extension point for productive DeepSearch.
 *
 * The planner, mission/checkpoint state and source authorization remain owned by the existing
 * DeepSearch runtime. This registry contributes source implementations only; it carries no search
 * state, authority or persistence of its own.
 */
object DeepSearchExternalRuntimeRegistry {
    private val slot =
        app.lifeos.core.runtime.process.NonOwningRuntimeSlot<List<DeepSearchSource>>(
            "DeepSearch external sources"
        )

    fun install(sources: List<DeepSearchSource>) {
        require(sources.all { it.descriptor.kind == DeepSearchSourceKind.EXTERNAL }) {
            "DeepSearch external runtime accepts EXTERNAL sources only"
        }
        require(sources.map { it.descriptor.sourceId }.distinct().size == sources.size) {
            "DeepSearch external source ids must be unique"
        }
        slot.install(sources.sortedBy { it.descriptor.sourceId })
    }

    fun sources(): List<DeepSearchSource> = slot.currentOrNull().orEmpty()

    internal fun clearForTests() {
        slot.clear()
    }
}

/**
 * Process-local indirection for the authoritative external-source permission decision.
 * Production installs an Owner-Policy-backed gate before the kernel is composed. The registry does
 * not retain a permission bit; every call delegates to the current authoritative gate.
 */
object DeepSearchPermissionRuntimeRegistry {
    private val slot =
        app.lifeos.core.runtime.process.NonOwningRuntimeSlot<DeepSearchPermissionGate>(
            "DeepSearch permission gate"
        )

    fun install(gate: DeepSearchPermissionGate) {
        slot.install(gate)
    }

    fun currentOrNull(): DeepSearchPermissionGate? = slot.currentOrNull()

    internal fun clearForTests() {
        slot.clear()
    }
}

object RuntimeDeepSearchPermissionGate : DeepSearchPermissionGate {
    override suspend fun permissionFor(source: DeepSearchSourceDescriptor): DeepSearchPermissionState {
        if (source.kind == DeepSearchSourceKind.LOCAL) return DeepSearchPermissionState.NOT_REQUIRED
        return DeepSearchPermissionRuntimeRegistry.currentOrNull()
            ?.permissionFor(source)
            ?: DeepSearchPermissionState.DENIED
    }
}

/**
 * Productive external admission reuses the single process CapabilityRegistry plus the dynamic
 * Owner-Policy permission view. Absence of either authority fails closed.
 */
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
