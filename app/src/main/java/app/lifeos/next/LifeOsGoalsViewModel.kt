package app.lifeos.next

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.lifeos.core.model.Photon
import app.lifeos.core.runtime.goal.GoalPlanId
import app.lifeos.next.kernel.KernelBootstrapStatus
import app.lifeos.next.ui.goals.GoalPlanUiModel
import app.lifeos.next.ui.goals.GoalPlanUiStatus
import app.lifeos.next.ui.goals.GoalWorkspaceProjector
import app.lifeos.next.ui.goals.GoalWorkspaceUiModel
import app.lifeos.next.ui.goals.TodayPlanProjector
import app.lifeos.next.ui.goals.TodayPlanUiModel
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class GoalWorkspaceFilter {
    ACTIVE,
    WAITING,
    DONE,
}

data class LifeOsGoalsUiState(
    val workspace: GoalWorkspaceUiModel = GoalWorkspaceUiModel.empty(),
    val today: TodayPlanUiModel? = null,
    val filter: GoalWorkspaceFilter = GoalWorkspaceFilter.ACTIVE,
    val selectedPlanId: GoalPlanId? = null,
    val loading: Boolean = true,
    val error: String? = null,
) {
    val visiblePlans: List<GoalPlanUiModel>
        get() = workspace.plans.filter { plan ->
            when (filter) {
                GoalWorkspaceFilter.ACTIVE -> plan.status in ACTIVE_STATUSES
                GoalWorkspaceFilter.WAITING -> plan.status == GoalPlanUiStatus.WAITING
                GoalWorkspaceFilter.DONE -> plan.status in DONE_STATUSES
            }
        }

    val selectedPlan: GoalPlanUiModel?
        get() = selectedPlanId?.let { id -> workspace.plans.firstOrNull { it.id == id } }

    private companion object {
        val ACTIVE_STATUSES = setOf(
            GoalPlanUiStatus.ACTIVE,
            GoalPlanUiStatus.REPLAN_REQUIRED,
            GoalPlanUiStatus.FAILED,
            GoalPlanUiStatus.BLOCKED,
            GoalPlanUiStatus.PLANNED,
        )
        val DONE_STATUSES = setOf(
            GoalPlanUiStatus.COMPLETED,
            GoalPlanUiStatus.CANCELLED,
        )
    }
}

/** Read-only UI adapter over the productive durable long-horizon goal-plan ledger. */
class LifeOsGoalsViewModel(application: Application) : AndroidViewModel(application) {
    private val owner = application as LifeOsApplication
    private val kernel = owner.kernel
    private val mutableState = MutableStateFlow(LifeOsGoalsUiState())

    val state = mutableState.asStateFlow()

    init {
        observeGoalPlans()
        observeBootstrap()
    }

    fun selectFilter(filter: GoalWorkspaceFilter) {
        mutableState.update { it.copy(filter = filter) }
    }

    fun selectPlan(planId: GoalPlanId) {
        if (mutableState.value.workspace.plans.none { it.id == planId }) return
        mutableState.update { it.copy(selectedPlanId = planId) }
    }

    fun dismissDetails() {
        mutableState.update { it.copy(selectedPlanId = null) }
    }

    /** Recomputes only read-only time/context-derived UI fields. Never mutates the ledger. */
    fun refreshProjection() {
        project(
            states = kernel.goalPlans.states.value,
            photons = kernel.bootstrapState.value.photons,
            at = Instant.now(),
        )
    }

    private fun observeGoalPlans() {
        viewModelScope.launch {
            kernel.goalPlans.states.collect { states ->
                project(
                    states = states,
                    photons = kernel.bootstrapState.value.photons,
                    at = Instant.now(),
                )
            }
        }
    }

    private fun observeBootstrap() {
        viewModelScope.launch {
            kernel.bootstrapState.collect { boot ->
                mutableState.update { current ->
                    current.copy(
                        loading = boot.status == KernelBootstrapStatus.CREATED ||
                            boot.status == KernelBootstrapStatus.LOADING,
                        error = boot.failureMessage,
                    )
                }
                project(
                    states = kernel.goalPlans.states.value,
                    photons = boot.photons,
                    at = Instant.now(),
                )
            }
        }
    }

    private fun project(
        states: Map<GoalPlanId, app.lifeos.core.runtime.goal.GoalPlanRuntimeState>,
        photons: List<Photon>,
        at: Instant,
    ) {
        val workspace = GoalWorkspaceProjector.project(states, at)
        val today = TodayPlanProjector.project(
            workspace = workspace,
            at = at,
            zoneId = ZoneId.systemDefault(),
            photons = photons,
        )
        mutableState.update { current ->
            current.copy(
                workspace = workspace,
                today = today,
                selectedPlanId = current.selectedPlanId?.takeIf { selected ->
                    workspace.plans.any { it.id == selected }
                },
            )
        }
    }
}
