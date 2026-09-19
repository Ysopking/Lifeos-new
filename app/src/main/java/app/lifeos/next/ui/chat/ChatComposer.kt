package app.lifeos.next.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.lifeos.next.R
import app.lifeos.next.kernel.InitialCognitiveContextPhase
import app.lifeos.next.kernel.InitialCognitiveContextReadiness
import app.lifeos.next.kernel.InitialCognitiveContextRuntimeRegistry
import app.lifeos.next.kernel.KernelBootstrapStatus
import app.lifeos.next.ui.theme.LifeOsTokens

@Composable
fun ChatComposer(
    draft: String,
    bootStatus: KernelBootstrapStatus,
    processing: ChatTurnProcessingState,
    voice: ChatVoiceUiState = ChatVoiceUiState(),
    cognitiveContext: InitialCognitiveContextReadiness = InitialCognitiveContextRuntimeRegistry.current(),
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onStartVoice: () -> Unit = {},
    onStopVoice: () -> Unit = {},
    onAcceptStagedVoice: () -> Unit = {},
    onDiscardStagedVoice: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val canSend = ChatVoicePolicy.canSend(
        draft = draft,
        bootStatus = bootStatus,
        processing = processing,
        voice = voice,
    )
    val canStartVoice = ChatVoicePolicy.canStartVoice(
        bootStatus = bootStatus,
        processing = processing,
        voice = voice,
    )
    val editorEnabled = cognitiveContext.contextReady && !processing.inFlight

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(LifeOsTokens.Spacing.small),
    ) {
        if (
            cognitiveContext.phase == InitialCognitiveContextPhase.WAITING_FOR_PERMISSIONS ||
            cognitiveContext.phase == InitialCognitiveContextPhase.PARTIAL ||
            cognitiveContext.phase == InitialCognitiveContextPhase.FAILED
        ) {
            Text(
                text = cognitiveContextStatus(cognitiveContext),
                style = MaterialTheme.typography.bodySmall,
                color = if (cognitiveContext.phase == InitialCognitiveContextPhase.FAILED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(
                LifeOsTokens.Radius.composer
            ),
            tonalElevation = LifeOsTokens.Elevation.resting,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Column(
                modifier = Modifier.padding(
                    horizontal = LifeOsTokens.Spacing.large,
                    vertical = LifeOsTokens.Spacing.medium,
                ),
                verticalArrangement = Arrangement.spacedBy(LifeOsTokens.Spacing.small),
            ) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    if (draft.isEmpty()) {
                        Text(
                            text = if (cognitiveContext.contextReady) {
                                "Frag LIFEOS …"
                            } else {
                                "Gedächtnis wird vorbereitet …"
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    BasicTextField(
                        value = draft,
                        onValueChange = onDraftChange,
                        enabled = editorEnabled,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                contentDescription = "Nachricht an LIFEOS"
                            },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        minLines = 1,
                        maxLines = 5,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                if (canSend) onSend()
                            }
                        ),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(LifeOsTokens.Spacing.small),
                ) {
                    when (voice.phase) {
                        ChatVoicePhase.IDLE -> IconButton(
                            onClick = onStartVoice,
                            enabled = canStartVoice,
                            modifier = Modifier.semantics {
                                contentDescription = "Spracheingabe starten"
                            },
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_voice),
                                contentDescription = null,
                                modifier = Modifier.size(LifeOsTokens.Size.actionIcon),
                            )
                        }

                        ChatVoicePhase.RECORDING -> Button(
                            onClick = onStopVoice,
                            modifier = Modifier.semantics {
                                contentDescription = "Sprachaufnahme stoppen"
                            },
                        ) {
                            Text("Stopp")
                        }

                        ChatVoicePhase.PROCESSING -> Box(
                            modifier = Modifier
                                .size(LifeOsTokens.Size.minimumTouchTarget)
                                .semantics {
                                    contentDescription = "Sprache wird lokal verarbeitet"
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(LifeOsTokens.Size.actionIcon),
                                strokeWidth = 2.dp,
                            )
                        }
                    }

                    Spacer(Modifier.weight(1f))

                    FilledIconButton(
                        modifier = Modifier.semantics {
                            contentDescription = when {
                                !cognitiveContext.contextReady -> "Gedächtnis wird vorbereitet"
                                processing.inFlight -> "Nachricht wird verarbeitet"
                                else -> "Senden"
                            }
                        },
                        onClick = onSend,
                        enabled = canSend,
                    ) {
                        if (processing.inFlight) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(LifeOsTokens.Size.actionIcon),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                painter = painterResource(R.drawable.ic_send),
                                contentDescription = null,
                                modifier = Modifier.size(LifeOsTokens.Size.actionIcon),
                            )
                        }
                    }
                }
            }
        }

        voice.stagedTranscript?.let { transcript ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Column(
                    modifier = Modifier.padding(LifeOsTokens.Spacing.medium),
                    verticalArrangement = Arrangement.spacedBy(LifeOsTokens.Spacing.xSmall),
                ) {
                    Text(
                        text = "Erkannter Sprachtext",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        text = transcript,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(LifeOsTokens.Spacing.small)) {
                        TextButton(onClick = onAcceptStagedVoice) { Text("Übernehmen") }
                        TextButton(onClick = onDiscardStagedVoice) { Text("Verwerfen") }
                    }
                }
            }
        }

        voice.status
            ?.takeIf { voice.phase != ChatVoicePhase.IDLE || voice.stagedTranscript != null }
            ?.let { status ->
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

        ChatComposerPolicy.statusLabel(processing)?.let { status ->
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = if (processing.phase == ChatTurnPhase.FAILED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

private fun cognitiveContextStatus(context: InitialCognitiveContextReadiness): String =
    when (context.phase) {
        InitialCognitiveContextPhase.PREPARING -> "Kognitiver Kontext wird vorbereitet …"
        InitialCognitiveContextPhase.WAITING_FOR_PERMISSIONS ->
            "Erststart: Datenfreigaben werden geprüft …"
        InitialCognitiveContextPhase.BUILDING_MEMORY ->
            "Daten werden eingelesen und das Gedächtnis wird aufgebaut …"
        InitialCognitiveContextPhase.PARTIAL -> buildString {
            append("Gedächtnis ist mit Teilkontext bereit")
            val missing = context.unauthorizedSources + context.unavailableSources
            if (missing > 0) append(" · $missing Quelle(n) fehlen")
            append('.')
        }
        InitialCognitiveContextPhase.FAILED ->
            "Gedächtnis konnte nicht aufgebaut werden: ${context.failure.orEmpty()}"
        InitialCognitiveContextPhase.READY -> "Gedächtnis bereit."
    }
