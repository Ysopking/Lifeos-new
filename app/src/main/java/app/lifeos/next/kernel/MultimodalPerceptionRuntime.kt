package app.lifeos.next.kernel

import app.lifeos.core.language.DeterministicSpeechRecognitionEngine
import app.lifeos.core.language.GraphemeCandidateLattice
import app.lifeos.core.language.LanguageContext
import app.lifeos.core.language.Pcm16MonoAudio
import app.lifeos.core.language.PerceptionModality
import app.lifeos.core.language.PerceptionPhotonFactory
import app.lifeos.core.language.PhotonLanguageContextBuilder
import app.lifeos.core.language.SpeechFieldRecognitionResult
import app.lifeos.core.language.WritingFieldRecognitionEngine
import app.lifeos.core.language.WritingFieldRecognitionResult
import app.lifeos.core.model.Photon
import app.lifeos.core.runtime.capability.CapabilityContract
import app.lifeos.core.runtime.capability.CapabilityDescriptor
import app.lifeos.core.runtime.capability.CapabilityId
import app.lifeos.core.runtime.capability.GeneratedToolRuntimeProcessRegistry
import app.lifeos.core.runtime.capability.MultimodalPerceptionCapabilities
import app.lifeos.core.runtime.capability.ProviderState
import app.lifeos.core.runtime.capability.ProviderType
import app.lifeos.core.runtime.capability.TrustLevel

sealed interface MultimodalLanguageSubmission {
    val source: PhotonSubmissionResult
    val recognition: PhotonSubmissionResult

    data class Routed(
        override val source: PhotonSubmissionResult,
        override val recognition: PhotonSubmissionResult,
        val language: LanguageSubmissionResult,
    ) : MultimodalLanguageSubmission

    data class Unresolved(
        override val source: PhotonSubmissionResult,
        override val recognition: PhotonSubmissionResult,
        val reason: String,
    ) : MultimodalLanguageSubmission
}

enum class VisualWritingAdapterState {
    READY,
    DEGRADED,
    QUARANTINED,
    STOPPED,
}

data class VisualWritingAdapterStatus(
    val state: VisualWritingAdapterState,
    val detail: String? = null,
)

/** Raw image observer seam. No visual OCR provider is advertised until one is explicitly installed. */
interface VisualWritingObservationAdapter {
    val id: String
    suspend fun status(): VisualWritingAdapterStatus
    suspend fun observe(source: Photon, encodedImage: ByteArray): GraphemeCandidateLattice
}

/**
 * Productive bridge that puts LIFEOS-native word/speech/writing fields on the same runtime plane as
 * image generation. Every recognized result becomes a provenance-linked Photon and, when resolved,
 * re-enters the exact same language/goal/capability/action path as typed chat.
 */
