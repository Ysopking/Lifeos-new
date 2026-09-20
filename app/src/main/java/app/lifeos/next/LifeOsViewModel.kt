package app.lifeos.next

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.lifeos.core.language.GoalFrame
import app.lifeos.core.language.LanguageContext
import app.lifeos.core.language.LanguageContextItem
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonRelation
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.RelationType
import app.lifeos.core.runtime.capability.CapabilityGap
import app.lifeos.core.runtime.capability.GeneratedToolGenesisResult
import app.lifeos.core.runtime.capability.GeneratedToolRequestExecutionResult
import app.lifeos.core.runtime.capability.GeneratedToolRuntimeStatus
import app.lifeos.core.runtime.capability.GeneratedToolState
import app.lifeos.core.runtime.evolution.PrivateNovelCapabilityActivationResult
import app.lifeos.core.runtime.goal.LocalSharePreparation
import app.lifeos.core.runtime.life.PerceptionCandidate
import app.lifeos.core.runtime.life.PerceptionModality
import app.lifeos.core.runtime.life.SpeechObservation
import app.lifeos.core.runtime.life.SpeechWordObservation
import app.lifeos.next.kernel.GoalResumeExecutionResult
import app.lifeos.next.kernel.ImageGenerationResult
import app.lifeos.next.kernel.KernelBootstrapStatus
import app.lifeos.next.kernel.LocalCommunicationExecutionResult
import app.lifeos.next.kernel.LocalDeepSearchExecutionResult
import app.lifeos.next.kernel.LocalImageTransformExecutionResult
import app.lifeos.next.kernel.LocalKnowledgeExecutionResult
import app.lifeos.next.kernel.LocalScheduleExecutionResult
import app.lifeos.next.kernel.LocalShareIntentFactory
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class VoiceCapturePhase { IDLE, RECORDING, PROCESSING }

data class LifeOsState(
    val photons: List<Photon> = emptyList(),
    val draft: String = "",
    val loading: Boolean = true,
    val saving: Boolean = false,
    val loadFailed: Boolean = false,
    val unreadable: Int = 0,
    val error: String? = null,
    val lastGoal: GoalFrame? = null,
    val lastCapabilityGaps: List<CapabilityGap> = emptyList(),
    val capabilityRequestSaving: Boolean = false,
    val capabilityRequestStatus: String? = null,
    val capabilityActivationSaving: Boolean = false,
    val capabilityActivationStatus: String? = null,
    val generatedToolStatus: GeneratedToolRuntimeStatus? = null,
    val generatedToolStatusLoading: Boolean = false,
    val generatedToolStatusError: String? = null,
    val pendingShare: LocalSharePreparation? = null,
    val shareStatus: String? = null,
    val voicePhase: VoiceCapturePhase = VoiceCapturePhase.IDLE,
    val voiceStatus: String? = null,
    val pendingVoiceRecognitions: List<Photon> = emptyList(),
)

data class ImagePreview(
    val bitmap: Bitmap,
    val width: Int,
    val height: Int,
    val rendererId: String,
)

sealed interface ImagePreviewState {
    data object Loading : ImagePreviewState
    data class Ready(val preview: ImagePreview) : ImagePreviewState
    data class Failed(val message: String) : ImagePreviewState
}

class LifeOsViewModel(application: Application) : AndroidViewModel(application) {
    private val owner = application as LifeOsApplication
    private val kernel = owner.kernel
    private val multimodalPerception = owner.multimodalPerception
    private val generatedToolStatusReader = owner.generatedToolStatusReader
    private val voiceCapture = AndroidVoiceCaptureEngine(application.applicationContext)
    private val localShareIntentFactory = LocalShareIntentFactory(application.applicationContext, kernel)
    private val voiceStopRequested = AtomicBoolean(false)
    private val mutableState = MutableStateFlow(LifeOsState())
    private val imagePreviewLoader = ImagePreviewLoader(kernel::loadImageAsset)

    val state = mutableState.asStateFlow()
    val runtimeState = kernel.runtime.state
    val matrixState = kernel.matrix.state

    init {
        observeKernel()
    }

    fun editDraft(text: String) {
        val current = mutableState.value
        if (!current.saving && current.voicePhase != VoiceCapturePhase.PROCESSING) {
            mutableState.update {
                it.copy(
                    draft = text,
                    pendingVoiceRecognitions = if (text.isBlank()) emptyList() else it.pendingVoiceRecognitions,
                )
            }
        }
    }

