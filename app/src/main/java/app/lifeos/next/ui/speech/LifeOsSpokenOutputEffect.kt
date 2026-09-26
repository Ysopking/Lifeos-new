package app.lifeos.next.ui.speech

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.lifeos.core.runtime.chat.ChatRole
import app.lifeos.next.kernel.KernelBootstrapStatus
import app.lifeos.next.ui.chat.ChatTimelineItem
import app.lifeos.next.ui.chat.ChatVoicePhase
import app.lifeos.next.ui.chat.HumanReadableOutput

/**
 * Speaks only assistant messages created during the current UI session. Historic conversation
 * rehydration is never replayed aloud after app start.
 */
@Composable
fun LifeOsSpokenOutputEffect(
    timeline: List<ChatTimelineItem>,
    bootStatus: KernelBootstrapStatus,
    voicePhase: ChatVoicePhase,
) {
    val context = LocalContext.current
    var speech by remember(context.applicationContext) {
        mutableStateOf<AndroidSpeechOutput?>(null)
    }
    val sessionStartedAtMillis by rememberSaveable {
        mutableStateOf(System.currentTimeMillis())
    }
    var lastSpokenEventId by rememberSaveable {
        mutableStateOf<String?>(null)
    }

    DisposableEffect(Unit) {
        onDispose {
            speech?.close()
            speech = null
        }
    }

    LaunchedEffect(voicePhase) {
        if (voicePhase == ChatVoicePhase.RECORDING) {
            speech?.stop()
        }
    }

    val latest = timeline
        .asSequence()
        .filterIsInstance<ChatTimelineItem.Message>()
        .map { it.event }
        .filter { it.role == ChatRole.LIFEOS }
        .filter { !it.text.isNullOrBlank() }
        .lastOrNull()

    LaunchedEffect(bootStatus, voicePhase, latest?.id) {
        if (
            bootStatus != KernelBootstrapStatus.READY &&
            bootStatus != KernelBootstrapStatus.DEGRADED
        ) return@LaunchedEffect
        if (voicePhase != ChatVoicePhase.IDLE) return@LaunchedEffect

        val event = latest ?: return@LaunchedEffect
        if (event.createdAt.toEpochMilli() < sessionStartedAtMillis) return@LaunchedEffect
        if (event.id == lastSpokenEventId) return@LaunchedEffect

        lastSpokenEventId = event.id
        val output = speech ?: AndroidSpeechOutput(
            context.applicationContext
        ).also { speech = it }
        output.speak(
            id = event.id,
            text = HumanReadableOutput.forSpeech(event.text.orEmpty()),
        )
    }
}
