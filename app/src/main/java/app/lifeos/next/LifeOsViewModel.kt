package app.lifeos.next

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.lifeos.core.language.GoalFrame
import app.lifeos.core.model.Photon
import app.lifeos.core.runtime.capability.CapabilityGap
import app.lifeos.core.runtime.capability.GeneratedToolRuntimeStatus
import app.lifeos.core.runtime.goal.LocalSharePreparation
import app.lifeos.next.kernel.KernelBootstrapStatus
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
    private val chatSubmission = ChatSubmissionController(
        kernel = kernel,
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

    fun saveDraft() = chatSubmission.submitDraft()

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