class MultimodalPerceptionRuntime(
    private val kernel: LifeOsKernel,
    private val speech: DeterministicSpeechRecognitionEngine = DeterministicSpeechRecognitionEngine(),
    private val writing: WritingFieldRecognitionEngine = WritingFieldRecognitionEngine(),
    private val photons: PerceptionPhotonFactory = PerceptionPhotonFactory(),
    private val contextBuilder: PhotonLanguageContextBuilder = PhotonLanguageContextBuilder(),
) {
    @Volatile private var visualWritingAdapter: VisualWritingObservationAdapter? = null

    suspend fun install() {
        val registry = requireNotNull(GeneratedToolRuntimeProcessRegistry.capabilities()) {
            "Kernel capability registry is not installed"
        }
        MultimodalPerceptionCapabilities.LOCAL_FIELD_PROVIDERS.forEach { descriptor ->
            registry.register(descriptor)
        }
    }

    suspend fun submitSpeech(
        source: Photon,
        audio: Pcm16MonoAudio,
        semanticField: Map<String, Double> = emptyMap(),
        conversationId: String = "default",
    ): MultimodalLanguageSubmission {
        val persistedSource = kernel.persistAndIngest(source)
        val context = contextFor(source)
        val field = speech.recognize(audio, semanticField, context)
        val recognitionPhoton = photons.speech(source, field)
        val persistedRecognition = kernel.persistAndIngest(recognitionPhoton)
        if (field.recognizedText == null) {
            return MultimodalLanguageSubmission.Unresolved(
                source = persistedSource,
                recognition = persistedRecognition,
                reason = "Speech field did not converge on lexical evidence",
            )
        }
        return routeRecognition(
            source = persistedSource,
            recognition = persistedRecognition,
            modality = PerceptionModality.SPEECH,
            conversationId = conversationId,
        )
    }

    suspend fun submitWriting(
        source: Photon,
        lattice: GraphemeCandidateLattice,
        conversationId: String = "default",
    ): MultimodalLanguageSubmission {
        val persistedSource = kernel.persistAndIngest(source)
        val field = writing.recognize(lattice)
        val recognitionPhoton = photons.writing(source, field)
        val persistedRecognition = kernel.persistAndIngest(recognitionPhoton)
        return routeRecognition(
            source = persistedSource,
            recognition = persistedRecognition,
            modality = PerceptionModality.WRITING,
            conversationId = conversationId,
        )
    }

    suspend fun installVisualWritingAdapter(adapter: VisualWritingObservationAdapter): CapabilityDescriptor {
        require(adapter.id.isNotBlank())
        val status = adapter.status()
        val descriptor = CapabilityDescriptor(
            capabilityId = CapabilityId(MultimodalPerceptionCapabilities.VISUAL_WRITING),
            providerId = adapter.id,
            providerType = ProviderType.MODULE,
            contract = CapabilityContract(
                requiredInputs = setOf("image-photon"),
                outputs = setOf("grapheme-candidate-lattice"),
            ),
            state = when (status.state) {
                VisualWritingAdapterState.READY -> ProviderState.ACTIVE
                VisualWritingAdapterState.DEGRADED -> ProviderState.DEGRADED
                VisualWritingAdapterState.QUARANTINED -> ProviderState.QUARANTINED
                VisualWritingAdapterState.STOPPED -> ProviderState.DISABLED
            },
            trustLevel = TrustLevel.SYSTEM,
            reliability = if (status.state == VisualWritingAdapterState.READY) 1.0 else 0.75,
            cost = 0.0,
        )
        requireNotNull(GeneratedToolRuntimeProcessRegistry.capabilities()).register(descriptor)
        visualWritingAdapter = if (
            status.state == VisualWritingAdapterState.READY || status.state == VisualWritingAdapterState.DEGRADED
        ) adapter else null
        return descriptor
    }

    suspend fun uninstallVisualWritingAdapter(providerId: String) {
        GeneratedToolRuntimeProcessRegistry.capabilities()?.unregister(
            CapabilityId(MultimodalPerceptionCapabilities.VISUAL_WRITING),
            providerId,
        )
        if (visualWritingAdapter?.id == providerId) visualWritingAdapter = null
    }

    suspend fun submitWritingImage(
        source: Photon,
        conversationId: String = "default",
    ): MultimodalLanguageSubmission {
        val adapter = visualWritingAdapter ?: error("No healthy visual writing observation adapter is installed")
        val bytes = kernel.loadImageAsset(source) ?: error("Writing image asset is missing or failed integrity verification")
        val lattice = adapter.observe(source, bytes)
        return submitWriting(source, lattice, conversationId)
    }

    fun recognizeSpeechField(
        audio: Pcm16MonoAudio,
        semanticField: Map<String, Double> = emptyMap(),
        context: LanguageContext = LanguageContext(),
    ): SpeechFieldRecognitionResult = speech.recognize(audio, semanticField, context)

    fun recognizeWritingField(lattice: GraphemeCandidateLattice): WritingFieldRecognitionResult = writing.recognize(lattice)

    private suspend fun routeRecognition(
        source: PhotonSubmissionResult,
        recognition: PhotonSubmissionResult,
        modality: PerceptionModality,
        conversationId: String,
    ): MultimodalLanguageSubmission.Routed {
        val utterance = photons.asUserUtterance(
            sourceRecognition = recognition.photon,
            modality = modality,
            conversationId = conversationId,
        )
        return MultimodalLanguageSubmission.Routed(
            source = source,
            recognition = recognition,
            language = kernel.persistUserUtterance(utterance),
        )
    }

    private fun contextFor(source: Photon): LanguageContext = contextBuilder.build(
        photons = kernel.bootstrapState.value.photons,
        now = source.provenance.createdAt,
        excludeIds = setOf(source.id),
    )
}
