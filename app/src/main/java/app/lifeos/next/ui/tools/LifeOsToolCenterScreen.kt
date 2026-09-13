package app.lifeos.next.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lifeos.next.LifeOsToolCenterUiState
import app.lifeos.next.LifeOsToolCenterViewModel

@Composable
fun LifeOsToolCenterScreen(
    model: LifeOsToolCenterViewModel,
    modifier: Modifier = Modifier,
) {
    val state by model.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        model.refresh()
    }

    ToolCenterOverview(
        state = state,
        onRefresh = model::refresh,
        modifier = modifier,
    )
}

@Composable
private fun ToolCenterOverview(
    state: LifeOsToolCenterUiState,
    onRefresh: () -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("Tool Center", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Read-only Sicht auf Fähigkeiten, Provider und generierte Tools der produktiven Runtime.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = onRefresh, enabled = !state.loading) {
                Text("Aktualisieren")
            }
        }

        state.error?.let { error ->
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        val workspace = state.workspace
        if (workspace == null && state.loading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            return@Column
        }

        if (workspace == null) {
            Text(
                "Der Runtime-Snapshot konnte nicht geladen werden.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "summary") {
                ToolCenterSummaryCard(workspace.summary)
            }

            item(key = "generated-title") {
                SectionTitle(
                    title = "Generierte Tools",
                    subtitle = "Lifecycle-, Verifikations- und Evidence-Status ohne Mutationsrechte.",
                )
            }

            if (workspace.generatedTools.isEmpty()) {
                item(key = "generated-empty") {
                    EmptySection("Aktuell sind keine generierten Tools im produktiven Registry registriert.")
                }
            } else {
                items(
                    items = workspace.generatedTools,
                    key = { it.toolId },
                ) { tool ->
                    GeneratedToolCard(tool)
                }
            }

            item(key = "providers-divider") { HorizontalDivider() }
            item(key = "providers-title") {
                SectionTitle(
                    title = "Capability Provider",
                    subtitle = "Auch deaktivierte und quarantänisierte Provider bleiben für Diagnose sichtbar.",
                )
            }

            if (workspace.providers.isEmpty()) {
                item(key = "providers-empty") {
                    EmptySection("Aktuell sind keine Capability Provider sichtbar.")
                }
            } else {
                items(
                    items = workspace.providers,
                    key = { "${it.capabilityId}:${it.providerId}" },
                ) { provider ->
                    ProviderCard(provider)
                }
            }
        }
    }
}

@Composable
private fun ToolCenterSummaryCard(summary: ToolCenterSummaryUiModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = summary.runtimeLabel,
                style = MaterialTheme.typography.titleMedium,
                color = toneColor(summary.runtimeTone),
            )
            Text(
                "${summary.capabilityCount} Fähigkeiten · ${summary.providerCount} Provider · ${summary.generatedToolCount} generierte Tools",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Aktiv ${summary.activeToolCount} · Testlauf ${summary.trialToolCount} · Aufmerksamkeit ${summary.attentionToolCount}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GeneratedToolCard(tool: ToolCenterGeneratedToolUiModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(tool.toolId, style = MaterialTheme.typography.titleSmall)
                Text(
                    tool.stateLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = toneColor(tool.tone),
                )
            }
            Text(
                "Fähigkeit: ${tool.capabilityId} · Verifikation: ${tool.verificationPercent}%",
                style = MaterialTheme.typography.bodySmall,
            )
            DetailLine("Berechtigungen", tool.permissions)
            DetailLine("Inputs", tool.requiredInputs)
            DetailLine("Outputs", tool.requiredOutputs)
            tool.promotionEvidenceId?.let {
                Text("Promotion-Evidence: $it", style = MaterialTheme.typography.bodySmall)
            }
            tool.lastMessage?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ProviderCard(provider: ToolCenterProviderUiModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(provider.capabilityId, style = MaterialTheme.typography.titleSmall)
                Text(
                    provider.stateLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = toneColor(provider.tone),
                )
            }
            Text(
                "${provider.providerId} · ${provider.providerTypeLabel}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Trust ${provider.trustLabel} · Reliability ${provider.reliabilityPercent}%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            DetailLine("Inputs", provider.requiredInputs)
            DetailLine("Outputs", provider.outputs)
        }
    }
}

@Composable
private fun DetailLine(label: String, values: List<String>) {
    Text(
        "$label: ${values.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "—"}",
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptySection(message: String) {
    Text(
        message,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun toneColor(tone: ToolCenterTone): Color = when (tone) {
    ToolCenterTone.POSITIVE -> MaterialTheme.colorScheme.primary
    ToolCenterTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    ToolCenterTone.WARNING -> MaterialTheme.colorScheme.tertiary
    ToolCenterTone.NEGATIVE -> MaterialTheme.colorScheme.error
    ToolCenterTone.MUTED -> MaterialTheme.colorScheme.outline
}
