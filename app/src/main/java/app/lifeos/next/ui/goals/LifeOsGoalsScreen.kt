package app.lifeos.next.ui.goals

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lifeos.core.runtime.goal.GoalPlanId
import app.lifeos.core.runtime.goal.GoalStepState
import app.lifeos.next.GoalWorkspaceFilter
import app.lifeos.next.LifeOsGoalsUiState
import app.lifeos.next.LifeOsGoalsViewModel
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun LifeOsGoalsScreen(
    model: LifeOsGoalsViewModel,
    modifier: Modifier = Modifier,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val selectedPlan = state.selectedPlan
    var showAllGoals by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        model.refreshProjection()
    }

    BackHandler(enabled = selectedPlan != null) {
        model.dismissDetails()
    }
    BackHandler(enabled = selectedPlan == null && showAllGoals) {
        showAllGoals = false
    }

    when {
        selectedPlan != null -> GoalDetails(
            plan = selectedPlan,
            onBack = model::dismissDetails,
            modifier = modifier,
        )
        showAllGoals -> GoalOverview(
            state = state,
            onSelectFilter = model::selectFilter,
            onOpenPlan = { model.selectPlan(it.id) },
            onShowToday = { showAllGoals = false },
            modifier = modifier,
        )
        else -> TodayOverview(
            state = state,
            onOpenPlan = model::selectPlan,
            onShowAllGoals = { showAllGoals = true },
            modifier = modifier,
        )
    }
}

@Composable
private fun TodayOverview(
    state: LifeOsGoalsUiState,
    onOpenPlan: (GoalPlanId) -> Unit,
    onShowAllGoals: () -> Unit,
    modifier: Modifier,
) {
    val today = state.today
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Heute", style = MaterialTheme.typography.headlineMedium)
                Text(
                    today?.date?.format(TODAY_DATE).orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onShowAllGoals) {
                Text("Alle Ziele")
            }
        }

        state.error?.let { error ->
            Text(
                error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        when {
            state.loading && today == null -> Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
            }
            today == null || today.items.isEmpty() -> Column(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                Text("Für heute ist nichts fest eingeplant.", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Bereite Schritte und echte Deadlines aus deinen bestehenden LIFEOS-Zielen erscheinen hier automatisch.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> {
                Text(
                    todaySummary(today),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(
                        items = today.items,
                        key = { "${it.planId.value}:${it.stepId.value}" },
                    ) { item ->
                        TodayPlanCard(item = item, onOpen = { onOpenPlan(item.planId) })
                    }
                }
            }
        }
    }
}

@Composable
private fun TodayPlanCard(
    item: TodayPlanItem,
    onOpen: () -> Unit,
) {
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(todayTimingLabel(item), style = MaterialTheme.typography.labelLarge)
            Text(item.objective, style = MaterialTheme.typography.titleMedium)
            Text(
                item.planTitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val stateLabel = todayStateLabel(item)
            if (stateLabel.isNotBlank()) {
                Text(
                    stateLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun GoalOverview(
    state: LifeOsGoalsUiState,
    onSelectFilter: (GoalWorkspaceFilter) -> Unit,
    onOpenPlan: (GoalPlanUiModel) -> Unit,
    onShowToday: () -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Ziele", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Langfristige Pläne und ihr Fortschritt.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onShowToday) {
                Text("Heute")
            }
        }

        TabRow(selectedTabIndex = state.filter.ordinal) {
            GoalWorkspaceFilter.entries.forEach { filter ->
                Tab(
                    selected = state.filter == filter,
                    onClick = { onSelectFilter(filter) },
                    text = { Text(goalFilterLabel(filter)) },
                )
            }
        }

        state.error?.let { error ->
            Text(
                error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (state.loading && state.workspace.plans.isEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
            }
        } else if (state.visiblePlans.isEmpty()) {
            GoalEmptyState(state.filter, Modifier.weight(1f))
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(
                    items = state.visiblePlans,
                    key = { it.id.value },
                ) { plan ->
                    GoalOverviewCard(
                        plan = plan,
                        onOpen = { onOpenPlan(plan) },
                    )
                }
            }
        }
    }
}

@Composable
private fun GoalEmptyState(
    filter: GoalWorkspaceFilter,
    modifier: Modifier = Modifier,
) {
    val (title, body) = when (filter) {
        GoalWorkspaceFilter.ACTIVE ->
            "Keine aktiven Ziele" to
                "Sobald LIFEOS ein längerfristiges Ziel verfolgt, erscheint es hier."
        GoalWorkspaceFilter.WAITING ->
            "Nichts wartet gerade" to
                "Ziele, die auf Evidenz, Fähigkeiten oder eine Fortsetzung warten, erscheinen hier."
        GoalWorkspaceFilter.DONE ->
            "Noch keine abgeschlossenen Ziele" to
                "Abgeschlossene oder abgebrochene Zielpläne werden hier nachvollziehbar archiviert."
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun todaySummary(today: TodayPlanUiModel): String = buildString {
    append("${today.items.size} Einträge")
    if (today.actionableCount > 0) append(" · ${today.actionableCount} direkt machbar")
    if (today.overdueCount > 0) append(" · ${today.overdueCount} überfällig")
}

private fun todayTimingLabel(item: TodayPlanItem): String = when (item.timing) {
    TodayPlanTiming.OVERDUE -> item.deadline?.let { "Überfällig · ${GOALS_TIME.format(it)}" } ?: "Überfällig"
    TodayPlanTiming.NOW -> "Jetzt"
    TodayPlanTiming.TODAY -> item.deadline?.let { "Heute · ${GOALS_CLOCK.format(it)}" } ?: "Heute"
    TodayPlanTiming.UNSCHEDULED -> "Bereit · kein fester Termin"
}

private fun todayStateLabel(item: TodayPlanItem): String = when {
    item.blockedByDependencies -> "Wartet auf Voraussetzungen"
    item.state == GoalStepState.WAITING_EVIDENCE -> "Wartet auf Evidenz"
    item.state == GoalStepState.WAITING_CAPABILITY -> "Wartet auf Fähigkeit"
    item.state == GoalStepState.PAUSED -> "Pausiert"
    item.state == GoalStepState.BLOCKED -> "Blockiert"
    item.state == GoalStepState.RUNNING -> "Läuft"
    item.state == GoalStepState.READY -> "Bereit"
    else -> ""
}

private fun goalFilterLabel(filter: GoalWorkspaceFilter): String = when (filter) {
    GoalWorkspaceFilter.ACTIVE -> "Aktiv"
    GoalWorkspaceFilter.WAITING -> "Wartet"
    GoalWorkspaceFilter.DONE -> "Erledigt"
}

private val TODAY_DATE: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEEE, d. MMMM", Locale.GERMAN)

private val GOALS_CLOCK: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

internal val GOALS_TIME: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault())
