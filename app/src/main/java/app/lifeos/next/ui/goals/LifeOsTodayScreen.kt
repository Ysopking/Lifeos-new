package app.lifeos.next.ui.goals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.lifeos.core.runtime.goal.GoalPlanId
import app.lifeos.next.LifeOsGoalsUiState
import app.lifeos.next.ui.components.LifeOsContentFrame
import app.lifeos.next.ui.theme.LifeOsTokens
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun LifeOsTodayScreen(
    state: LifeOsGoalsUiState,
    onOpenPlan: (GoalPlanId) -> Unit,
    onShowAllGoals: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val today = state.today

    LifeOsContentFrame(modifier = modifier.fillMaxSize()) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = today?.date?.format(TODAY_DATE).orEmpty(),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onShowAllGoals) {
                Text("Alle Ziele")
            }
        }

        state.error?.let { error ->
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        when {
            state.loading && today == null -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            today == null || today.items.isEmpty() -> Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "Für heute ist nichts fest eingeplant.",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "Bereite Schritte und echte Deadlines aus deinen LIFEOS-Zielen erscheinen hier automatisch.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> {
                Text(
                    text = todaySummary(today),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                val attention = today.items.filter(TodayPlanItem::requiresOwnerAttention)
                val now = today.items.filterNot(TodayPlanItem::requiresOwnerAttention)
                    .filter {
                        it.timing == TodayPlanTiming.NOW ||
                            it.timing == TodayPlanTiming.OVERDUE
                    }
                val later = today.items.filterNot(TodayPlanItem::requiresOwnerAttention)
                    .filter {
                        it.timing == TodayPlanTiming.TODAY ||
                            it.timing == TodayPlanTiming.UNSCHEDULED
                    }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(LifeOsTokens.Spacing.small),
                ) {
                    section(
                        title = "Jetzt",
                        sectionItems = now,
                        onOpenPlan = onOpenPlan,
                    )
                    section(
                        title = "Später",
                        sectionItems = later,
                        onOpenPlan = onOpenPlan,
                    )
                    section(
                        title = "Braucht dich",
                        sectionItems = attention,
                        onOpenPlan = onOpenPlan,
                    )
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(
    title: String,
    sectionItems: List<TodayPlanItem>,
    onOpenPlan: (GoalPlanId) -> Unit,
) {
    if (sectionItems.isEmpty()) return
    item(key = "section:$title") {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    items(
        items = sectionItems,
        key = { "${it.planId.value}:${it.stepId.value}" },
    ) { item ->
        TodayActionRow(
            item = item,
            onOpen = { onOpenPlan(item.planId) },
        )
    }
}

private fun todaySummary(today: TodayPlanUiModel): String = buildString {
    append("${today.items.size} Einträge")
    if (today.actionableCount > 0) append(" · ${today.actionableCount} direkt machbar")
    if (today.overdueCount > 0) append(" · ${today.overdueCount} überfällig")
}

private val TODAY_DATE: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEEE, d. MMMM", Locale.GERMAN)
