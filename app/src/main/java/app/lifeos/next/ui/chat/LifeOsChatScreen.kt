package app.lifeos.next.ui.chat

import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lifeos.core.runtime.chat.ChatEvent
import app.lifeos.core.runtime.chat.ChatRole
import app.lifeos.next.LifeOsChatViewModel
import app.lifeos.next.ui.components.LifeOsRuntimeStatus
import app.lifeos.next.ui.components.buildRuntimeHealthUiModel
import app.lifeos.next.ui.system.RuntimeHealthModalSheet

@Composable
fun LifeOsChatScreen(
    model: LifeOsChatViewModel,
    modifier: Modifier = Modifier,
) {
    val state by model.state.collectAsStateWithLifecycle()
    var showRuntimeHealth by rememberSaveable { mutableStateOf(false) }
    val runtimeHealth = buildRuntimeHealthUiModel(
        bootStatus = state.bootStatus,
        readiness = state.readiness,
        topologyEvidence = state.runtimeTopology,
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("LIFEOS", style = MaterialTheme.typography.headlineMedium)
        LifeOsRuntimeStatus(
            model = runtimeHealth,
            onOpenDetails = { showRuntimeHealth = true },
        )
        LazyColumn(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.events.isEmpty()) item { Text("Schreib LIFEOS eine Nachricht.") }
            items(state.events, key = { it.id }) { ChatMessage(it) }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        ChatComposer(
            draft = state.draft,
            bootStatus = state.bootStatus,
            processing = state.turnProcessing,
            onDraftChange = model::editDraft,
            onSend = model::sendMessage,
        )
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
private fun ChatMessage(event: ChatEvent) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (event.role == ChatRole.USER) Arrangement.End else Arrangement.Start,
    ) {
        Card {
            Column(Modifier.padding(12.dp)) {
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
