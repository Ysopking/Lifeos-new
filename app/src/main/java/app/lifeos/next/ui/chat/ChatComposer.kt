package app.lifeos.next.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
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
    val canSend = ChatVoicePolicy.canSend(draft, bootStatus, processing, voice)
    val canStartVoice = ChatVoicePolicy.canStartVoice(bootStatus, processing, voice)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(LifeOsTokens.Spacing.small),
    ) {
        if (!cognitiveContext.contextReady || cognitiveContext.phase == InitialCognitiveContextPhase.PARTIAL) {
            ContextStatus(cognitiveContext)
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.large,
            tonalElevation = LifeOsTokens.Elevation.raised,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(
                modifier = Modifier.padding(LifeOsTokens.Spacing.small),
                verticalArrangement = Arrangement.spacedBy(LifeOsTokens.Spacing.small),
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            if (cognitiveContext.contextReady) {
                                "Frag LIFEOS oder beschreibe eine Aufgabe …"
                            } else {
                                "Gedächtnismatrix wird vorbereitet"
                            }
                        )
                    },
                    enabled = cognitiveContext.contextReady,
                    minLines = 1,
                    maxLines = 6,
                    shape = MaterialTheme.shapes.medium,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(LifeOsTokens.Spacing.small),
                ) {
                    when (voice.phase) {
                        ChatVoicePhase.IDLE -> FilledTonalButton(
                            onClick = onStartVoice,
                            enabled = canStartVoice,
                        ) { Text("● Sprache") }
                        ChatVoicePhase.RECORDING -> Button(onClick = onStopVoice) {
                            Text("■ Stopp")
                        }
                        ChatVoicePhase.PROCESSING -> FilledTonalButton(
                            modifier = Modifier.semantics {
                                contentDescription = "Sprache wird lokal verarbeitet"
                            },
                            onClick = {},
                            enabled = false,
                        ) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        }
                    }

                    Spacer(Modifier.weight(1f))

                    Button(
                        modifier = Modifier.semantics {
                            contentDescription = when {
                                !cognitiveContext.contextReady -> "Gedächtnismatrix wird vorbereitet"
                                processing.inFlight -> "Nachricht wird verarbeitet"
                                else -> "Senden"
                            }
                        },
                        onClick = onSend,
                        enabled = canSend,
                    ) {
                        if (processing.inFlight) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Senden  ↑")
                        }
                    }
                }
            }
        }

        voice.stagedTranscript?.let { transcript ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(
                    Modifier.padding(LifeOsTokens.Spacing.medium),
                    verticalArrangement = Arrangement.spacedBy(LifeOsTokens.Spacing.xSmall),
                ) {
                    Text("Erkannter Sprachtext", style = MaterialTheme.typography.labelMedium)
                    Text(transcript, style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(LifeOsTokens.Spacing.small)) {
                        TextButton(onClick = onAcceptStagedVoice) { Text("Übernehmen") }
                        TextButton(onClick = onDiscardStagedVoice) { Text("Verwerfen") }
                    }
                }
            }
        }

        voice.status?.let { SupportingStatus(it) }
        ChatComposerPolicy.statusLabel(processing)?.let {
            SupportingStatus(it, processing.phase == ChatTurnPhase.FAILED)
        }
    }
}

@Composable
private fun ContextStatus(context: InitialCognitiveContextReadiness) {
    val failed = context.phase == InitialCognitiveContextPhase.FAILED
    Surface(
        color = if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (failed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            cognitiveContextStatus(context),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(
                horizontal = LifeOsTokens.Spacing.medium,
                vertical = LifeOsTokens.Spacing.small,
            ),
        )
    }
}

@Composable
private fun SupportingStatus(status: String, error: Boolean = false) {
    Text(
        status,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = LifeOsTokens.Spacing.xSmall),
    )
}

private fun cognitiveContextStatus(context: InitialCognitiveContextReadiness): String = when (context.phase) {
    InitialCognitiveContextPhase.PREPARING -> "Kognitiver Kontext wird vorbereitet …"
    InitialCognitiveContextPhase.WAITING_FOR_PERMISSIONS -> "Erststart: Datenfreigaben werden geprüft …"
    InitialCognitiveContextPhase.BUILDING_MEMORY -> "Daten werden eingelesen und die Gedächtnismatrix wird aufgebaut …"
    InitialCognitiveContextPhase.PARTIAL -> buildString {
        append("Gedächtnismatrix ist mit Teilkontext bereit")
        val missing = context.unauthorizedSources + context.unavailableSources
        if (missing > 0) {
            append(" · ")
            append(missing)
            append(" Quelle(n) fehlen")
        }
        append('.')
    }
    InitialCognitiveContextPhase.FAILED ->
        "Gedächtnismatrix konnte nicht aufgebaut werden: " + context.failure.orEmpty()
    InitialCognitiveContextPhase.READY -> "Gedächtnismatrix bereit."
}
