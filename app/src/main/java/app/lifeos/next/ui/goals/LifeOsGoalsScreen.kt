package app.lifeos.next.ui.goals

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lifeos.next.LifeOsGoalsViewModel

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

        showAllGoals -> LifeOsGoalsOverview(
            state = state,
            onSelectFilter = model::selectFilter,
            onOpenPlan = { model.selectPlan(it.id) },
            onShowToday = { showAllGoals = false },
            modifier = modifier,
        )

        else -> LifeOsTodayScreen(
            state = state,
            onOpenPlan = model::selectPlan,
            onShowAllGoals = { showAllGoals = true },
            modifier = modifier,
        )
    }
}
