package app.lifeos.next.ui.actions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lifeos.next.LifeOsChatViewModel
import app.lifeos.next.LifeOsGoalsViewModel
import app.lifeos.next.ui.components.LifeOsContentFrame
import app.lifeos.next.ui.theme.LifeOsTokens

@Composable
fun LifeOsActionCenterScreen(
    chatModel: LifeOsChatViewModel,
    goalsModel: LifeOsGoalsViewModel,
    onSubmitPrompt: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val chat by chatModel.state.collectAsStateWithLifecycle()
    val goals by goalsModel.state.collectAsStateWithLifecycle()
    val projected = ActionCenterProjector.project(goals.workspace)

    LifeOsContentFrame(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(LifeOsTokens.Spacing.large),
        ) {
            item {
                Column(
                    verticalArrangement = Arrangement.spacedBy(LifeOsTokens.Spacing.xSmall),
                    modifier = Modifier.padding(top = LifeOsTokens.Spacing.small),
                ) {
                    Text("Rückfragen & Aktionen", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "Nur Dinge, bei denen deine Entscheidung oder ein konkreter Start sinnvoll ist.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (chat.clarificationOptions.isNotEmpty()) {
                item {
                    SectionTitle(
                        title = "Rückfragen",
                        subtitle = "LIFEOS braucht genau hier deine Auswahl.",
                    )
                }
                chat.clarificationOptions.forEachIndexed { index, option ->
                    item(key = "clarification:$index:$option") {
                        ActionSurface(
                            title = option,
                            body = "Antwort direkt an den laufenden Chat übergeben.",
                            actionLabel = "Antworten",
                            primary = true,
                            onClick = { onSubmitPrompt(option) },
                        )
                    }
                }
            }

            if (projected.ready.isNotEmpty()) {
                item {
                    SectionTitle(
                        title = "Jetzt anstoßen",
                        subtitle = "Bereite Owner-Schritte aus deinen Projekten.",
                    )
                }
                projected.ready.forEach { item ->
                    item(key = "ready:${item.stepId.value}") {
                        ActionSurface(
                            title = item.projectTitle,
                            body = item.objective,
                            actionLabel = "Anstoßen",
                            primary = true,
                            onClick = { onSubmitPrompt(item.prompt) },
                        )
                    }
                }
            }

            if (projected.needsOwner.isNotEmpty()) {
                item {
                    SectionTitle(
                        title = "Braucht dich",
                        subtitle = "Blockaden oder fehlende Entscheidungen gezielt klären.",
                    )
                }
                projected.needsOwner.forEach { item ->
                    item(key = "owner:${item.stepId.value}") {
                        ActionSurface(
                            title = item.projectTitle,
                            body = item.objective,
                            actionLabel = "Klären",
                            primary = false,
                            onClick = { onSubmitPrompt(item.prompt) },
                        )
                    }
                }
            }

            if (
                chat.clarificationOptions.isEmpty() &&
                projected.ready.isEmpty() &&
                projected.needsOwner.isEmpty()
            ) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = LifeOsTokens.Spacing.xxLarge),
                        verticalArrangement = Arrangement.spacedBy(LifeOsTokens.Spacing.small),
                    ) {
                        Text("Alles geklärt", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Aktuell wartet keine Rückfrage und kein Owner-Schritt auf dich.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(
    title: String,
    subtitle: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(LifeOsTokens.Spacing.xSmall)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ActionSurface(
    title: String,
    body: String,
    actionLabel: String,
    primary: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(LifeOsTokens.Spacing.medium),
            verticalArrangement = Arrangement.spacedBy(LifeOsTokens.Spacing.small),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium)
            if (primary) {
                Button(onClick = onClick) { Text(actionLabel) }
            } else {
                OutlinedButton(onClick = onClick) { Text(actionLabel) }
            }
        }
    }
}
