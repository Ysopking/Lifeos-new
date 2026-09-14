package app.lifeos.core.runtime.deepsearch

object DeepSearchExternalRuntimeRegistry {
    @Volatile
    private var installedSources: List<DeepSearchSource> = emptyList()

    @Volatile
    private var installedGate: DeepSearchCapabilityGate? = null

    fun install(sources: List<DeepSearchSource>, capabilityGate: DeepSearchCapabilityGate) {
        require(sources.isNotEmpty())
        require(sources.all { it.descriptor.kind == DeepSearchSourceKind.EXTERNAL })
        require(sources.map { it.descriptor.sourceId }.distinct().size == sources.size)
        installedSources = sources.sortedBy { it.descriptor.sourceId }
        installedGate = capabilityGate
    }

    fun sources(): List<DeepSearchSource> = installedSources

    internal fun gateOrNull(): DeepSearchCapabilityGate? = installedGate

    internal fun clearForTests() {
        installedSources = emptyList()
        installedGate = null
    }
}

object RuntimeAwareDeepSearchCapabilityGate : DeepSearchCapabilityGate {
    override suspend fun authorize(source: DeepSearchSourceDescriptor): DeepSearchSourceAuthorization {
        if (source.kind == DeepSearchSourceKind.LOCAL) {
            return DeepSearchSourceAuthorization(true, "local-source")
        }
        val gate = DeepSearchExternalRuntimeRegistry.gateOrNull()
            ?: return DeepSearchSourceAuthorization(false, "external-runtime-not-installed")
        return gate.authorize(source)
    }
}
