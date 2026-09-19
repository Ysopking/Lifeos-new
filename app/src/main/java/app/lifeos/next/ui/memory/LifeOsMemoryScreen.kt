package app.lifeos.next.ui.memory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lifeos.next.LifeOsMemoryViewModel
import app.lifeos.next.ui.components.LifeOsContentFrame
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
    var viewMenuOpen by rememberSaveable { mutableStateOf(false) }
    val selected = runCatching { MemoryWorkspaceTab.valueOf(selectedName) }
        .getOrDefault(MemoryWorkspaceTab.NOW)

    LifeOsContentFrame(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LifeOsTokens.Spacing.small),
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = model::editQuery,
                modifier = Modifier.weight(1f),
                label = { Text("Gedächtnis durchsuchen") },
                placeholder = { Text("Begriff, Quelle oder Thema") },
                singleLine = true,
            )
            Box {
                TextButton(onClick = { viewMenuOpen = true }) {
                    Text("Ansicht")
                }
                DropdownMenu(
                    expanded = viewMenuOpen,
                    onDismissRequest = { viewMenuOpen = false },
                ) {
                    MemoryWorkspaceTab.entries.forEach { tab ->
                        DropdownMenuItem(
                            text = { Text(tab.menuLabel()) },
                            onClick = {
                                selectedName = tab.name
                                viewMenuOpen = false
                            },
                        )
                    }
                }
            }
        }

        Text(
            text = projectionStatus(state.workspace),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (state.loading && !state.workspace.projectionAvailable && state.workspace.now.isEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            when (selected) {
                MemoryWorkspaceTab.NOW -> MemoryNowList(
                    workspace = state.workspace,
                    onOpenSource = model::selectSource,
                    onLoadMore = model::loadMoreNow,
                    modifier = Modifier.weight(1f),
                )
                MemoryWorkspaceTab.TOPICS -> MemoryTopicsList(
                    workspace = state.workspace,
                    onOpenSource = model::selectSource,
                    onLoadMore = model::loadMoreTopics,
                    modifier = Modifier.weight(1f),
                )
                MemoryWorkspaceTab.TIMELINE -> MemoryTimelineList(
                    workspace = state.workspace,
                    onOpenSource = model::selectSource,
                    onLoadMore = model::loadMoreTimeline,
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

    state.selectedSourceId?.let { sourceId ->
        MemorySourceDetailsDialog(
            sourceId = sourceId,
            source = state.selectedSource,
            loadImagePreview = model::loadImagePreview,
            onDismiss = model::dismissSourceDetails,
        )
    }
}

private fun MemoryWorkspaceTab.menuLabel(): String = when (this) {
    MemoryWorkspaceTab.NOW -> "Zuletzt"
    MemoryWorkspaceTab.TOPICS -> "Themen"
    MemoryWorkspaceTab.TIMELINE -> "Zeitverlauf"
}

private fun projectionStatus(workspace: MemoryWorkspaceUiModel): String = when {
    !workspace.projectionAvailable ->
        "Langzeitprojektion noch nicht verfügbar · neue autoritative Quellen bleiben sichtbar."
    workspace.projectionEvaluatedAt != null ->
        "${workspace.authoritativePhotonCount} autoritative Quellen · Projektion ${MEMORY_WORKSPACE_TIME.format(workspace.projectionEvaluatedAt)}"
    else -> "${workspace.authoritativePhotonCount} autoritative Quellen"
}

internal val MEMORY_WORKSPACE_TIME: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault())
internal val MEMORY_WORKSPACE_DATE: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd.MM.yyyy").withZone(ZoneId.systemDefault())
