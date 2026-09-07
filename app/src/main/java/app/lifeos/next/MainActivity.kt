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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.lifeos.core.model.Photon
import app.lifeos.core.model.Provenance
import app.lifeos.core.runtime.CognitiveRuntime
import app.lifeos.core.runtime.ThoughtMatrix
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LifeOsApp() }
    }
}

@Composable
private fun LifeOsApp() {
    val scope = rememberCoroutineScope()
    val matrix = remember { ThoughtMatrix() }
    val runtime = remember { CognitiveRuntime(scope, listOf(matrix)) }
    val runtimeState by runtime.state.collectAsState()
    val matrixState by matrix.state.collectAsState()
    val messages = remember { mutableStateListOf<String>() }
    var input by remember { mutableStateOf("") }
    DisposableEffect(runtime) { runtime.start(); onDispose { runtime.stop() } }

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
                OutlinedTextField(input, { input = it }, Modifier.fillMaxWidth(), label = { Text("Gedanke") }, maxLines = 4)
                Button(
                    onClick = {
                        val content = input.trim(); input = ""; messages += content
                        scope.launch { runtime.ingest(Photon(content = content, provenance = Provenance("local-chat", "user"), tags = setOf("chat"))) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = input.isNotBlank(),
                ) { Text("Als Photon aufnehmen") }
                Text("Verarbeitet: ${runtimeState.processed} · Feldenergie: ${"%.1f".format(matrixState.totalEnergy)}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
