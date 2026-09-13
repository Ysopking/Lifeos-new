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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.lifeos.core.runtime.goal.GoalStepState

@Composable
internal fun GoalStepTimeline(
    plan: GoalPlanUiModel,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Ablauf", style = MaterialTheme.typography.titleMedium)
        plan.steps.forEachIndexed { index, step ->
            GoalStepRow(
                index = index,
                step = step,
                allSteps = plan.steps,
            )
        }
    }
}

@Composable
private fun GoalStepRow(
    index: Int,
    step: GoalStepUiModel,
    allSteps: List<GoalStepUiModel>,
) {
    val unmetTitles = step.unmetDependencyIds.mapNotNull { dependencyId ->
        allSteps.firstOrNull { it.id == dependencyId }?.let(::displayStepTitle)
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Schritt ${index + 1}", style = MaterialTheme.typography.labelMedium)
                GoalStepStateBadge(step.state)
            }
            Text(displayStepTitle(step), style = MaterialTheme.typography.titleSmall)
            if (step.presentationKind == GoalStepPresentationKind.INTERNAL_VERIFICATION) {
                Text(
                    step.objective,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (unmetTitles.isNotEmpty()) {
                Text(
                    "Wartet auf: ${unmetTitles.joinToString()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            step.deadline?.let { deadline ->
                Text(
                    if (step.expired) {
                        "Frist überschritten: ${GOALS_TIME.format(deadline)}"
                    } else {
                        "Frist: ${GOALS_TIME.format(deadline)}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (step.expired) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            if (step.outcomePhotonId != null) {
                Text(
                    "Ergebnis vorhanden",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun GoalStepStateBadge(state: GoalStepState) {
    Surface(
        shape = MaterialTheme.shapes.small,
        tonalElevation = 1.dp,
    ) {
        Text(
            text = goalStepStateLabel(state),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

internal fun goalStepStateLabel(state: GoalStepState): String = when (state) {
    GoalStepState.PLANNED -> "Geplant"
    GoalStepState.READY -> "Bereit"
    GoalStepState.RUNNING -> "In Arbeit"
    GoalStepState.BLOCKED -> "Blockiert"
    GoalStepState.WAITING_EVIDENCE -> "Wartet auf Evidenz"
    GoalStepState.WAITING_CAPABILITY -> "Wartet auf Fähigkeit"
    GoalStepState.PAUSED -> "Pausiert"
    GoalStepState.COMPLETED -> "Erledigt"
    GoalStepState.CANCELLED -> "Abgebrochen"
    GoalStepState.FAILED -> "Fehlgeschlagen"
    GoalStepState.REPLAN_REQUIRED -> "Neuplanung nötig"
}
