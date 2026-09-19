package app.lifeos.next.ui.goals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.lifeos.next.GoalWorkspaceFilter
import app.lifeos.next.LifeOsGoalsUiState
import app.lifeos.next.ui.components.LifeOsContentFrame
import app.lifeos.next.ui.theme.LifeOsTokens

@Composable
internal fun LifeOsGoalsOverview(
    state: LifeOsGoalsUiState,
    onSelectFilter: (GoalWorkspaceFilter) -> Unit,
    onOpenPlan: (GoalPlanUiModel) -> Unit,
    onShowToday: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LifeOsContentFrame(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(LifeOsTokens.Spacing.xSmall),
            ) {
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
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (state.loading && state.workspace.plans.isEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
            }
        } else if (state.visiblePlans.isEmpty()) {
            GoalEmptyState(
                filter = state.filter,
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(LifeOsTokens.Spacing.small),
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
