package app.lifeos.next.ui.goals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.lifeos.core.runtime.goal.GoalStepState
import app.lifeos.next.ui.theme.LifeOsTokens
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun TodayActionRow(
    item: TodayPlanItem,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val attention = item.requiresOwnerAttention()
    Surface(
        onClick = onOpen,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = if (attention) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        tonalElevation = if (attention) {
            LifeOsTokens.Elevation.resting
        } else {
            0.dp
        },
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = LifeOsTokens.Spacing.large,
                vertical = LifeOsTokens.Spacing.medium,
            ),
            verticalArrangement = Arrangement.spacedBy(LifeOsTokens.Spacing.xSmall),
        ) {
            Text(
                text = todayTimingLabel(item),
                style = MaterialTheme.typography.labelLarge,
                color = if (attention) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
            Text(
                text = item.objective,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = item.planTitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            todayStateLabel(item)
                .takeIf(String::isNotBlank)
                ?.let { stateLabel ->
                    Text(
                        text = stateLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
        }
    }
}

internal fun TodayPlanItem.requiresOwnerAttention(): Boolean =
    blockedByDependencies ||
        state == GoalStepState.WAITING_EVIDENCE ||
        state == GoalStepState.WAITING_CAPABILITY ||
        state == GoalStepState.PAUSED ||
        state == GoalStepState.BLOCKED

internal fun todayTimingLabel(item: TodayPlanItem): String = when (item.timing) {
    TodayPlanTiming.OVERDUE ->
        item.deadline?.let { "Überfällig · ${GOALS_TIME.format(it)}" } ?: "Überfällig"
    TodayPlanTiming.NOW -> "Jetzt"
    TodayPlanTiming.TODAY ->
        item.deadline?.let { "Heute · ${GOALS_CLOCK.format(it)}" } ?: "Heute"
    TodayPlanTiming.UNSCHEDULED -> "Bereit · kein fester Termin"
}

internal fun todayStateLabel(item: TodayPlanItem): String = when {
    item.blockedByDependencies -> "Wartet auf Voraussetzungen"
    item.state == GoalStepState.WAITING_EVIDENCE -> "Wartet auf Evidenz"
    item.state == GoalStepState.WAITING_CAPABILITY -> "Wartet auf Fähigkeit"
    item.state == GoalStepState.PAUSED -> "Pausiert"
    item.state == GoalStepState.BLOCKED -> "Blockiert"
    item.state == GoalStepState.RUNNING -> "Läuft"
    item.state == GoalStepState.READY -> "Bereit"
    else -> ""
}

private val GOALS_CLOCK: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

internal val GOALS_TIME: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault())
