package app.lifeos.next

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lifeos.core.runtime.chat.ChatEvent
import app.lifeos.core.runtime.chat.ChatRole
import app.lifeos.next.kernel.KernelBootstrapStatus

class ChatMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val model = ViewModelProvider(this)[LifeOsChatViewModel::class.java]
        setContent { LifeOsChatScreen(model) }
    }
}

@Composable
private fun LifeOsChatScreen(model: LifeOsChatViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.safeDrawingPadding().imePadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("LIFEOS", style = MaterialTheme.typography.headlineMedium)
                Text(statusText(state.bootStatus), style = MaterialTheme.typography.labelMedium)
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (state.events.isEmpty()) item { Text("Schreib LIFEOS eine Nachricht.") }
                    items(state.events, key = { it.id }) { ChatMessage(it) }
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = state.draft,
                        onValueChange = model::editDraft,
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Nachricht an LIFEOS") },
                        enabled = !state.sending,
                    )
                    Button(
                        onClick = model::sendMessage,
                        enabled = state.draft.isNotBlank() && !state.sending &&
                            (state.bootStatus == KernelBootstrapStatus.READY || state.bootStatus == KernelBootstrapStatus.DEGRADED),
                    ) { Text(if (state.sending) "…" else "Senden") }
                }
            }
        }
    }
}

@Composable
private fun ChatMessage(event: ChatEvent) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (event.role == ChatRole.USER) Arrangement.End else Arrangement.Start,
    ) {
        Card {
            Column(Modifier.padding(12.dp)) {
                Text(if (event.role == ChatRole.USER) "Du" else "LIFEOS", style = MaterialTheme.typography.labelMedium)
                event.text?.let { Text(it) }
            }
        }
    }
}

private fun statusText(status: KernelBootstrapStatus): String = when (status) {
    KernelBootstrapStatus.CREATED -> "Start"
    KernelBootstrapStatus.LOADING -> "Gedächtnis wird geladen"
    KernelBootstrapStatus.READY -> "Bereit"
    KernelBootstrapStatus.DEGRADED -> "Eingeschränkt"
    KernelBootstrapStatus.FAILED -> "Fehler"
}
