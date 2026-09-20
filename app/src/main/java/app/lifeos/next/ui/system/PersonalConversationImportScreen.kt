package app.lifeos.next.ui.system

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lifeos.next.PersonalConversationImportKind
import app.lifeos.next.PersonalConversationImportPhase
import app.lifeos.next.PersonalConversationImportViewModel
import app.lifeos.next.ui.theme.LifeOsTokens

@Composable
internal fun PersonalConversationImportScreen(
    model: PersonalConversationImportViewModel,
    modifier: Modifier = Modifier,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val whatsappPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        model.preview(PersonalConversationImportKind.WHATSAPP, uris)
    }
    val geminiPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        model.preview(PersonalConversationImportKind.GEMINI, uris)
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = LifeOsTokens.Spacing.large),
        verticalArrangement = Arrangement.spacedBy(LifeOsTokens.Spacing.medium),
    ) {
        item {
            Column(
                verticalArrangement = Arrangement.spacedBy(LifeOsTokens.Spacing.small),
            ) {
                Text(
                    text = "Privater Sprachkorpus",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = "Ausgewählte Exporte werden lokal gelesen. Die Vorschau schreibt noch nichts. Erst deine ausdrückliche Import-Freigabe erzeugt archivierte Gesprächs-Photonen.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Archivierte Gespräche erhalten keine Ausführungsautorität und sind vom normalen Sprachkontext getrennt. Nur deine eigenen Turns dürfen später als persönliche Sprachbeispiele verwendet werden.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            OutlinedTextField(
                value = state.ownerNamesInput,
                onValueChange = model::updateOwnerNames,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Dein WhatsApp-Name") },
                supportingText = {
                    Text("Mehrere eigene Namen/Aliase mit Komma trennen. Für Gemini nicht nötig.")
                },
                enabled = state.phase != PersonalConversationImportPhase.IMPORTING &&
                    state.phase != PersonalConversationImportPhase.PREVIEWING,
                singleLine = false,
                maxLines = 3,
            )
        }

        item {
            Column(
                verticalArrangement = Arrangement.spacedBy(LifeOsTokens.Spacing.small),
            ) {
                OutlinedButton(
                    onClick = {
                        whatsappPicker.launch(
                            arrayOf(
                                "text/plain",
                                "application/zip",
                                "application/octet-stream",
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.phase != PersonalConversationImportPhase.IMPORTING &&
                        state.phase != PersonalConversationImportPhase.PREVIEWING,
                ) {
                    Text("WhatsApp TXT/ZIP auswählen")
                }
                OutlinedButton(
                    onClick = {
                        geminiPicker.launch(
                            arrayOf(
                                "application/json",
                                "application/zip",
                                "application/octet-stream",
                                "text/json",
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.phase != PersonalConversationImportPhase.IMPORTING &&
                        state.phase != PersonalConversationImportPhase.PREVIEWING,
                ) {
                    Text("Gemini JSON/ZIP auswählen")
                }
            }
        }

        if (
            state.phase == PersonalConversationImportPhase.PREVIEWING ||
            state.phase == PersonalConversationImportPhase.IMPORTING
        ) {
            item {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    text = if (state.phase == PersonalConversationImportPhase.PREVIEWING) {
                        "Archive werden lokal geprüft …"
                    } else {
                        "Bestätigte Gespräche werden lokal importiert …"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        if (state.previews.isNotEmpty()) {
            item {
                HorizontalDivider()
                Text(
                    text = "Vorschau · ${state.previews.size} Datei(en) · ${state.selectedTurns} Turns",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "Eigene Turns: ${state.ownerTurns} · Gemini-Assistent: ${state.assistantTurns} · Andere/Unbekannt: ${state.otherTurns}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            items(
                items = state.previews,
                key = { it.uri + ":" + it.fileFingerprint },
            ) { preview ->
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(LifeOsTokens.Spacing.medium),
                        verticalArrangement = Arrangement.spacedBy(LifeOsTokens.Spacing.xSmall),
                    ) {
                        Text(preview.displayName, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${preview.kind.name} · ${preview.turnCount} Turns · ${preview.archiveEntries} Archiv-Eintrag/Einträge",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "Owner ${preview.ownerTurns} · Assistant ${preview.assistantTurns} · Andere ${preview.otherTurns}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (preview.skippedUnknownRoles > 0) {
                            Text(
                                "${preview.skippedUnknownRoles} Gemini-Turn(s) mit unbekannter Rolle wurden absichtlich übersprungen.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        if (state.readyToImport) {
            item {
                Button(
                    onClick = model::confirmImport,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("${state.selectedTurns} Turns jetzt importieren")
                }
                TextButton(
                    onClick = model::clearSelection,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Auswahl verwerfen")
                }
            }
        }

        state.result?.let { result ->
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(LifeOsTokens.Spacing.medium),
                        verticalArrangement = Arrangement.spacedBy(LifeOsTokens.Spacing.xSmall),
                    ) {
                        Text("Import abgeschlossen", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${result.created} neu · ${result.replayed} bereits vorhanden · ${result.total} geprüft",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "Die Originaldateien wurden nicht verändert oder verschoben.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        state.error?.let { error ->
            item {
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = model::dismissError) {
                    Text("Meldung schließen")
                }
            }
        }
    }
}
