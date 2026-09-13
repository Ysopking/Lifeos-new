package app.lifeos.next.ui.memory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lifeos.core.model.PhotonId

@Composable
internal fun MemoryNowList(
    workspace: MemoryWorkspaceUiModel,
    onOpenSource: (PhotonId) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (workspace.now.isEmpty()) {
        MemoryEmptyState(if (workspace.query.isBlank()) "Keine aktiven oder neuen Gedächtnisquellen." else "Keine Treffer in Jetzt.", modifier)
        return
    }
    LazyColumn(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(workspace.now, key = { it.photonId.value }) { source ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            when {
                                source.isNew -> "Neu"
                                source.stage != null -> source.stage.germanLabel()
                                else -> "Nicht projiziert"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(MEMORY_WORKSPACE_TIME.format(source.createdAt), style = MaterialTheme.typography.labelSmall)
                    }
                    Text(
                        if (source.isImage) "Lokales LIFEOS-Bild" else source.content,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(onClick = { onOpenSource(source.photonId) }) {
                        Text(if (source.isImage) "Bild & Quelle öffnen" else "Quelle öffnen")
                    }
                }
            }
        }
    }
}

@Composable
internal fun MemoryTopicsList(
    workspace: MemoryWorkspaceUiModel,
    onOpenSource: (PhotonId) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (workspace.topicGroups.isEmpty() && workspace.crystals.isEmpty()) {
        MemoryEmptyState(if (workspace.query.isBlank()) "Noch keine kompakten Themen oder Kristalle." else "Keine Treffer in Themen.", modifier)
        return
    }
    LazyColumn(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (workspace.crystals.isNotEmpty()) {
            item { Text("Kristallisierte Kerne", style = MaterialTheme.typography.titleMedium) }
            items(workspace.crystals, key = { it.crystalId }) { crystal ->
                MemoryCrystalCard(crystal, onOpenSource)
            }
            item { HorizontalDivider() }
        }
        workspace.topicGroups.forEach { group ->
            item(key = "topic:${group.kind.name}") {
                Text(group.label, style = MaterialTheme.typography.titleMedium)
            }
            items(group.atoms, key = { it.atomId }) { atom ->
                MemoryAtomCard(atom, onOpenSource)
            }
        }
    }
}

@Composable
internal fun MemoryTimelineList(
    workspace: MemoryWorkspaceUiModel,
    onOpenSource: (PhotonId) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (workspace.episodes.isEmpty()) {
        MemoryEmptyState(if (workspace.query.isBlank()) "Noch keine Gedächtnisepisoden." else "Keine Treffer in Timeline.", modifier)
        return
    }
    LazyColumn(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(workspace.episodes, key = { it.episodeId }) { episode ->
            MemoryEpisodeCard(episode, onOpenSource)
        }
    }
}

@Composable
private fun MemoryAtomCard(atom: MemoryAtomUi, onOpenSource: (PhotonId) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${atom.stage.germanLabel()} · ${MEMORY_WORKSPACE_TIME.format(atom.observedAt)}", style = MaterialTheme.typography.labelMedium)
            Text(atom.content)
            MemorySourceLinks(atom.sourcePhotonIds, atom.resolvedSourceCount, atom.missingSourceCount, onOpenSource)
        }
    }
}

@Composable
private fun MemoryCrystalCard(crystal: MemoryCrystalUi, onOpenSource: (PhotonId) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Kristallisiert", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            Text(crystal.semanticCore)
            Text("${MEMORY_WORKSPACE_DATE.format(crystal.startedAt)} – ${MEMORY_WORKSPACE_DATE.format(crystal.endedAt)}", style = MaterialTheme.typography.labelSmall)
            MemorySourceLinks(crystal.sourcePhotonIds, crystal.resolvedSourceCount, crystal.missingSourceCount, onOpenSource)
        }
    }
}

@Composable
private fun MemoryEpisodeCard(episode: MemoryEpisodeUi, onOpenSource: (PhotonId) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${episode.stage.germanLabel()} · ${MEMORY_WORKSPACE_DATE.format(episode.startedAt)}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            Text(if (episode.semanticKeys.isEmpty()) "Episode ohne semantische Schlüssel" else episode.semanticKeys.joinToString(" · "))
            MemorySourceLinks(episode.sourcePhotonIds, episode.resolvedSourceCount, episode.missingSourceCount, onOpenSource)
        }
    }
}

@Composable
private fun MemorySourceLinks(
    sourceIds: Set<PhotonId>,
    resolved: Int,
    missing: Int,
    onOpenSource: (PhotonId) -> Unit,
) {
    Text("Quellen: $resolved${if (missing > 0) " · fehlend: $missing" else ""}", style = MaterialTheme.typography.labelSmall)
    sourceIds.take(3).forEach { id ->
        TextButton(onClick = { onOpenSource(id) }) {
            Text(id.value, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    if (sourceIds.size > 3) Text("+ ${sourceIds.size - 3} weitere Quellen", style = MaterialTheme.typography.labelSmall)
}

@Composable
private fun MemoryEmptyState(text: String, modifier: Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
