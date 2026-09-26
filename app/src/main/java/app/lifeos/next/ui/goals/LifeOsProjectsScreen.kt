package app.lifeos.next.ui.goals

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lifeos.next.LifeOsGoalsViewModel

@Composable
fun LifeOsProjectsScreen(
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

    if (selectedPlan != null) {
        GoalDetails(
            plan = selectedPlan,
            onBack = model::dismissDetails,
            modifier = modifier,
        )
    } else {
        LifeOsGoalsOverview(
            state = state,
            onSelectFilter = model::selectFilter,
            onOpenPlan = { model.selectPlan(it.id) },
            onShowToday = null,
            title = "Projekte",
            subtitle = "Vorhaben, nächste Schritte, Fortschritt und Blockaden.",
            modifier = modifier,
        )
    }
}
