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
