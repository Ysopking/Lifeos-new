package app.lifeos.next

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val model = ViewModelProvider(this)[LifeOsViewModel::class.java]
        setContent { LifeOsApp(model) }
    }
}

@Composable
private fun LifeOsApp(model: LifeOsViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val runtime by model.runtimeState.collectAsStateWithLifecycle()
    val matrix by model.matrixState.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var diagnostics by rememberSaveable { mutableStateOf(false) }
    val visible = remember(state.photons, query) {
        val term = query.trim()
        state.photons.filter { it.content.contains(term, ignoreCase = true) || it.tags.any { tag -> tag.contains(term, ignoreCase = true) } }
    }
    val dateFormat = remember { DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault()) }
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("LIFEOS · Gedanken", style = MaterialTheme.typography.headlineMedium)
                Text("${state.photons.size} gespeichert · lokal verschlüsselt", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Gedanken durchsuchen") }, singleLine = true)
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.loading) item { Text("Gedanken werden geladen …") }
                    else if (visible.isEmpty()) item { Text(if (query.isBlank()) "Halte deinen ersten Gedanken fest." else "Keine passenden Gedanken gefunden.") }
                    items(visible, key = { it.id.value }) { photon ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                SelectionContainer { Text(photon.content) }
                                Text(dateFormat.format(photon.provenance.createdAt), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                if (state.unreadable > 0) Text("${state.unreadable} Datei(en) nicht lesbar. Originaldateien bleiben erhalten.", color = MaterialTheme.colorScheme.error)
                state.error?.let { error ->
                    Text(error, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = if (state.loadFailed) model::retryLoad else model::dismissError) {
                        Text(if (state.loadFailed) "Erneut laden" else "Meldung schließen")
                    }
                }
                OutlinedTextField(state.draft, model::editDraft, Modifier.fillMaxWidth(), label = { Text("Neuer Gedanke") }, maxLines = 4, enabled = !state.saving)
                Button(model::saveDraft, Modifier.fillMaxWidth(), enabled = state.draft.isNotBlank() && !state.loading && !state.loadFailed && !state.saving) {
                    Text(if (state.saving) "Wird gespeichert …" else "Gedanken speichern")
                }
                TextButton(onClick = { diagnostics = !diagnostics }) { Text(if (diagnostics) "Diagnose schließen" else "Diagnose") }
                if (diagnostics) AlertDialog(
                    onDismissRequest = { diagnostics = false },
                    confirmButton = { TextButton(onClick = { diagnostics = false }) { Text("Schließen") } },
                    title = { Text("App-Diagnose") },
                    text = { Text("Version ${BuildConfig.VERSION_NAME}\nRuntime: ${if (runtime.running) "aktiv" else "gestoppt"}\nIndexiert: ${matrix.nodes.size}\nVerarbeitet: ${runtime.processed}\nFehlgeschlagen: ${runtime.failed}\nFeldeinflüsse im Verlauf: ${runtime.recentInfluences.size}\nFeldenergie: ${"%.1f".format(matrix.totalEnergy)}\n${runtime.lastError ?: "Kein Runtime-Fehler"}") },
                )
            }
        }
    }
}
