package app.lifeos.next.ui.goals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun GoalOverviewCard(
    plan: GoalPlanUiModel,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val current = plan.currentStepId?.let { id -> plan.steps.firstOrNull { it.id == id } }
    val next = plan.nextStepId?.let { id -> plan.steps.firstOrNull { it.id == id } }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                GoalStatusBadge(plan.status)
                Text(
                    "${plan.completedSteps} von ${plan.totalSteps} erledigt",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(plan.title, style = MaterialTheme.typography.titleMedium)
            when {
                current != null -> GoalWorkHint("Aktuell", displayStepTitle(current))
                next != null -> GoalWorkHint("Als Nächstes", displayStepTitle(next))
            }
            if (plan.steps.any { it.expired }) {
                Text(
                    "Mindestens ein Schritt ist überfällig.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                "Letzte Aktivität ${GOALS_TIME.format(plan.lastActivityAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onOpen) {
                Text("Details ansehen")
            }
        }
    }
}

@Composable
internal fun GoalStatusBadge(status: GoalPlanUiStatus) {
    Surface(
        shape = MaterialTheme.shapes.small,
        tonalElevation = 1.dp,
    ) {
        Text(
            text = goalPlanStatusLabel(status),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun GoalWorkHint(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

internal fun displayStepTitle(step: GoalStepUiModel): String = when (step.presentationKind) {
    GoalStepPresentationKind.INTERNAL_VERIFICATION -> "Ergebnis prüfen"
    GoalStepPresentationKind.OWNER_ACTION,
    GoalStepPresentationKind.OTHER -> step.objective
}

internal fun goalPlanStatusLabel(status: GoalPlanUiStatus): String = when (status) {
    GoalPlanUiStatus.ACTIVE -> "Aktiv"
    GoalPlanUiStatus.REPLAN_REQUIRED -> "Neuplanung nötig"
    GoalPlanUiStatus.FAILED -> "Fehlgeschlagen"
    GoalPlanUiStatus.BLOCKED -> "Blockiert"
    GoalPlanUiStatus.WAITING -> "Wartet"
    GoalPlanUiStatus.PLANNED -> "Geplant"
    GoalPlanUiStatus.COMPLETED -> "Abgeschlossen"
    GoalPlanUiStatus.CANCELLED -> "Abgebrochen"
}
