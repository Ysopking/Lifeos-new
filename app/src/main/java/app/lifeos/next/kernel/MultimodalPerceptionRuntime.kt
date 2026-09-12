package app.lifeos.next.kernel

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.StableCognitiveIds
import app.lifeos.core.runtime.capability.CapabilityDescriptor
import app.lifeos.core.runtime.capability.CapabilityId
import app.lifeos.core.runtime.capability.GeneratedToolRuntimeProcessRegistry
import app.lifeos.core.runtime.capability.MultimodalPerceptionCapabilities
import app.lifeos.core.runtime.capability.MultimodalPerceptionCapabilityInstaller
import app.lifeos.core.runtime.capability.VisualPerceptionAdapterHealthSource
import app.lifeos.core.runtime.life.PerceptionFusionEngine
import app.lifeos.core.runtime.life.PerceptionModality
import app.lifeos.core.runtime.life.PerceptionSemanticPhotonFactory
import app.lifeos.core.runtime.life.SpeechObservation
import app.lifeos.core.runtime.life.VisualObservation
import app.lifeos.core.runtime.life.WritingFieldPerceptionResolver
import app.lifeos.core.runtime.life.WritingObservation


data class MultimodalObservationSubmission(
    val rawSource: PhotonSubmissionResult?,
    val observation: PhotonSubmissionResult,
    val recognition: PhotonSubmissionResult?,
    val modality: PerceptionModality,
)

/** A real visual-writing adapter must provide both health and actual image -> grapheme observation. */
interface VisualWritingObservationAdapter : VisualPerceptionAdapterHealthSource {
    suspend fun observe(sourceAsset: Photon, encodedImage: ByteArray): WritingObservation
}

/**
 * Productive M bridge. Raw source evidence, typed observations and semantic results are separate
 * immutable Photons. Resolved speech/writing enters normal Language/Goal execution only through the
 * explicit routeRecognition call, preserving the existing user-submit boundary.
 */
