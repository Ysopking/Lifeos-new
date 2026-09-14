package app.lifeos.next.ui.chat

import app.lifeos.next.kernel.InitialCognitiveContextRuntimeRegistry
import app.lifeos.next.kernel.KernelBootstrapStatus
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ChatVoiceStateTest {
    @BeforeTest
    fun setUpCognitiveContext() {
        InitialCognitiveContextRuntimeRegistry.markReadyForTest()
    }

    @AfterTest
    fun tearDownCognitiveContext() {
        InitialCognitiveContextRuntimeRegistry.resetForTest()
    }

    @Test
    fun readyIdleStateAllowsCapture() {
        assertTrue(
            ChatVoicePolicy.canStartVoice(
                KernelBootstrapStatus.READY,
                ChatTurnProcessingState.idle(),
                ChatVoiceUiState(),
            )
        )
    }

    @Test
    fun degradedBootAllowsCaptureLikeTextSend() {
        assertTrue(
            ChatVoicePolicy.canStartVoice(
                KernelBootstrapStatus.DEGRADED,
                ChatTurnProcessingState.idle(),
                ChatVoiceUiState(),
            )
        )
    }

    @Test
    fun cognitiveContextBuildBlocksTextAndVoiceEvenWhenKernelIsReady() {
        InitialCognitiveContextRuntimeRegistry.resetForTest()
        InitialCognitiveContextRuntimeRegistry.markBuildingMemory()

        assertFalse(
            ChatVoicePolicy.canStartVoice(
                KernelBootstrapStatus.READY,
                ChatTurnProcessingState.idle(),
                ChatVoiceUiState(),
            )
        )
        assertFalse(
            ChatVoicePolicy.canSend(
                "Hallo",
                KernelBootstrapStatus.READY,
                ChatTurnProcessingState.idle(),
                ChatVoiceUiState(),
            )
        )
    }

    @Test
    fun loadingAndFailedBootBlockCapture() {
        assertFalse(ChatVoicePolicy.canStartVoice(KernelBootstrapStatus.LOADING, ChatTurnProcessingState.idle(), ChatVoiceUiState()))
        assertFalse(ChatVoicePolicy.canStartVoice(KernelBootstrapStatus.FAILED, ChatTurnProcessingState.idle(), ChatVoiceUiState()))
    }

    @Test
    fun inFlightTurnBlocksCapture() {
        assertFalse(
            ChatVoicePolicy.canStartVoice(
                KernelBootstrapStatus.READY,
                ChatTurnProcessingState.submitting("turn-1"),
                ChatVoiceUiState(),
            )
        )
    }

    @Test
    fun recordingAndProcessingBlockSend() {
        assertFalse(
            ChatVoicePolicy.canSend(
                "Hallo",
                KernelBootstrapStatus.READY,
                ChatTurnProcessingState.idle(),
                ChatVoiceUiState(phase = ChatVoicePhase.RECORDING),
            )
        )
        assertFalse(
            ChatVoicePolicy.canSend(
                "Hallo",
                KernelBootstrapStatus.READY,
                ChatTurnProcessingState.idle(),
                ChatVoiceUiState(phase = ChatVoicePhase.PROCESSING),
            )
        )
    }

    @Test
    fun stagedTranscriptBlocksSendUntilResolved() {
        assertFalse(
            ChatVoicePolicy.canSend(
                "Neu getippt",
                KernelBootstrapStatus.READY,
                ChatTurnProcessingState.idle(),
                ChatVoiceUiState(stagedTranscript = "Sprachtext"),
            )
        )
    }

    @Test
    fun unchangedDraftAppliesTranscript() {
        val result = ChatVoicePolicy.resolveTranscript("Hallo", "Hallo", "Welt")
        assertEquals("Hallo Welt", assertIs<VoiceDraftResolution.Applied>(result).draft)
    }

    @Test
    fun blankUnchangedDraftAdoptsTranscript() {
        val result = ChatVoicePolicy.resolveTranscript("", "", "  Nur Sprache  ")
        assertEquals("Nur Sprache", assertIs<VoiceDraftResolution.Applied>(result).draft)
    }

    @Test
    fun changedDraftStagesWithoutOverwriting() {
        val result = ChatVoicePolicy.resolveTranscript("Alt", "Neu getippt", "Sprachtext")
        assertEquals("Sprachtext", assertIs<VoiceDraftResolution.Staged>(result).transcript)
    }

    @Test
    fun stagedAcceptMergeIsDeterministic() {
        assertEquals("Neu getippt Sprachtext", ChatVoicePolicy.mergeDraft("Neu getippt", "Sprachtext"))
        assertEquals("Neu getippt Sprachtext", ChatVoicePolicy.mergeDraft("Neu getippt", "Sprachtext"))
    }

    @Test
    fun idleVoicePreservesExistingF2SendPolicy() {
        assertTrue(
            ChatVoicePolicy.canSend(
                "Hallo",
                KernelBootstrapStatus.READY,
                ChatTurnProcessingState.idle(),
                ChatVoiceUiState(),
            )
        )
        assertFalse(
            ChatVoicePolicy.canSend(
                " ",
                KernelBootstrapStatus.READY,
                ChatTurnProcessingState.idle(),
                ChatVoiceUiState(),
            )
        )
    }
}