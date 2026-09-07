package app.lifeos.next

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.lifeos.core.model.Photon
import app.lifeos.core.model.Provenance
import app.lifeos.core.data.EncryptedPhotonStore
import app.lifeos.core.runtime.CognitiveRuntime
import app.lifeos.core.runtime.ThoughtMatrix
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LifeOsApp() }
    }
}

@Composable
private fun LifeOsApp() {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val matrix = remember { ThoughtMatrix() }
    val store = remember(context) { EncryptedPhotonStore(context.applicationContext) }
    val runtime = remember { CognitiveRuntime(scope, listOf(matrix)) }
    val runtimeState by runtime.state.collectAsState()
    val matrixState by matrix.state.collectAsState()
    val messages = remember { mutableStateListOf<String>() }
    var input by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var storageStatus by remember { mutableStateOf("Speicher wird geladen …") }
    var storageError by remember { mutableStateOf<String?>(null) }
    DisposableEffect(runtime) { runtime.start(); onDispose { runtime.stop() } }
    LaunchedEffect(Unit) {
        try {
            val report = store.loadReport()
            report.photons.forEach { photon ->
                messages += photon.content
                runtime.ingest(photon)
            }
            storageStatus = "Geladen: ${report.photons.size} · Nicht lesbar: ${report.unreadableFiles.size}"
            if (report.unreadableFiles.isNotEmpty()) {
                storageError = "Einige Photonen konnten nicht geladen werden. Die Dateien bleiben erhalten."
            }
        } catch (error: CancellationException) { throw error
        } catch (error: Exception) {
            storageError = "Speicher konnte nicht geöffnet werden. Bitte App neu starten."
        } finally { loading = false }
    }

    MaterialTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("LIFEOS", style = MaterialTheme.typography.headlineLarge)
                Text("Runtime ${if (runtimeState.running) "aktiv" else "pausiert"} · Photonen ${matrixState.nodes.size}")
                Card(Modifier.fillMaxWidth().weight(1f)) {
                    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (messages.isEmpty()) item { Text("Schreibe einen Gedanken. Er wird als Photon in die Gedankenmatrix aufgenommen.") }
                        items(messages) { Card { Text(it, Modifier.padding(12.dp)) } }
                    }
                }
                OutlinedTextField(input, { input = it }, Modifier.fillMaxWidth(), label = { Text("Gedanke") }, maxLines = 4, enabled = !loading && !saving)
                Button(
                    onClick = {
                        val content = input.trim()
                        saving = true
                        scope.launch {
                            try {
                                val photon = Photon(content = content, provenance = Provenance("local-chat", "user"), tags = setOf("chat"))
                                store.save(photon)
                                messages += content
                                input = ""
                                storageStatus = "Zuletzt eingegebener Gedanke ist gespeichert."
                                runtime.ingest(photon)
                            } catch (error: CancellationException) { throw error
                            } catch (error: Exception) {
                                storageError = "Vorgang fehlgeschlagen. Noch vorhandene Eingabe bleibt erhalten."
                            } finally { saving = false }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = input.isNotBlank() && !loading && !saving,
                ) { Text("Als Photon aufnehmen") }
                Text(storageStatus, style = MaterialTheme.typography.bodySmall)
                storageError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                runtimeState.lastError?.let { Text("Runtime-Fehler: $it", color = MaterialTheme.colorScheme.error) }
                Text("Verarbeitet: ${runtimeState.processed} · Feldenergie: ${"%.1f".format(matrixState.totalEnergy)}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
