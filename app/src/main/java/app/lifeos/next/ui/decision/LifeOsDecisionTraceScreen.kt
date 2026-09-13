package app.lifeos.next.ui.decision

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lifeos.next.LifeOsDecisionTraceViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LifeOsDecisionTraceScreen(
    model: LifeOsDecisionTraceViewModel,
    modifier: Modifier = Modifier,
) {
    val state by model.state.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = modifier.padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text("Warum?", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "Nachvollziehbare Entscheidungen aus dem dauerhaften LIFEOS-Trace.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                TextButton(onClick = model::refresh) {
                    Text("Aktualisieren")
                }
            }
        }

        if (state.loading && state.workspace.traces.isEmpty()) {
            item { CircularProgressIndicator() }
        }

        state.error?.let { error ->
            item {
                Text(
                    "DecisionTrace konnte nicht gelesen werden: $error",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        if (!state.loading && state.error == null && state.workspace.traces.isEmpty()) {
            item {
                Text(
                    "Noch keine dauerhaften Entscheidungs-Traces vorhanden.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        if (state.workspace.traces.isNotEmpty()) {
            item {
                Text(
                    "${state.workspace.traces.size} Traces · ${state.workspace.unresolvedCount} mit offener Unsicherheit",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            items(
                items = state.workspace.traces,
                key = { it.traceId.value },
            ) { trace ->
                DecisionTraceOverviewCard(
                    trace = trace,
                    onClick = { model.selectTrace(trace.traceId) },
                )
            }
            item { Text("", modifier = Modifier.padding(bottom = 12.dp)) }
        }
    }

    state.selectedTrace?.let { trace ->
        ModalBottomSheet(onDismissRequest = model::dismissDetails) {
            DecisionTraceDetails(trace)
        }
    }
}

@Composable
private fun DecisionTraceOverviewCard(
    trace: DecisionTraceUiModel,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(trace.title, style = MaterialTheme.typography.titleMedium)
                Text(trace.kind.label(), style = MaterialTheme.typography.labelMedium)
            }
            Text(trace.summary, style = MaterialTheme.typography.bodyMedium)
            Text(
                "Revision ${trace.revision} · ${trace.nodeCount} Knoten · ${trace.links.size} Beziehungen",
                style = MaterialTheme.typography.bodySmall,
            )
            trace.lastRecordedAt?.let { recordedAt ->
                Text("Zuletzt: $recordedAt", style = MaterialTheme.typography.bodySmall)
            }
            if (trace.unresolved) {
                Text("Offene Unsicherheit", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun DecisionTraceDetails(trace: DecisionTraceUiModel) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(trace.title, style = MaterialTheme.typography.headlineSmall)
        Text(trace.summary, style = MaterialTheme.typography.bodyMedium)
        Text(
            "Trace ${trace.traceId.value} · Revision ${trace.revision}",
            style = MaterialTheme.typography.bodySmall,
        )

        DecisionTraceNodeSection("Fakten", trace.facts)
        DecisionTraceNodeSection("Bedingungen", trace.constraints)
        DecisionTraceNodeSection("Alternativen & Auswahl", trace.alternatives)
        DecisionTraceNodeSection("Ergebnisse", trace.outcomes)
        DecisionTraceNodeSection("Offene Unsicherheiten", trace.uncertainties)

        if (trace.links.isNotEmpty()) {
            Text("Beziehungen", style = MaterialTheme.typography.titleMedium)
            trace.links.forEach { link ->
                Text(
                    "${link.type.name}: ${link.from.value} → ${link.to.value}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Text(
            "Diese Ansicht ist read-only. Sie zeigt persistierte DecisionTrace-Evidenz und erteilt keine produktive Autorität.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 24.dp),
        )
    }
}

@Composable
private fun DecisionTraceNodeSection(
    title: String,
    nodes: List<DecisionTraceNodeUiModel>,
) {
    if (nodes.isEmpty()) return
    Text(title, style = MaterialTheme.typography.titleMedium)
    nodes.forEach { node ->
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(node.label, style = MaterialTheme.typography.titleSmall)
                Text(
                    "${node.type.name} · ${node.sourceType} · Revision ${node.sourceRevision}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(node.recordedAt.toString(), style = MaterialTheme.typography.bodySmall)
                node.reasons.forEach { reason ->
                    Text(reason.summary, style = MaterialTheme.typography.bodyMedium)
                    if (reason.summary != reason.raw) {
                        Text(reason.raw, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text(
                    "Quelle: ${node.sourceId}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun DecisionTraceKind.label(): String = when (this) {
    DecisionTraceKind.GOAL -> "Ziel"
    DecisionTraceKind.SELF_HEALING -> "Self-Healing"
    DecisionTraceKind.EVOLUTION -> "Evolution"
    DecisionTraceKind.ARTIFACT -> "Artefakt"
    DecisionTraceKind.SYSTEM -> "System"
}
