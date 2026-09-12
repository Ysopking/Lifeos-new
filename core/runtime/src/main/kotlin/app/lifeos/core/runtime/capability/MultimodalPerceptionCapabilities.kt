package app.lifeos.core.runtime.capability

object MultimodalPerceptionCapabilities {
    const val SPEECH_FIELD = "speech.recognize.field"
    const val WRITING_FIELD = "writing.recognize.field"
    const val VISUAL_OBSERVATION = "visual.observe.typed"
    const val VISUAL_WRITING = "writing.observe.visual"

    val LOCAL_FIELD_PROVIDERS: List<CapabilityDescriptor> = listOf(
        CapabilityDescriptor(
            capabilityId = CapabilityId(SPEECH_FIELD),
            providerId = "lifeos-speech-field",
            providerType = ProviderType.MODULE,
            contract = CapabilityContract(
                requiredInputs = setOf("typed-speech-observation"),
                outputs = setOf("semantic-user-photon"),
            ),
            state = ProviderState.ACTIVE,
            trustLevel = TrustLevel.SYSTEM,
            reliability = 1.0,
            cost = 0.0,
        ),
        CapabilityDescriptor(
            capabilityId = CapabilityId(WRITING_FIELD),
            providerId = "lifeos-writing-field",
            providerType = ProviderType.MODULE,
            contract = CapabilityContract(
                requiredInputs = setOf("typed-grapheme-observation"),
                outputs = setOf("semantic-user-photon"),
            ),
            state = ProviderState.ACTIVE,
            trustLevel = TrustLevel.SYSTEM,
            reliability = 1.0,
            cost = 0.0,
        ),
    )
}

enum class VisualPerceptionAdapterState {
    READY,
    DEGRADED,
    QUARANTINED,
    STOPPED,
}

data class VisualPerceptionAdapterHealth(
    val state: VisualPerceptionAdapterState,
    val reliability: Double,
    val detail: String? = null,
) {
    init {
        require(reliability.isFinite() && reliability in 0.0..1.0)
        require(detail == null || detail.isNotBlank())
    }
}

/** A concrete adapter object must exist before visual capability registration is even possible. */
interface VisualPerceptionAdapterHealthSource {
    val providerId: String
    suspend fun health(): VisualPerceptionAdapterHealth
}

class MultimodalPerceptionCapabilityInstaller(
    private val registry: CapabilityRegistry,
) {
    suspend fun installLocalFields() {
        MultimodalPerceptionCapabilities.LOCAL_FIELD_PROVIDERS.forEach { registry.register(it) }
    }

    suspend fun installVisualAdapter(
        adapter: VisualPerceptionAdapterHealthSource,
        capabilityId: CapabilityId = CapabilityId(MultimodalPerceptionCapabilities.VISUAL_OBSERVATION),
        requiredInputs: Set<String> = setOf("image-asset-photon"),
        outputs: Set<String> = setOf("typed-visual-observation"),
    ): CapabilityDescriptor {
        require(adapter.providerId.isNotBlank())
        val health = adapter.health()
        val descriptor = CapabilityDescriptor(
            capabilityId = capabilityId,
            providerId = adapter.providerId,
            providerType = ProviderType.MODULE,
            contract = CapabilityContract(requiredInputs = requiredInputs, outputs = outputs),
            state = when (health.state) {
                VisualPerceptionAdapterState.READY -> ProviderState.ACTIVE
                VisualPerceptionAdapterState.DEGRADED -> ProviderState.DEGRADED
                VisualPerceptionAdapterState.QUARANTINED -> ProviderState.QUARANTINED
                VisualPerceptionAdapterState.STOPPED -> ProviderState.DISABLED
            },
            trustLevel = TrustLevel.SYSTEM,
            reliability = health.reliability,
            cost = 0.0,
        )
        registry.register(descriptor)
        return descriptor
    }

    suspend fun uninstallVisualAdapter(
        providerId: String,
        capabilityId: CapabilityId = CapabilityId(MultimodalPerceptionCapabilities.VISUAL_OBSERVATION),
    ): CapabilityDescriptor? = registry.unregister(capabilityId, providerId)
}
