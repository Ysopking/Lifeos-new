package app.lifeos.core.runtime.deepsearch

/**
 * Process-local source extension point for productive DeepSearch.
 *
 * The planner, mission/checkpoint state and source authorization remain owned by the existing
 * DeepSearch runtime. This registry contributes source implementations only; it carries no search
 * state, authority or persistence of its own.
 */
object DeepSearchExternalRuntimeRegistry {
    @Volatile
    private var installedSources: List<DeepSearchSource> = emptyList()

    fun install(sources: List<DeepSearchSource>) {
        require(sources.all { it.descriptor.kind == DeepSearchSourceKind.EXTERNAL }) {
            "DeepSearch external runtime accepts EXTERNAL sources only"
        }
        require(sources.map { it.descriptor.sourceId }.distinct().size == sources.size) {
            "DeepSearch external source ids must be unique"
        }
        installedSources = sources.sortedBy { it.descriptor.sourceId }
    }

    fun sources(): List<DeepSearchSource> = installedSources

    internal fun clearForTests() {
        installedSources = emptyList()
    }
}

/**
 * Process-local indirection for the authoritative external-source permission decision.
 * Production installs an Owner-Policy-backed gate before the kernel is composed. The registry does
 * not retain a permission bit; every call delegates to the current authoritative gate.
 */
object DeepSearchPermissionRuntimeRegistry {
    @Volatile
    private var installedGate: DeepSearchPermissionGate? = null

    fun install(gate: DeepSearchPermissionGate) {
        installedGate = gate
    }

    fun currentOrNull(): DeepSearchPermissionGate? = installedGate

    internal fun clearForTests() {
        installedGate = null
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
