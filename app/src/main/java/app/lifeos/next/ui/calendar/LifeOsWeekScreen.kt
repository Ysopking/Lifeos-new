package app.lifeos.next.ui.calendar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lifeos.next.LifeOsGoalsViewModel
import app.lifeos.next.ui.components.LifeOsContentFrame
import app.lifeos.next.ui.goals.GoalPlanUiModel
import app.lifeos.next.ui.theme.LifeOsTokens
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

@Composable
fun LifeOsWeekScreen(
    goalsModel: LifeOsGoalsViewModel,
    modifier: Modifier = Modifier,
) {
    val state by goalsModel.state.collectAsStateWithLifecycle()
    var weekOffset by rememberSaveable { mutableIntStateOf(0) }
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val anchor = today.plusWeeks(weekOffset.toLong())
    val weekStart = anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    LaunchedEffect(Unit) {
        goalsModel.refreshProjection()
    }

    LifeOsContentFrame(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(onClick = { weekOffset -= 1 }) { Text("‹") }
            Column {
                Text(
                    text = weekTitle(weekStart),
                    style = MaterialTheme.typography.titleLarge,
                )
                if (weekOffset != 0) {
                    Text(
                        text = if (weekOffset < 0) "Vergangene Woche" else "Kommende Woche",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(onClick = { weekOffset += 1 }) { Text("›") }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(LifeOsTokens.Spacing.small),
        ) {
            items((0L..6L).map(weekStart::plusDays)) { date ->
                WeekDayCard(
                    date = date,
                    today = today,
                    zone = zone,
                    events = state.calendarEvents.filter {
                        it.start.atZone(zone).toLocalDate() == date
                    },
                    plans = state.workspace.plans,
                    onOpenPlan = goalsModel::selectPlan,
                )
            }
        }
    }
}

@Composable
private fun WeekDayCard(
    date: LocalDate,
    today: LocalDate,
    zone: ZoneId,
    events: List<CalendarEventUiModel>,
    plans: List<GoalPlanUiModel>,
    onOpenPlan: (app.lifeos.core.runtime.goal.GoalPlanId) -> Unit,
) {
    val deadlines = plans.flatMap { plan ->
        plan.steps.mapNotNull { step ->
            val deadline = step.deadline ?: return@mapNotNull null
            if (deadline.atZone(zone).toLocalDate() != date) return@mapNotNull null
            plan to step
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = if (date == today) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        tonalElevation = LifeOsTokens.Elevation.raised,
    ) {
        Column(
            modifier = Modifier.padding(LifeOsTokens.Spacing.medium),
            verticalArrangement = Arrangement.spacedBy(LifeOsTokens.Spacing.small),
        ) {
            Text(
                text = DAY_FORMAT.format(date),
                style = MaterialTheme.typography.titleMedium,
            )

            if (events.isEmpty() && deadlines.isEmpty()) {
                Text(
                    text = "Frei",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            events.forEach { event ->
                Column(verticalArrangement = Arrangement.spacedBy(LifeOsTokens.Spacing.xSmall)) {
                    Text(
                        text = if (event.allDay) {
                            event.title
                        } else {
                            "${TIME_FORMAT.format(event.start.atZone(zone))} · ${event.title}"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    event.location.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            deadlines.forEach { (plan, step) ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenPlan(plan.id) }
                        .padding(vertical = LifeOsTokens.Spacing.xSmall),
                    verticalArrangement = Arrangement.spacedBy(LifeOsTokens.Spacing.xSmall),
                ) {
                    Text(
                        text = "Projekt · ${plan.title}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = step.objective,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

private fun weekTitle(start: LocalDate): String {
    val end = start.plusDays(6)
    return if (start.month == end.month) {
        "${start.dayOfMonth}.–${end.dayOfMonth}. ${MONTH_FORMAT.format(end)}"
    } else {
        "${SHORT_DATE.format(start)} – ${SHORT_DATE.format(end)}"
    }
}

private val DAY_FORMAT = DateTimeFormatter.ofPattern("EEEE, d. MMM", Locale.GERMAN)
private val MONTH_FORMAT = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.GERMAN)
private val SHORT_DATE = DateTimeFormatter.ofPattern("d. MMM", Locale.GERMAN)
private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm", Locale.GERMAN)
