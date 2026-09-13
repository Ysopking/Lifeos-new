package app.lifeos.next.ui.goals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun GoalDetails(
    plan: GoalPlanUiModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var evidenceExpanded by rememberSaveable(plan.id.value) { mutableStateOf(false) }
    val current = plan.currentStepId?.let { id -> plan.steps.firstOrNull { it.id == id } }
    val next = plan.nextStepId?.let { id -> plan.steps.firstOrNull { it.id == id } }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            TextButton(onClick = onBack) {
                Text("← Zur Übersicht")
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                GoalStatusBadge(plan.status)
                Text(plan.title, style = MaterialTheme.typography.headlineSmall)
                Text(
                    "${plan.completedSteps} von ${plan.totalSteps} Schritten erledigt · " +
                        "Aktualisiert ${GOALS_TIME.format(plan.lastActivityAt)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (current != null || next != null) {
            item {
                GoalFocusCard(
                    label = if (current != null) "Aktueller Schritt" else "Nächster Schritt",
                    step = current ?: requireNotNull(next),
                )
            }
        }
        item {
            GoalStepTimeline(plan)
        }
        item {
            TechnicalGoalEvidence(
                plan = plan,
                expanded = evidenceExpanded,
                onToggle = { evidenceExpanded = !evidenceExpanded },
            )
        }
    }
}

@Composable
private fun GoalFocusCard(
    label: String,
    step: GoalStepUiModel,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(displayStepTitle(step), style = MaterialTheme.typography.titleMedium)
            Text(
                goalStepStateLabel(step.state),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (step.expired) {
                Text(
                    "Dieser Schritt ist überfällig.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun TechnicalGoalEvidence(
    plan: GoalPlanUiModel,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Technische Evidenz", style = MaterialTheme.typography.titleMedium)
            Text(
                "IDs und Laufzeitdetails für nachvollziehbare Diagnose.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onToggle) {
                Text(if (expanded) "Ausblenden" else "Anzeigen")
            }
            if (expanded) {
                EvidenceLine("Plan", plan.id.value)
                EvidenceLine("Revision", plan.revision.toString())
                EvidenceLine("Quell-Photon", plan.sourceGoalPhotonId.value)
                EvidenceLine("Quell-Revision", plan.sourceGoalPhotonRevision.toString())
                plan.steps.forEach { step ->
                    Text(
                        displayStepTitle(step),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    EvidenceLine("Key", step.key)
                    EvidenceLine("Schritt-ID", step.id.value)
                    step.activeActionId?.let { EvidenceLine("Action-ID", it) }
                    step.outcomePhotonId?.let { EvidenceLine("Outcome-Photon", it.value) }
                }
            }
        }
    }
}

@Composable
private fun EvidenceLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
