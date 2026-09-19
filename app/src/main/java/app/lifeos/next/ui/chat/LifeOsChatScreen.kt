package app.lifeos.next.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lifeos.core.runtime.chat.ChatEvent
import app.lifeos.core.runtime.chat.ChatRole
import app.lifeos.next.LifeOsChatViewModel
import app.lifeos.next.kernel.InitialCognitiveContextRuntimeRegistry
import app.lifeos.next.ui.components.LifeOsContentFrame
import app.lifeos.next.ui.components.LifeOsRuntimeAlert
import app.lifeos.next.ui.components.buildRuntimeHealthUiModel
import app.lifeos.next.ui.system.RuntimeHealthModalSheet
import app.lifeos.next.ui.theme.LifeOsTokens

@Composable
fun LifeOsChatScreen(
    model: LifeOsChatViewModel,
    modifier: Modifier = Modifier,
    onRequestMicrophonePermission: () -> Unit = {},
) {
    val state by model.state.collectAsStateWithLifecycle()
    val cognitiveContext by InitialCognitiveContextRuntimeRegistry.state.collectAsStateWithLifecycle()
    var showRuntimeHealth by rememberSaveable { mutableStateOf(false) }
    val runtimeHealth = buildRuntimeHealthUiModel(
        bootStatus = state.bootStatus,
        readiness = state.readiness,
        topologyEvidence = state.runtimeTopology,
        selfStateEvidence = state.selfState,
    )

    LifeOsContentFrame(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        LifeOsRuntimeAlert(
            model = runtimeHealth,
            onOpenDetails = { showRuntimeHealth = true },
        )

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(LifeOsTokens.Spacing.small),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                vertical = LifeOsTokens.Spacing.small,
            ),
        ) {
            if (state.timeline.isEmpty()) {
                item {
                    Text(
                        if (cognitiveContext.contextReady) {
                            "Schreib LIFEOS eine Nachricht."
                        } else {
                            "LIFEOS baut zuerst den persönlichen Gedächtniskontext auf."
                        }
                    )
                }
            }
            items(state.timeline, key = { it.id }) { item ->
                when (item) {
                    is ChatTimelineItem.Message -> ChatMessage(item.event)
                    is ChatTimelineItem.Image -> Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start,
                    ) {
                        ChatImageContent(
                            photon = item.photon,
                            loadPreview = model::loadChatImagePreview,
                            modifier = Modifier.fillMaxWidth(0.92f),
                        )
                    }
                }
            }
        }

        state.error?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = LifeOsTokens.Spacing.small),
            )
        }

        ChatComposer(
            draft = state.draft,
            bootStatus = state.bootStatus,
            processing = state.turnProcessing,
            voice = state.voice,
            cognitiveContext = cognitiveContext,
            onDraftChange = model::editDraft,
            onSend = model::sendMessage,
            onStartVoice = {
                if (model.beginVoiceCapture() == ChatVoiceStartResult.PERMISSION_REQUIRED) {
                    onRequestMicrophonePermission()
                }
            },
            onStopVoice = model::stopVoiceCapture,
            onAcceptStagedVoice = model::acceptStagedVoiceTranscript,
            onDiscardStagedVoice = model::discardStagedVoiceTranscript,
        )
    }

    if (showRuntimeHealth) {
        RuntimeHealthModalSheet(
            health = runtimeHealth,
            readiness = state.readiness,
            topology = state.runtimeTopology,
            onDismiss = { showRuntimeHealth = false },
            selfState = state.selfState,
        )
    }
}

@Composable
private fun ChatMessage(event: ChatEvent) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (event.role == ChatRole.USER) Arrangement.End else Arrangement.Start,
    ) {
        Card {
            Column(Modifier.padding(LifeOsTokens.Spacing.medium)) {
                Text(
                    when (event.role) {
                        ChatRole.USER -> "Du"
                        ChatRole.LIFEOS -> "LIFEOS"
                        ChatRole.SYSTEM -> "System"
                    },
                    style = MaterialTheme.typography.labelMedium,
                )
                event.text?.let { Text(it) }
            }
        }
    }
}
