package app.lifeos.core.runtime.capability

/** Read-only provider view for isolated runtime modules. */
interface CapabilityProviderCatalog {
    suspend fun providersFor(
        capabilityId: CapabilityId,
        includeUnavailable: Boolean = false,
    ): List<CapabilityDescriptor>
}