    fun retryLoad() {
        val current = mutableState.value
        if (!current.loadFailed || current.loading) return

        mutableState.update {
            it.copy(
                loading = true,
                loadFailed = false,
                error = null,
            )
        }
        kernel.retryBootstrap()
    }

    fun dismissError() {
        mutableState.update { it.copy(error = null) }
    }

    fun notificationPermissionDenied() {
        mutableState.update {
            it.copy(error = "Benachrichtigungsberechtigung fehlt. Ohne sie kann LIFEOS keine lokale Erinnerung anzeigen.")
        }
    }

    fun refreshGeneratedToolStatus() {
        if (mutableState.value.generatedToolStatusLoading) return
        viewModelScope.launch { loadGeneratedToolStatus() }
    }

    /** One explicit press approves exactly one blocking gap for bounded local Genesis. */
    fun requestCapabilityGaps() {
        val current = mutableState.value
        val gap = current.lastCapabilityGaps.firstOrNull()
        if (
            current.loading ||
            current.loadFailed ||
            current.capabilityRequestSaving ||
            current.capabilityActivationSaving ||
            gap == null
        ) return

        mutableState.update {
            it.copy(
                capabilityRequestSaving = true,
                capabilityRequestStatus = null,
            )
        }
        viewModelScope.launch {
            try {
                val result = kernel.generateExplicitlyApprovedTool(gap)
                val status = when (val execution = result.execution) {
                    is GeneratedToolRequestExecutionResult.Blocked ->
                        "Tool-Erzeugung wurde vor Genesis blockiert: ${execution.reason}"

                    is GeneratedToolRequestExecutionResult.Completed -> when (val genesis = execution.genesis) {
                        is GeneratedToolGenesisResult.OwnerReviewRequired ->
                            "${genesis.record.manifest.toolId} wurde lokal erzeugt, gebaut, getestet und verifiziert. Die exakte Code-Revision wartet jetzt in Assets auf deine Freigabe; erst danach darf das Tool in TRIAL."

                        is GeneratedToolGenesisResult.TrialReady ->
                            "${genesis.record.manifest.toolId} wurde lokal erzeugt, gebaut, getestet und verifiziert. Das Tool ist jetzt isoliert in TRIAL und noch nicht aktiv."

                        is GeneratedToolGenesisResult.Rejected ->
                            "Der lokale ToolWorkshop hat ${genesis.record.manifest.toolId} sicher abgelehnt: ${genesis.reasons.joinToString("; ")}"
                    }
                }
                mutableState.update { it.copy(capabilityRequestStatus = status) }
                loadGeneratedToolStatus()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.update {
                    it.copy(
                        capabilityRequestStatus =
                            "Tool-Erzeugung konnte nicht sicher abgeschlossen werden: ${error.message ?: error::class.simpleName ?: "unbekannter Fehler"}"
                    )
                }
            } finally {
                mutableState.update { it.copy(capabilityRequestSaving = false) }
            }
        }
    }

    /** Separate explicit owner action: five non-productive canaries, review, seal, then guarded ACTIVE. */
    fun reviewAndActivateFirstTrialTool() {
        val current = mutableState.value
        val trial = current.generatedToolStatus?.tools?.firstOrNull { it.state == GeneratedToolState.TRIAL }
        if (
            current.loading ||
            current.loadFailed ||
            current.capabilityRequestSaving ||
            current.capabilityActivationSaving ||
            trial == null
        ) return

        mutableState.update {
            it.copy(
                capabilityActivationSaving = true,
                capabilityActivationStatus = null,
            )
        }
        viewModelScope.launch {
            try {
                val result = kernel.reviewAndActivateGeneratedTool(trial.toolId)
                val status = when (result) {
                    is PrivateNovelCapabilityActivationResult.Activated ->
                        "${result.promotion.activeRecord.manifest.toolId} hat fünf getrennte lokale Novel-Canaries, Readiness, den dauerhaften Promotion-Seal und die getrennte Review-/Owner-Prüfung bestanden. Das Tool ist jetzt ACTIVE mit LOW Trust und wird nach Neustart nur mit exakt passender Evidence wiederhergestellt."
                    is PrivateNovelCapabilityActivationResult.AlreadyActive ->
                        "${result.record.manifest.toolId} ist bereits ACTIVE. Es wurden keine weiteren Canary-Trials ausgeführt."
                    is PrivateNovelCapabilityActivationResult.Blocked ->
                        "Aktivierung von ${result.toolId} wurde sicher blockiert: ${result.reasons.joinToString("; ")}"
                }
                mutableState.update { it.copy(capabilityActivationStatus = status) }
                loadGeneratedToolStatus()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.update {
                    it.copy(
                        capabilityActivationStatus =
                            "Tool-Aktivierung konnte nicht sicher abgeschlossen werden: ${error.message ?: error::class.simpleName ?: "unbekannter Fehler"}"
                    )
                }
            } finally {
                mutableState.update { it.copy(capabilityActivationSaving = false) }
            }
        }
    }

