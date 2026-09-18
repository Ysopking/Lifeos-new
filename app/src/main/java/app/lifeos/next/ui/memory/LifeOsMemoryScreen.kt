package app.lifeos.next.ui.memory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lifeos.next.LifeOsMemoryViewModel
import app.lifeos.next.ui.components.LifeOsPill
import app.lifeos.next.ui.components.LifeOsScreenHeader
import app.lifeos.next.ui.theme.LifeOsTokens
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun LifeOsMemoryScreen(
    model: LifeOsMemoryViewModel,
    modifier: Modifier = Modifier,
) {
    val state by model.state.collectAsStateWithLifecycle()
    var selectedName by rememberSaveable { mutableStateOf(MemoryWorkspaceTab.NOW.name) }
    val selected = runCatching { MemoryWorkspaceTab.valueOf(selectedName) }
        .getOrDefault(MemoryWorkspaceTab.NOW)

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = LifeOsTokens.Layout.contentMaxWidth)
                .padding(
                    horizontal = LifeOsTokens.Spacing.large,
                    vertical = LifeOsTokens.Spacing.medium,
                ),
            verticalArrangement = Arrangement.spacedBy(LifeOsTokens.Spacing.medium),
        ) {
            LifeOsScreenHeader(
                title = "Gedächtnis",
                subtitle = "Dauerhafte Quellen, Themen und Episoden – ohne versteckte UI-Schreibzugriffe.",
                eyebrow = "Memory Fabric",
                trailing = {
                    LifeOsPill(
                        label = state.workspace.authoritativePhotonCount.toString() + " Quellen",
                    )
                },
            )

            Text(
                text = projectionStatus(state.workspace),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = state.query,
                onValueChange = model::editQuery,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Gedächtnis durchsuchen") },
                placeholder = { Text("Quelle, Thema, Tag oder Inhalt") },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
            )

            TabRow(
                selectedTabIndex = selected.ordinal,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary,
            ) {
                MemoryWorkspaceTab.entries.forEach { tab ->
                    Tab(
                        selected = tab == selected,
                        onClick = { selectedName = tab.name },
                        text = { Text(tab.label) },
                    )
                }
            }

            if (state.loading && !state.workspace.projectionAvailable && state.workspace.now.isEmpty()) {
                Row(
                    Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator()
                }
            } else {
                when (selected) {
                    MemoryWorkspaceTab.NOW -> MemoryNowList(
                        workspace = state.workspace,
                        onOpenSource = model::selectSource,
                        modifier = Modifier.weight(1f),
                    )
                    MemoryWorkspaceTab.TOPICS -> MemoryTopicsList(
                        workspace = state.workspace,
                        onOpenSource = model::selectSource,
                        modifier = Modifier.weight(1f),
                    )
                    MemoryWorkspaceTab.TIMELINE -> MemoryTimelineList(
                        workspace = state.workspace,
                        onOpenSource = model::selectSource,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            state.error?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    state.selectedSourceId?.let { sourceId ->
        MemorySourceDetailsDialog(
            sourceId = sourceId,
            source = state.selectedSource,
            loadImagePreview = model::loadImagePreview,
            onDismiss = model::dismissSourceDetails,
        )
    }
}

private fun projectionStatus(workspace: MemoryWorkspaceUiModel): String = when {
    !workspace.projectionAvailable ->
        "Langzeitprojektion noch nicht verfügbar · neue autoritative Quellen bleiben sichtbar."
    workspace.projectionEvaluatedAt != null ->
        workspace.authoritativePhotonCount.toString() +
            " autoritative Quellen · Projektion " +
            MEMORY_WORKSPACE_TIME.format(workspace.projectionEvaluatedAt)
    else -> workspace.authoritativePhotonCount.toString() + " autoritative Quellen"
}

internal val MEMORY_WORKSPACE_TIME: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault())
internal val MEMORY_WORKSPACE_DATE: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd.MM.yyyy").withZone(ZoneId.systemDefault())
