package app.lifeos.next.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (!cognitiveContext.contextReady || cognitiveContext.phase == InitialCognitiveContextPhase.PARTIAL) {
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

        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text(
                    if (cognitiveContext.contextReady) {
                        "Nachricht an LIFEOS"
                    } else {
                        "Gedächtnismatrix wird vorbereitet"
                    }
                )
            },
            enabled = cognitiveContext.contextReady && !processing.inFlight,
            minLines = 1,
            maxLines = 5,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(
                onSend = {
                    if (canSend) onSend()
                }
            ),
        )

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (voice.phase) {
                ChatVoicePhase.IDLE -> OutlinedButton(
                    onClick = onStartVoice,
                    enabled = canStartVoice,
                ) {
                    Text("Sprache")
                }
                ChatVoicePhase.RECORDING -> Button(onClick = onStopVoice) {
                    Text("Stopp")
                }
                ChatVoicePhase.PROCESSING -> OutlinedButton(
                    modifier = Modifier.semantics {
                        contentDescription = "Sprache wird lokal verarbeitet"
                    },
                    onClick = {},
                    enabled = false,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
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
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("Senden")
                }
            }
        }

        voice.stagedTranscript?.let { transcript ->
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("Erkannter Sprachtext", style = MaterialTheme.typography.labelMedium)
                    Text(transcript, style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onAcceptStagedVoice) { Text("Übernehmen") }
                        TextButton(onClick = onDiscardStagedVoice) { Text("Verwerfen") }
                    }
                }
            }
        }

        voice.status?.let { status ->
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

private fun cognitiveContextStatus(context: InitialCognitiveContextReadiness): String = when (context.phase) {
    InitialCognitiveContextPhase.PREPARING -> "Kognitiver Kontext wird vorbereitet …"
    InitialCognitiveContextPhase.WAITING_FOR_PERMISSIONS -> "Erststart: Datenfreigaben werden geprüft …"
    InitialCognitiveContextPhase.BUILDING_MEMORY -> "Daten werden eingelesen und die Gedächtnismatrix wird aufgebaut …"
    InitialCognitiveContextPhase.PARTIAL -> buildString {
        append("Gedächtnismatrix ist mit Teilkontext bereit")
        val missing = context.unauthorizedSources + context.unavailableSources
        if (missing > 0) append(" · $missing Quelle(n) fehlen")
        append('.')
    }
    InitialCognitiveContextPhase.FAILED -> "Gedächtnismatrix konnte nicht aufgebaut werden: ${context.failure.orEmpty()}"
    InitialCognitiveContextPhase.READY -> "Gedächtnismatrix bereit."
}
