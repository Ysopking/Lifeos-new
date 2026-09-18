package app.lifeos.next.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lifeos.core.runtime.chat.ChatEvent
import app.lifeos.core.runtime.chat.ChatRole
import app.lifeos.next.LifeOsChatViewModel
import app.lifeos.next.kernel.InitialCognitiveContextRuntimeRegistry
import app.lifeos.next.ui.components.LifeOsRuntimeStatus
import app.lifeos.next.ui.components.RuntimeHealthUiModel
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
    val listState = rememberLazyListState()
    val runtimeHealth = buildRuntimeHealthUiModel(
        bootStatus = state.bootStatus,
        readiness = state.readiness,
        topologyEvidence = state.runtimeTopology,
    )

    LaunchedEffect(state.timeline.size) {
        if (state.timeline.isNotEmpty()) {
            listState.animateScrollToItem(state.timeline.lastIndex)
        }
    }

    Box(
        modifier = modifier.fillMaxSize().imePadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = LifeOsTokens.Layout.contentMaxWidth)
                .padding(
                    horizontal = LifeOsTokens.Spacing.large,
                    vertical = LifeOsTokens.Spacing.medium,
                ),
            verticalArrangement = Arrangement.spacedBy(LifeOsTokens.Spacing.medium),
        ) {
            ChatHeader(runtimeHealth) { showRuntimeHealth = true }

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(LifeOsTokens.Spacing.medium),
            ) {
                if (state.timeline.isEmpty()) {
                    item { ChatEmptyState(cognitiveContext.contextReady) }
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
                                modifier = Modifier
                                    .fillMaxWidth(0.92f)
                                    .widthIn(max = LifeOsTokens.Layout.readingMaxWidth),
                            )
                        }
                    }
                }
            }

            state.error?.let { error ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(
                            horizontal = LifeOsTokens.Spacing.medium,
                            vertical = LifeOsTokens.Spacing.small,
                        ),
                    )
                }
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
    }

    if (showRuntimeHealth) {
        RuntimeHealthModalSheet(
            health = runtimeHealth,
            readiness = state.readiness,
            topology = state.runtimeTopology,
            onDismiss = { showRuntimeHealth = false },
        )
    }
}

@Composable
private fun ChatHeader(
    runtimeHealth: RuntimeHealthUiModel,
    onOpenRuntimeHealth: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(LifeOsTokens.Spacing.small)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    "LIFEOS",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Lokale kognitive Runtime",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(
                    "PRIVATE",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
        LifeOsRuntimeStatus(runtimeHealth, onOpenRuntimeHealth)
    }
}

@Composable
private fun ChatEmptyState(contextReady: Boolean) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = LifeOsTokens.Layout.readingMaxWidth),
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
        tonalElevation = LifeOsTokens.Elevation.resting,
    ) {
        Column(
            modifier = Modifier.padding(LifeOsTokens.Spacing.xLarge),
            verticalArrangement = Arrangement.spacedBy(LifeOsTokens.Spacing.small),
        ) {
            Text(
                if (contextReady) "Was möchtest du erledigen?" else "LIFEOS bereitet deinen Kontext vor",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                if (contextReady) {
                    "Fragen, planen, erinnern, recherchieren oder lokale Werkzeuge nutzen – mit nachvollziehbarer Herkunft und kontrollierten Aktionen."
                } else {
                    "Gedächtnis, Berechtigungen und lokale Runtime-Evidenz werden rehydriert. Danach ist der Chat vollständig verfügbar."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ChatMessage(event: ChatEvent) {
    val isUser = event.role == ChatRole.USER
    val isSystem = event.role == ChatRole.SYSTEM
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(if (isUser) 0.86f else 0.92f)
                .widthIn(max = LifeOsTokens.Layout.readingMaxWidth),
            color = when {
                isUser -> MaterialTheme.colorScheme.primaryContainer
                isSystem -> MaterialTheme.colorScheme.surfaceVariant
                else -> MaterialTheme.colorScheme.surface
            },
            contentColor = when {
                isUser -> MaterialTheme.colorScheme.onPrimaryContainer
                isSystem -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.onSurface
            },
            shape = RoundedCornerShape(
                topStart = if (isUser) LifeOsTokens.Radius.large else 8.dp,
                topEnd = if (isUser) 8.dp else LifeOsTokens.Radius.large,
                bottomStart = LifeOsTokens.Radius.large,
                bottomEnd = LifeOsTokens.Radius.large,
            ),
            tonalElevation = if (isUser) 0.dp else LifeOsTokens.Elevation.resting,
        ) {
            Column(
                modifier = Modifier.padding(
                    horizontal = LifeOsTokens.Spacing.large,
                    vertical = LifeOsTokens.Spacing.medium,
                ),
                verticalArrangement = Arrangement.spacedBy(LifeOsTokens.Spacing.xSmall),
            ) {
                Text(
                    when (event.role) {
                        ChatRole.USER -> "Du"
                        ChatRole.LIFEOS -> "LIFEOS"
                        ChatRole.SYSTEM -> "System"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isUser) {
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                event.text?.let { text ->
                    SelectionContainer {
                        Text(text, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}
