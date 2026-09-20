package app.lifeos.next

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.lifeos.core.language.GoalFrame
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonRelation
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.RelationType
import app.lifeos.core.runtime.capability.CapabilityGap
import app.lifeos.core.runtime.capability.GeneratedToolRuntimeStatus
import app.lifeos.core.runtime.goal.LocalSharePreparation
import app.lifeos.core.runtime.life.PerceptionModality
import app.lifeos.next.kernel.GoalResumeExecutionResult
import app.lifeos.next.kernel.ImageGenerationResult
import app.lifeos.next.kernel.KernelBootstrapStatus
import app.lifeos.next.kernel.LocalCommunicationExecutionResult
import app.lifeos.next.kernel.LocalDeepSearchExecutionResult
import app.lifeos.next.kernel.LocalImageTransformExecutionResult
import app.lifeos.next.kernel.LocalKnowledgeExecutionResult
import app.lifeos.next.kernel.LocalScheduleExecutionResult
import app.lifeos.next.kernel.LocalShareIntentFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    private val mutableState = MutableStateFlow(LifeOsState())
    private val toolCenter = ToolCenterUiController(
        kernel = kernel,
        generatedToolStatusReader = owner.generatedToolStatusReader,
        state = mutableState,
        scope = viewModelScope,
    )
    private val imagePreviewLoader = ImagePreviewLoader(kernel::loadImageAsset)
    private val shareInteraction = ShareInteractionController(
        kernel = kernel,
        intentFactory = LocalShareIntentFactory(application.applicationContext, kernel),
        state = mutableState,
        scope = viewModelScope,
    )
    private val voiceInteraction = VoiceInteractionController(
        context = application.applicationContext,
        multimodalPerception = multimodalPerception,
        state = mutableState,
        scope = viewModelScope,
    )

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

    fun refreshGeneratedToolStatus() = toolCenter.refreshStatus()

    fun requestCapabilityGaps() = toolCenter.requestCapabilityGaps()

    fun reviewAndActivateFirstTrialTool() =
        toolCenter.reviewAndActivateFirstTrialTool()

    fun dismissCapabilityRequestStatus() =
        toolCenter.dismissRequestStatus()

    fun dismissCapabilityActivationStatus() =
        toolCenter.dismissActivationStatus()

    fun voicePermissionDenied() = voiceInteraction.permissionDenied()

    fun startVoiceCapture() = voiceInteraction.start()

    fun stopVoiceCapture() = voiceInteraction.stop()

    fun dismissVoiceStatus() = voiceInteraction.dismissStatus()

    suspend fun createShareIntent(share: LocalSharePreparation): Intent =
        shareInteraction.createIntent(share)

    fun communicationShareOpened(share: LocalSharePreparation) =
        shareInteraction.opened(share)

    fun communicationShareFailed(share: LocalSharePreparation) =
        shareInteraction.failed(share)

    fun dismissShareStatus() = shareInteraction.dismissStatus()

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
                    toolCenter.loadStatus()
                }
            }
        }
    }

    override fun onCleared() {
        voiceInteraction.clear()
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
    }
}
