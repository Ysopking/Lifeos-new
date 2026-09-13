package app.lifeos.next

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.lifeos.core.runtime.trace.DecisionTraceId
import app.lifeos.next.ui.decision.DecisionTraceProjector
import app.lifeos.next.ui.decision.DecisionTraceUiModel
import app.lifeos.next.ui.decision.DecisionTraceWorkspaceUiModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LifeOsDecisionTraceUiState(
    val workspace: DecisionTraceWorkspaceUiModel = DecisionTraceWorkspaceUiModel.empty(),
    val selectedTraceId: DecisionTraceId? = null,
    val loading: Boolean = true,
    val error: String? = null,
) {
    val selectedTrace: DecisionTraceUiModel?
        get() = selectedTraceId?.let { id -> workspace.traces.firstOrNull { it.traceId == id } }
}

/** Read-only adapter over the already-authoritative durable DecisionTrace ledger. */
class LifeOsDecisionTraceViewModel(application: Application) : AndroidViewModel(application) {
    private val owner = application as LifeOsApplication
    private val mutableState = MutableStateFlow(LifeOsDecisionTraceUiState())

    val state = mutableState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            mutableState.update { it.copy(loading = true, error = null) }
            try {
                val workspace = DecisionTraceProjector.project(owner.decisionTraces.snapshots())
                mutableState.update { current ->
                    current.copy(
                        workspace = workspace,
                        selectedTraceId = current.selectedTraceId?.takeIf { selected ->
                            workspace.traces.any { it.traceId == selected }
                        },
                        loading = false,
                    )
                }
            } catch (error: Exception) {
                mutableState.update { current ->
                    current.copy(
                        loading = false,
                        error = error.message ?: error::class.simpleName ?: "decision-trace-load-failed",
                    )
                }
            }
        }
    }

    fun selectTrace(traceId: DecisionTraceId) {
        if (mutableState.value.workspace.traces.none { it.traceId == traceId }) return
        mutableState.update { it.copy(selectedTraceId = traceId) }
    }

    fun dismissDetails() {
        mutableState.update { it.copy(selectedTraceId = null) }
    }
}
