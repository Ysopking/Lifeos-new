package app.lifeos.core.runtime.capability

/**
 * First-class capability contracts for LIFEOS-native perception fields.
 * These describe executable model cores, not Android microphone/camera acquisition.
 */
object MultimodalPerceptionCapabilities {
    val LOCAL_FIELD_PROVIDERS: List<CapabilityDescriptor> = listOf(
        CapabilityDescriptor(
            capabilityId = CapabilityId(WORD_FIELD),
            providerId = "linguistic-field-core",
            providerType = ProviderType.MODULE,
            contract = CapabilityContract(
                requiredInputs = setOf("text-observation"),
                outputs = setOf("lexical-semantic-field"),
            ),
            state = ProviderState.ACTIVE,
            trustLevel = TrustLevel.SYSTEM,
            reliability = 1.0,
            cost = 0.0,
        ),
        CapabilityDescriptor(
            capabilityId = CapabilityId(SPEECH_FIELD),
            providerId = "bidirectional-speech-field-core",
            providerType = ProviderType.MODULE,
            contract = CapabilityContract(
                requiredInputs = setOf("pcm16-mono"),
                outputs = setOf("speech-field-result"),
            ),
            state = ProviderState.ACTIVE,
            trustLevel = TrustLevel.SYSTEM,
            reliability = 1.0,
            cost = 0.0,
        ),
        CapabilityDescriptor(
            capabilityId = CapabilityId(WRITING_FIELD),
            providerId = "grapheme-phonetic-field-core",
            providerType = ProviderType.MODULE,
            contract = CapabilityContract(
                requiredInputs = setOf("grapheme-candidate-lattice"),
                outputs = setOf("writing-field-result"),
            ),
            state = ProviderState.ACTIVE,
            trustLevel = TrustLevel.SYSTEM,
            reliability = 1.0,
            cost = 0.0,
        ),
    )

    /**
     * Raw camera/image -> grapheme observation remains a separate adapter capability. It must not
     * be advertised ACTIVE until a concrete visual observer is installed and health-checked.
     */
    val VISUAL_WRITING_OBSERVER = CapabilityRequirement(
        capabilityId = CapabilityId(VISUAL_WRITING),
        severity = GapSeverity.BLOCKING,
        requiredInputs = setOf("image-photon"),
        requiredOutputs = setOf("grapheme-candidate-lattice"),
    )

    const val WORD_FIELD = "word.resolve.field"
    const val SPEECH_FIELD = "speech.recognize.field"
    const val WRITING_FIELD = "writing.recognize.field"
    const val VISUAL_WRITING = "writing.observe.visual"
}