    fun dismissCapabilityRequestStatus() {
        mutableState.update { it.copy(capabilityRequestStatus = null) }
    }

    fun dismissCapabilityActivationStatus() {
        mutableState.update { it.copy(capabilityActivationStatus = null) }
    }

    fun voicePermissionDenied() {
        mutableState.update {
            it.copy(
                voicePhase = VoiceCapturePhase.IDLE,
                voiceStatus = "Mikrofonberechtigung wurde nicht erteilt.",
            )
        }
    }

    fun startVoiceCapture() {
        val current = mutableState.value
        if (current.loading || current.loadFailed || current.saving || current.voicePhase != VoiceCapturePhase.IDLE) return
        if (!voiceCapture.hasPermission()) {
            voicePermissionDenied()
            return
        }

        voiceStopRequested.set(false)
        val context = buildLanguageContext(current.photons)
        mutableState.update {
            it.copy(
                voicePhase = VoiceCapturePhase.RECORDING,
                voiceStatus = "Lokale Sprachaufnahme läuft …",
                error = null,
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            applyVoiceCaptureResult(voiceCapture.capture(voiceStopRequested, context))
        }
    }

    fun stopVoiceCapture() {
        if (mutableState.value.voicePhase != VoiceCapturePhase.RECORDING) return
        voiceStopRequested.set(true)
        mutableState.update {
            it.copy(
                voicePhase = VoiceCapturePhase.PROCESSING,
                voiceStatus = "Sprachfeld wird lokal ausgewertet …",
            )
        }
    }

    fun dismissVoiceStatus() {
        mutableState.update { it.copy(voiceStatus = null) }
    }

    suspend fun createShareIntent(share: LocalSharePreparation): Intent =
        localShareIntentFactory.create(share)

    fun communicationShareOpened(share: LocalSharePreparation) {
        if (mutableState.value.pendingShare != share) return
        mutableState.update { it.copy(pendingShare = null, shareStatus = "Android-Teilen wurde geöffnet.") }
        viewModelScope.launch {
            val receipt = kernel.recordCommunicationHandoff(share)
            if (!receipt.processingQueued) {
                mutableState.update {
                    it.copy(shareStatus = "Android-Teilen wurde geöffnet; der lokale Handoff-Beleg konnte aber nicht vollständig eingereiht werden.")
                }
            }
        }
    }

    fun communicationShareFailed(share: LocalSharePreparation) {
        if (mutableState.value.pendingShare != share) return
        mutableState.update {
            it.copy(
                pendingShare = null,
                error = "Das Android-Teilen konnte nicht geöffnet werden.",
            )
        }
    }

    fun dismissShareStatus() {
        mutableState.update { it.copy(shareStatus = null) }
    }

    fun saveDraft() {
        val current = mutableState.value
        if (current.loading || current.loadFailed || current.saving || current.voicePhase != VoiceCapturePhase.IDLE || current.draft.isBlank()) return

        val submittedContent = current.draft.trim()
        val voiceRecognitions = current.pendingVoiceRecognitions
        val exactVoiceRecognition = voiceRecognitions.singleOrNull()?.takeIf { recognition ->
            "recognition:resolved" in recognition.tags && recognition.content.trim() == submittedContent
        }
        val recognitionParentIds = voiceRecognitions.map { it.id }.toSet()
        val photon = exactVoiceRecognition?.let { null } ?: Photon(
            content = submittedContent,
            provenance = Provenance(
                source = if (recognitionParentIds.isEmpty()) "local-chat" else "local-chat:edited-multimodal",
                actor = "user",
                parentIds = recognitionParentIds,
            ),
            relations = recognitionParentIds.map { parentId ->
                PhotonRelation(parentId, RelationType.DERIVED_FROM)
            }.toSet(),
            tags = buildSet {
                add("chat")
                if (recognitionParentIds.isNotEmpty()) {
                    add("input:speech-edited")
                    add("perception-derived-utterance")
                }
            },
        )
        mutableState.update { it.copy(saving = true, error = null) }

        viewModelScope.launch {
            try {
                val result = if (exactVoiceRecognition != null) {
                    multimodalPerception.routeRecognition(
                        recognition = exactVoiceRecognition,
                        modality = PerceptionModality.SPEECH,
                    )
                } else {
                    kernel.persistUserUtterance(requireNotNull(photon))
                }
                val retainDraft =
                    result.localSchedule is LocalScheduleExecutionResult.Blocked ||
                        result.localImageTransform is LocalImageTransformExecutionResult.Blocked
                mutableState.update {
                    it.copy(
                        draft = if (retainDraft) submittedContent else "",
                        pendingVoiceRecognitions = if (retainDraft) voiceRecognitions else emptyList(),
                        lastGoal = result.effectiveGoal ?: it.lastGoal,
                        lastCapabilityGaps = result.effectiveRouting?.blockingGaps.orEmpty(),
                        capabilityRequestStatus = null,
                        capabilityActivationStatus = null,
                        pendingShare = (result.localCommunication as? LocalCommunicationExecutionResult.Prepared)?.share,
                        shareStatus = null,
                        error = when {
                            result.languageFailure != null ->
                                "Gedanke wurde gespeichert, aber das lokale Sprachverständnis ist fehlgeschlagen."
                            !result.source.processingQueued ->
                                "Gedanke wurde gespeichert, konnte aber nicht zur Verarbeitung eingereiht werden."
                            result.goal?.processingQueued != true ->
                                "Gedanke wurde verstanden und gespeichert, das abgeleitete Ziel konnte aber nicht zur Verarbeitung eingereiht werden."
                            result.goalResume is GoalResumeExecutionResult.Blocked ->
                                "Das vorherige Ziel konnte nicht sicher fortgesetzt werden: ${result.goalResume.message}"
                            result.goalResume is GoalResumeExecutionResult.Failed ->
                                "Das vorherige Ziel konnte nicht fortgesetzt werden: ${result.goalResume.message}"
                            result.goalResume is GoalResumeExecutionResult.Resumed && !result.goalResume.resumedGoal.processingQueued ->
                                "Das vorherige Ziel wurde wiederaufgenommen und gespeichert, konnte aber nicht dauerhaft zur Verarbeitung eingereiht werden."
                            result.localKnowledge is LocalKnowledgeExecutionResult.Failed ->
                                "Die lokale Wissensaktion ist fehlgeschlagen: ${result.localKnowledge.message}"
                            result.localKnowledge is LocalKnowledgeExecutionResult.Produced && !result.localKnowledge.output.processingQueued ->
                                "Die lokale Wissensantwort wurde gespeichert, konnte aber nicht dauerhaft zur Verarbeitung eingereiht werden."
                            result.localDeepSearch is LocalDeepSearchExecutionResult.Failed ->
                                "Die lokale DeepSearch-Suche ist fehlgeschlagen: ${result.localDeepSearch.message}"
                            result.localDeepSearch is LocalDeepSearchExecutionResult.Produced && !result.localDeepSearch.output.processingQueued ->
                                "Das lokale DeepSearch-Ergebnis wurde gespeichert, konnte aber nicht dauerhaft zur Verarbeitung eingereiht werden."
                            result.localSchedule is LocalScheduleExecutionResult.Blocked -> when (result.localSchedule.reason) {
                                "notification-permission-required" -> "Für lokale Erinnerungen muss die Benachrichtigungsberechtigung erteilt werden."
                                "reminder-time-missing" -> "Für die Erinnerung fehlt eine Uhrzeit. Der Entwurf bleibt zum Ergänzen erhalten."
                                else -> "Die lokale Erinnerung konnte nicht geplant werden: ${result.localSchedule.reason}"
                            }
                            result.localSchedule is LocalScheduleExecutionResult.Failed ->
                                "Die lokale Erinnerung ist fehlgeschlagen: ${result.localSchedule.message}"
                            result.localSchedule is LocalScheduleExecutionResult.Scheduled && !result.localSchedule.output.processingQueued ->
                                "Die Erinnerung wurde lokal geplant, ihr Photon konnte aber nicht dauerhaft zur Verarbeitung eingereiht werden."
                            result.localImageTransform is LocalImageTransformExecutionResult.Blocked -> when (result.localImageTransform.reason) {
                                "image-transform-operation-unsupported" ->
                                    "Unterstützte lokale Bildänderungen sind heller, dunkler, wärmer, kühler, schärfer oder Graustufen."
                                "image-transform-reference-unresolved", "goal-is-not-action-ready" ->
                                    "Welches lokale Bild bearbeitet werden soll, ist nicht eindeutig. Der Entwurf bleibt zum Ergänzen erhalten."
                                "image-transform-source-missing" -> "Es ist kein lokales Bild zum Bearbeiten vorhanden."
                                "image-transform-pixel-budget-exceeded" -> "Das Bild ist für die lokale Bearbeitung zu groß."
                                else -> "Die lokale Bildbearbeitung ist blockiert: ${result.localImageTransform.reason}"
                            }
                            result.localImageTransform is LocalImageTransformExecutionResult.Failed ->
                                "Die lokale Bildbearbeitung ist fehlgeschlagen: ${result.localImageTransform.message}"
                            result.localImageTransform is LocalImageTransformExecutionResult.Transformed &&
                                result.localImageTransform.ownerReviewCandidateId == null &&
                                !result.localImageTransform.output.processingQueued ->
                                "Das bearbeitete Bild wurde lokal gespeichert, konnte aber nicht dauerhaft zur Verarbeitung eingereiht werden."
                            result.localCommunication is LocalCommunicationExecutionResult.Blocked ->
                                "Es gibt kein eindeutig teilbares lokales Ergebnis."
                            result.localCommunication is LocalCommunicationExecutionResult.Failed ->
                                "Das lokale Teilen konnte nicht vorbereitet werden: ${result.localCommunication.message}"
                            result.imageGeneration is ImageGenerationResult.Blocked ->
                                "Das Bildziel wurde verstanden, kann mit den lokalen Fähigkeiten aber noch nicht vollständig ausgeführt werden."
                            result.imageGeneration is ImageGenerationResult.Failed ->
                                "Das Bild konnte lokal nicht erzeugt werden: ${result.imageGeneration.message}"
                            else -> null
                        },
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update {
                    it.copy(error = "Gedanke konnte nicht gespeichert werden. Die Eingabe bleibt im Textfeld.")
                }
            } finally {
                mutableState.update { it.copy(saving = false) }
            }
        }
    }

    suspend fun loadImagePreview(photon: Photon): ImagePreviewState =
        imagePreviewLoader.load(photon)

    private suspend fun loadGeneratedToolStatus() {
        mutableState.update { it.copy(generatedToolStatusLoading = true, generatedToolStatusError = null) }
        try {
            val status = withContext(Dispatchers.IO) { generatedToolStatusReader.snapshot() }
            mutableState.update {
                it.copy(
                    generatedToolStatus = status,
                    generatedToolStatusError = null,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableState.update {
                it.copy(generatedToolStatusError = "Generated-Tool-Status konnte nicht gelesen werden.")
            }
        } finally {
            mutableState.update { it.copy(generatedToolStatusLoading = false) }
        }
    }

    private suspend fun applyVoiceCaptureResult(result: LocalVoiceCaptureResult) {
        if (result !is LocalVoiceCaptureResult.Success) {
            mutableState.update { state -> applyVoiceResult(state, result) }
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
            perceptionFailure = error.message ?: error::class.simpleName ?: "unbekannter Fehler"
        }
        mutableState.update { state ->
            applyVoiceResult(
                state = state,
                result = result,
                recognition = recognition,
                perceptionFailure = perceptionFailure,
            )
        }
    }

    private fun buildSpeechObservation(result: LocalVoiceCaptureResult.Success): SpeechObservation = SpeechObservation(
        sourceId = "android-microphone",
        observedAt = result.observedAt,
        observedUntil = result.observedUntil,
        words = result.words.mapIndexed { index, word ->
            val candidates = buildList {
                add(PerceptionCandidate(word.canonical, word.confidence, word.semanticTag))
                word.alternatives.forEach { (value, confidence) ->
                    if (value != word.canonical) add(PerceptionCandidate(value, confidence))
                }
            }.distinctBy { candidate -> candidate.value to candidate.semanticTag }
            SpeechWordObservation(index = index, candidates = candidates)
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
                (state.pendingVoiceRecognitions.filterNot { it.id == persisted.id } + persisted)
            } ?: state.pendingVoiceRecognitions
            state.copy(
                draft = combinedDraft,
                pendingVoiceRecognitions = recognitions,
                voicePhase = VoiceCapturePhase.IDLE,
                voiceStatus = buildString {
                    append("Lokales Sprachfeld: ")
                    append(result.words.size).append(" Wortkandidat(en), ")
                    append(result.segmentCount).append(" Sprachsegment(e), ")
                    append("Konfidenz ").append("%.0f".format(confidence * 100.0)).append(" %")
                    if (result.stoppedByLimit) append(" · 20-s-Limit erreicht")
                    when {
                        recognition != null -> append(" · Observation & Recognition als Photonen persistiert")
                        perceptionFailure != null -> append(" · Photon-Lineage nicht persistiert: ").append(perceptionFailure)
                    }
                },
            )
        }
        LocalVoiceCaptureResult.NoSpeech -> state.copy(
            voicePhase = VoiceCapturePhase.IDLE,
            voiceStatus = "Keine ausreichend stabile Sprache im lokalen Akustikfeld erkannt.",
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

    private fun buildLanguageContext(photons: List<Photon>): LanguageContext = LanguageContext(
        items = photons.take(MAX_VOICE_CONTEXT_PHOTONS).mapIndexed { index, photon ->
            LanguageContextItem(
                photonId = photon.id,
                kind = photon.mimeType,
                tags = photon.tags,
                createdAt = photon.provenance.createdAt,
                active = index < ACTIVE_VOICE_CONTEXT_PHOTONS,
                contentTerms = CONTEXT_TERM_REGEX.findAll(photon.content)
                    .map { it.value.lowercase() }
                    .filter { it.length >= 2 }
                    .take(MAX_TERMS_PER_PHOTON)
                    .toSet(),
                confidence = photon.confidence,
            )
        },
    )

    private fun observeKernel() {
        viewModelScope.launch {
            kernel.bootstrapState.collect { bootstrap ->
                mutableState.update { current ->
                    val loadError = bootstrap.status == KernelBootstrapStatus.FAILED
                    current.copy(
                        photons = bootstrap.photons
                            .filter { it.isUserVisiblePhoton() }
                            .asReversed(),
                        loading = bootstrap.status == KernelBootstrapStatus.CREATED ||
                            bootstrap.status == KernelBootstrapStatus.LOADING,
                        loadFailed = loadError,
                        unreadable = bootstrap.unreadableFiles,
                        error = when {
                            loadError -> LOAD_ERROR_MESSAGE
                            current.error == LOAD_ERROR_MESSAGE -> null
                            else -> current.error
                        },
                    )
                }
                if (
                    bootstrap.status == KernelBootstrapStatus.READY ||
                    bootstrap.status == KernelBootstrapStatus.DEGRADED
                ) {
                    loadGeneratedToolStatus()
                }
            }
        }
    }

    override fun onCleared() {
        voiceStopRequested.set(true)
        imagePreviewLoader.clear()
        super.onCleared()
    }

    private fun Photon.isUserVisiblePhoton(): Boolean =
        "goal" !in tags &&
            "scene-graph" !in tags &&
            "tool-request" !in tags &&
            "tool-generation-approval" !in tags &&
            "perception" !in tags &&
            "perception-raw-source" !in tags

    private companion object {
        const val LOAD_ERROR_MESSAGE = "Speicher konnte nicht geladen werden. Bitte erneut versuchen."
        const val MAX_VOICE_CONTEXT_PHOTONS = 24
        const val ACTIVE_VOICE_CONTEXT_PHOTONS = 6
        const val MAX_TERMS_PER_PHOTON = 32
        val CONTEXT_TERM_REGEX = Regex("[\\p{L}\\p{N}]+")
    }
}
