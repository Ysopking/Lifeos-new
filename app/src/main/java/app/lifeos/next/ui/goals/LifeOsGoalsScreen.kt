package app.lifeos.next.ui.goals

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lifeos.next.GoalWorkspaceFilter
import app.lifeos.next.LifeOsGoalsUiState
import app.lifeos.next.LifeOsGoalsViewModel
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun LifeOsGoalsScreen(
    model: LifeOsGoalsViewModel,
    modifier: Modifier = Modifier,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val selectedPlan = state.selectedPlan

    LaunchedEffect(Unit) {
        model.refreshProjection()
    }

    BackHandler(enabled = selectedPlan != null) {
        model.dismissDetails()
    }

    if (selectedPlan == null) {
        GoalOverview(
            state = state,
            onSelectFilter = model::selectFilter,
            onOpenPlan = { model.selectPlan(it.id) },
            modifier = modifier,
        )
    } else {
        GoalDetails(
            plan = selectedPlan,
            onBack = model::dismissDetails,
            modifier = modifier,
        )
    }
}

@Composable
private fun GoalOverview(
    state: LifeOsGoalsUiState,
    onSelectFilter: (GoalWorkspaceFilter) -> Unit,
    onOpenPlan: (GoalPlanUiModel) -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Ziele", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Was LIFEOS gerade verfolgt und wie weit es ist.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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

private fun goalFilterLabel(filter: GoalWorkspaceFilter): String = when (filter) {
    GoalWorkspaceFilter.ACTIVE -> "Aktiv"
    GoalWorkspaceFilter.WAITING -> "Wartet"
    GoalWorkspaceFilter.DONE -> "Erledigt"
}

internal val GOALS_TIME: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault())
