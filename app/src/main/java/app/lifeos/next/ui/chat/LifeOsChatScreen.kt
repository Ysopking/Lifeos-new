package app.lifeos.next.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lifeos.core.runtime.chat.ChatEvent
import app.lifeos.core.runtime.chat.ChatRole
import app.lifeos.next.LifeOsChatViewModel
import app.lifeos.next.LifeOsReadinessCard
import app.lifeos.next.kernel.KernelBootstrapStatus

@Composable
fun LifeOsChatScreen(
    model: LifeOsChatViewModel,
    modifier: Modifier = Modifier,
) {
    val state by model.state.collectAsStateWithLifecycle()
    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("LIFEOS", style = MaterialTheme.typography.headlineMedium)
        Text(statusText(state.bootStatus), style = MaterialTheme.typography.labelMedium)
        if (state.registeredSubsystems > 0) {
            Text(
                "${state.registeredSubsystems} Subsysteme · ${state.capabilityProviders} Provider · " +
                    "${state.unavailableSubsystems} nicht verfügbar · ${state.generatedProviders} generiert",
                style = MaterialTheme.typography.labelSmall,
            )
        }
        state.readiness?.let { readiness ->
            LifeOsReadinessCard(readiness)
        }
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
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
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
                    (state.bootStatus == KernelBootstrapStatus.READY ||
                        state.bootStatus == KernelBootstrapStatus.DEGRADED),
            ) {
                Text(if (state.sending) "…" else "Senden")
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

private fun statusText(status: KernelBootstrapStatus): String = when (status) {
    KernelBootstrapStatus.CREATED -> "Start"
    KernelBootstrapStatus.LOADING -> "Gedächtnis wird geladen"
    KernelBootstrapStatus.READY -> "Bereit"
    KernelBootstrapStatus.DEGRADED -> "Eingeschränkt"
    KernelBootstrapStatus.FAILED -> "Fehler"
}