class MultimodalPerceptionRuntime(
    private val kernel: LifeOsKernel,
    private val fusion: PerceptionFusionEngine = PerceptionFusionEngine(),
    private val semantics: PerceptionSemanticPhotonFactory = PerceptionSemanticPhotonFactory(),
    private val writing: WritingFieldPerceptionResolver = WritingFieldPerceptionResolver(),
) {
    @Volatile
    private var visualWritingAdapter: VisualWritingObservationAdapter? = null

    suspend fun install() {
        val registry = requireNotNull(GeneratedToolRuntimeProcessRegistry.capabilities()) {
            "Kernel capability registry is not installed"
        }
        MultimodalPerceptionCapabilityInstaller(registry).installLocalFields()
    }

    suspend fun observeSpeech(
        observation: SpeechObservation,
        sampleRateHz: Int,
        capturedMillis: Long,
    ): MultimodalObservationSubmission {
        require(sampleRateHz > 0)
        require(capturedMillis >= 0L)
        val raw = speechCaptureEnvelope(observation, sampleRateHz, capturedMillis)
        val persistedRaw = kernel.persistAndIngest(raw)
        val typed = observation.copy(sourceAssetPhotonId = raw.id)
        val signal = typed.toSignal()
        val observationPhoton = fusion.fuse(listOf(signal)).photons.single()
        val persistedObservation = kernel.persistAndIngest(observationPhoton)
        val recognitionPhoton = semantics.recognition(
            observation = observationPhoton,
            modality = PerceptionModality.SPEECH,
            recognizedText = typed.transcript,
            confidence = typed.confidence,
            traceFingerprint = signal.fingerprint,
            candidates = signal.canonicalCandidates,
        )
        val persistedRecognition = kernel.persistAndIngest(recognitionPhoton)
        return MultimodalObservationSubmission(
            rawSource = persistedRaw,
            observation = persistedObservation,
            recognition = persistedRecognition,
            modality = PerceptionModality.SPEECH,
        )
    }

    suspend fun observeWriting(observation: WritingObservation): MultimodalObservationSubmission {
        val signal = observation.toSignal()
        val observationPhoton = fusion.fuse(listOf(signal)).photons.single()
        val persistedObservation = kernel.persistAndIngest(observationPhoton)
        val resolution = writing.resolve(observation)
        val recognitionPhoton = semantics.recognition(
            observation = observationPhoton,
            modality = PerceptionModality.WRITING,
            recognizedText = resolution.recognizedText,
            confidence = resolution.confidence,
            traceFingerprint = resolution.traceFingerprint,
            candidates = resolution.lexicalCandidates,
        )
        val persistedRecognition = kernel.persistAndIngest(recognitionPhoton)
        return MultimodalObservationSubmission(
            rawSource = observation.sourceAssetPhotonId?.let { assetId ->
                kernel.photonStore.load(assetId)?.let { PhotonSubmissionResult(it, processingQueued = true) }
            },
            observation = persistedObservation,
            recognition = persistedRecognition,
            modality = PerceptionModality.WRITING,
        )
    }

    suspend fun observeVisual(observation: VisualObservation): MultimodalObservationSubmission {
        val observationPhoton = fusion.fuse(listOf(observation.toSignal())).photons.single()
        val persisted = kernel.persistAndIngest(observationPhoton)
        return MultimodalObservationSubmission(
            rawSource = kernel.photonStore.load(observation.sourceAssetPhotonId)?.let {
                PhotonSubmissionResult(it, processingQueued = true)
            },
            observation = persisted,
            recognition = null,
            modality = PerceptionModality.VISUAL,
        )
    }

    suspend fun routeRecognition(
        recognition: Photon,
        modality: PerceptionModality,
        conversationId: String = "default",
    ): LanguageSubmissionResult {
        val utterance = semantics.asUserUtterance(recognition, modality, conversationId)
        return kernel.persistUserUtterance(utterance)
    }

    suspend fun installVisualWritingAdapter(adapter: VisualWritingObservationAdapter): CapabilityDescriptor {
        val registry = requireNotNull(GeneratedToolRuntimeProcessRegistry.capabilities()) {
            "Kernel capability registry is not installed"
        }
        val descriptor = MultimodalPerceptionCapabilityInstaller(registry).installVisualAdapter(
            adapter = adapter,
            capabilityId = CapabilityId(MultimodalPerceptionCapabilities.VISUAL_WRITING),
            requiredInputs = setOf("image-asset-photon"),
            outputs = setOf("typed-grapheme-observation"),
        )
        visualWritingAdapter = if (
            descriptor.state == app.lifeos.core.runtime.capability.ProviderState.ACTIVE ||
            descriptor.state == app.lifeos.core.runtime.capability.ProviderState.DEGRADED
        ) adapter else null
        return descriptor
    }

    suspend fun uninstallVisualWritingAdapter(providerId: String) {
        val registry = GeneratedToolRuntimeProcessRegistry.capabilities() ?: return
        MultimodalPerceptionCapabilityInstaller(registry).uninstallVisualAdapter(
            providerId = providerId,
            capabilityId = CapabilityId(MultimodalPerceptionCapabilities.VISUAL_WRITING),
        )
        if (visualWritingAdapter?.providerId == providerId) visualWritingAdapter = null
    }

    suspend fun observeWritingImage(sourceAsset: Photon): MultimodalObservationSubmission {
        val adapter = visualWritingAdapter ?: error("No usable visual writing adapter is installed")
        val bytes = kernel.loadImageAsset(sourceAsset)
            ?: error("Writing image asset is missing or failed integrity verification")
        val observation = adapter.observe(sourceAsset, bytes)
        require(observation.sourceAssetPhotonId == sourceAsset.id) {
            "Visual writing observation must retain the exact source asset Photon"
        }
        return observeWriting(observation)
    }

    private fun speechCaptureEnvelope(
        observation: SpeechObservation,
        sampleRateHz: Int,
        capturedMillis: Long,
    ): Photon {
        val fingerprint = StableCognitiveIds.fingerprint(
            "speech-capture-envelope/v1",
            observation.sourceId,
            observation.observedAt.toString(),
            observation.observedUntil.toString(),
            sampleRateHz.toString(),
            capturedMillis.toString(),
            "ephemeral-cleared",
        )
        return Photon(
            id = PhotonId("speech-capture-${fingerprint.take(48)}"),
            content = buildString {
                appendLine("sample_rate_hz=$sampleRateHz")
                appendLine("captured_millis=$capturedMillis")
                appendLine("observed_until=${observation.observedUntil}")
                append("raw_pcm_state=EPHEMERAL_CLEARED")
            },
            mimeType = "application/vnd.lifeos.audio-capture-envelope+text",
            semanticMass = 0.1,
            energy = 0.0,
            confidence = 1.0,
            provenance = Provenance(
                source = "local-microphone",
                actor = observation.sourceId,
                createdAt = observation.observedAt,
            ),
            tags = setOf(
                "perception-raw-source",
                "raw-audio-capture",
                "raw-asset-state:ephemeral-cleared",
                "privacy:local-only",
            ),
        )
    }
}
