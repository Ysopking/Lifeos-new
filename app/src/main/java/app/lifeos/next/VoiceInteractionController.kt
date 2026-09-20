package app.lifeos.next

import android.content.Context
import app.lifeos.core.language.LanguageContext
import app.lifeos.core.language.LanguageContextItem
import app.lifeos.core.model.Photon
import app.lifeos.core.runtime.life.PerceptionCandidate
import app.lifeos.core.runtime.life.SpeechObservation
import app.lifeos.core.runtime.life.SpeechWordObservation
import app.lifeos.next.kernel.MultimodalPerceptionRuntime
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class VoiceInteractionController(
    context: Context,
    private val multimodalPerception: MultimodalPerceptionRuntime,
    private val state: MutableStateFlow<LifeOsState>,
    private val scope: CoroutineScope,
) {
    private val voiceCapture = AndroidVoiceCaptureEngine(context.applicationContext)
    private val stopRequested = AtomicBoolean(false)

    fun permissionDenied() {
        state.update {
            it.copy(
                voicePhase = VoiceCapturePhase.IDLE,
                voiceStatus = "Mikrofonberechtigung wurde nicht erteilt.",
            )
        }
    }

    fun start() {
        val current = state.value
        if (
            current.loading ||
            current.loadFailed ||
            current.saving ||
            current.voicePhase != VoiceCapturePhase.IDLE
        ) return
        if (!voiceCapture.hasPermission()) {
            permissionDenied()
            return
        }

        stopRequested.set(false)
        val context = buildLanguageContext(current.photons)
        state.update {
            it.copy(
                voicePhase = VoiceCapturePhase.RECORDING,
                voiceStatus = "Lokale Sprachaufnahme läuft …",
                error = null,
            )
        }

        scope.launch(Dispatchers.IO) {
            applyCaptureResult(
                voiceCapture.capture(stopRequested, context)
            )
        }
    }

    fun stop() {
        if (state.value.voicePhase != VoiceCapturePhase.RECORDING) return
        stopRequested.set(true)
        state.update {
            it.copy(
                voicePhase = VoiceCapturePhase.PROCESSING,
                voiceStatus = "Sprachfeld wird lokal ausgewertet …",
            )
        }
    }

    fun dismissStatus() {
        state.update { it.copy(voiceStatus = null) }
    }

    fun clear() {
        stopRequested.set(true)
    }

    private suspend fun applyCaptureResult(result: LocalVoiceCaptureResult) {
        if (result !is LocalVoiceCaptureResult.Success) {
            state.update { current -> applyVoiceResult(current, result) }
            return
        }

        var recognition: Photon? = null
        var perceptionFailure: String? = null
        try {
            recognition = multimodalPerception.observeSpeech(
                observation = buildSpeechObservation(result),
                sampleRateHz = AndroidVoiceCaptureEngine.SAMPLE_RATE_HZ,
                capturedMillis = result.capturedMillis,
            ).recognition?.photon
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            perceptionFailure =
                error.message ?: error::class.simpleName ?: "unbekannter Fehler"
        }
        state.update { current ->
            applyVoiceResult(
                state = current,
                result = result,
                recognition = recognition,
                perceptionFailure = perceptionFailure,
            )
        }
    }

    private fun buildSpeechObservation(
        result: LocalVoiceCaptureResult.Success,
    ): SpeechObservation = SpeechObservation(
        sourceId = "android-microphone",
        observedAt = result.observedAt,
        observedUntil = result.observedUntil,
        words = result.words.mapIndexed { index, word ->
            val candidates = buildList {
                add(
                    PerceptionCandidate(
                        word.canonical,
                        word.confidence,
                        word.semanticTag,
                    )
                )
                word.alternatives.forEach { (value, confidence) ->
                    if (value != word.canonical) {
                        add(PerceptionCandidate(value, confidence))
                    }
                }
            }.distinctBy { candidate ->
                candidate.value to candidate.semanticTag
            }
            SpeechWordObservation(
                index = index,
                candidates = candidates,
            )
        },
        tags = setOf(
            "conversation:default",
            "input:speech",
            "privacy:local-only",
        ),
    )

    private fun applyVoiceResult(
        state: LifeOsState,
        result: LocalVoiceCaptureResult,
        recognition: Photon? = null,
        perceptionFailure: String? = null,
    ): LifeOsState = when (result) {
        is LocalVoiceCaptureResult.Success -> {
            val transcript = result.transcript.trim()
            val combinedDraft = when {
                transcript.isBlank() -> state.draft
                state.draft.isBlank() -> transcript
                else -> "${state.draft.trimEnd()} $transcript"
            }
            val confidence = result.words.map { it.confidence }.average()
            val recognitions = recognition?.let { persisted ->
                (
                    state.pendingVoiceRecognitions.filterNot {
                        it.id == persisted.id
                    } + persisted
                    )
            } ?: state.pendingVoiceRecognitions
            state.copy(
                draft = combinedDraft,
                pendingVoiceRecognitions = recognitions,
                voicePhase = VoiceCapturePhase.IDLE,
                voiceStatus = buildString {
                    append("Lokales Sprachfeld: ")
                    append(result.words.size).append(" Wortkandidat(en), ")
                    append(result.segmentCount).append(" Sprachsegment(e), ")
                    append("Konfidenz ")
                        .append("%.0f".format(confidence * 100.0))
                        .append(" %")
                    if (result.stoppedByLimit) {
                        append(" · 20-s-Limit erreicht")
                    }
                    when {
                        recognition != null ->
                            append(
                                " · Observation & Recognition als Photonen persistiert"
                            )

                        perceptionFailure != null ->
                            append(" · Photon-Lineage nicht persistiert: ")
                                .append(perceptionFailure)
                    }
                },
            )
        }

        LocalVoiceCaptureResult.NoSpeech -> state.copy(
            voicePhase = VoiceCapturePhase.IDLE,
            voiceStatus =
                "Keine ausreichend stabile Sprache im lokalen Akustikfeld erkannt.",
        )

        LocalVoiceCaptureResult.PermissionMissing -> state.copy(
            voicePhase = VoiceCapturePhase.IDLE,
            voiceStatus = "Mikrofonberechtigung fehlt.",
        )

        is LocalVoiceCaptureResult.Failed -> state.copy(
            voicePhase = VoiceCapturePhase.IDLE,
            voiceStatus = result.message,
        )
    }

    private fun buildLanguageContext(
        photons: List<Photon>,
    ): LanguageContext = LanguageContext(
        items = photons.take(MAX_VOICE_CONTEXT_PHOTONS)
            .mapIndexed { index, photon ->
                LanguageContextItem(
                    photonId = photon.id,
                    kind = photon.mimeType,
                    tags = photon.tags,
                    createdAt = photon.provenance.createdAt,
                    active = index < ACTIVE_VOICE_CONTEXT_PHOTONS,
                    contentTerms = CONTEXT_TERM_REGEX
                        .findAll(photon.content)
                        .map { it.value.lowercase() }
                        .filter { it.length >= 2 }
                        .take(MAX_TERMS_PER_PHOTON)
                        .toSet(),
                    confidence = photon.confidence,
                )
            },
    )

    private companion object {
        const val MAX_VOICE_CONTEXT_PHOTONS = 24
        const val ACTIVE_VOICE_CONTEXT_PHOTONS = 6
        const val MAX_TERMS_PER_PHOTON = 32
        val CONTEXT_TERM_REGEX = Regex("[\\p{L}\\p{N}]+")
    }
}
