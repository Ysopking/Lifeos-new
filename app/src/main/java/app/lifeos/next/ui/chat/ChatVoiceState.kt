package app.lifeos.next.ui.chat

import app.lifeos.next.kernel.InitialCognitiveContextRuntimeRegistry
import app.lifeos.next.kernel.KernelBootstrapStatus

enum class ChatVoicePhase {
    IDLE,
    RECORDING,
    PROCESSING,
}

enum class ChatVoiceStartResult {
    STARTED,
    PERMISSION_REQUIRED,
    BLOCKED,
}

data class ChatVoiceUiState(
    val phase: ChatVoicePhase = ChatVoicePhase.IDLE,
    val status: String? = null,
    val draftAtCaptureStart: String? = null,
    val stagedTranscript: String? = null,
    val voiceDraftBaseline: String? = null,
    val voiceInputEdited: Boolean = false,
)

sealed interface VoiceDraftResolution {
    data class Applied(val draft: String) : VoiceDraftResolution
    data class Staged(val transcript: String) : VoiceDraftResolution
}

object ChatVoicePolicy {
    fun canStartVoice(
        bootStatus: KernelBootstrapStatus,
        processing: ChatTurnProcessingState,
        voice: ChatVoiceUiState,
    ): Boolean =
        InitialCognitiveContextRuntimeRegistry.current().contextReady &&
            !processing.inFlight &&
            voice.phase == ChatVoicePhase.IDLE &&
            voice.stagedTranscript == null &&
            (bootStatus == KernelBootstrapStatus.READY || bootStatus == KernelBootstrapStatus.DEGRADED)

    fun canSend(
        draft: String,
        bootStatus: KernelBootstrapStatus,
        processing: ChatTurnProcessingState,
        voice: ChatVoiceUiState,
    ): Boolean =
        InitialCognitiveContextRuntimeRegistry.current().contextReady &&
            ChatComposerPolicy.canSend(draft, bootStatus, processing) &&
            voice.phase == ChatVoicePhase.IDLE &&
            voice.stagedTranscript == null

    fun resolveTranscript(
        draftAtCaptureStart: String,
        currentDraft: String,
        transcript: String,
    ): VoiceDraftResolution {
        val normalized = transcript.trim()
        require(normalized.isNotBlank()) { "Voice transcript must not be blank" }
        return if (currentDraft == draftAtCaptureStart) {
            VoiceDraftResolution.Applied(mergeDraft(currentDraft, normalized))
        } else {
            VoiceDraftResolution.Staged(normalized)
        }
    }

    fun mergeDraft(currentDraft: String, transcript: String): String {
        val normalized = transcript.trim()
        require(normalized.isNotBlank()) { "Voice transcript must not be blank" }
        return when {
            currentDraft.isBlank() -> normalized
            else -> "${currentDraft.trimEnd()} $normalized"
        }
    }
}