package app.lifeos.next

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.lifeos.core.runtime.capability.CapabilityGap
import app.lifeos.core.runtime.capability.ToolCenterRuntimeSnapshotReader
import app.lifeos.next.kernel.ToolCenterCapabilityGapRuntimeRegistry
import app.lifeos.next.ui.tools.ToolCenterActionMessages
import app.lifeos.next.ui.tools.ToolCenterOwnerProjector
import app.lifeos.next.ui.tools.ToolCenterOwnerWorkspaceUiModel
import app.lifeos.next.ui.tools.ToolCenterProjector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LifeOsToolCenterUiState(
    val workspace: ToolCenterOwnerWorkspaceUiModel? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val generationInFlightCapabilityId: String? = null,
    val activationInFlightToolId: String? = null,
    val actionStatus: String? = null,
) {
    val actionInFlight: Boolean
        get() = generationInFlightCapabilityId != null || activationInFlightToolId != null
}

/**
 * Owner-facing adapter over productive runtime evidence. Generation and activation are deliberately
 * separate actions and both delegate to the existing guarded kernel boundaries.
 */
class LifeOsToolCenterViewModel(application: Application) : AndroidViewModel(application) {
    private val owner = application as LifeOsApplication
    private val kernel = owner.kernel
    private val reader = ToolCenterRuntimeSnapshotReader()
    private val mutableState = MutableStateFlow(LifeOsToolCenterUiState())
    private var latestGaps: List<CapabilityGap> = ToolCenterCapabilityGapRuntimeRegistry.gaps.value

    val state = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            ToolCenterCapabilityGapRuntimeRegistry.gaps.collect { gaps ->
                latestGaps = gaps
                if (mutableState.value.workspace != null) {
                    loadWorkspace(showLoading = false)
                }
            }
        }
    }

    fun refresh() {
        if (mutableState.value.actionInFlight) return
        viewModelScope.launch { loadWorkspace(showLoading = true) }
    }

    fun approveGeneration(capabilityId: String) {
        val current = mutableState.value
        if (current.actionInFlight) return
        val gap = latestGaps.firstOrNull { it.requirement.capabilityId.value == capabilityId } ?: return
        val projectedGap = current.workspace?.gaps?.firstOrNull { it.capabilityId == capabilityId } ?: return
        if (!projectedGap.approvalEligible) return

        mutableState.update {
            it.copy(
                generationInFlightCapabilityId = capabilityId,
                actionStatus = null,
                error = null,
            )
        }
        viewModelScope.launch {
            try {
                val result = kernel.generateExplicitlyApprovedTool(gap)
                mutableState.update { it.copy(actionStatus = ToolCenterActionMessages.generation(result)) }
                loadWorkspace(showLoading = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.update {
                    it.copy(
                        actionStatus = "Tool-Erzeugung konnte nicht sicher abgeschlossen werden: ${error.message ?: error::class.simpleName ?: "unbekannter Fehler"}"
                    )
                }
            } finally {
                mutableState.update { it.copy(generationInFlightCapabilityId = null) }
            }
        }
    }

    fun reviewAndActivate(toolId: String) {
        val current = mutableState.value
        if (current.actionInFlight) return
        val tool = current.workspace?.trialTools?.firstOrNull { it.toolId == toolId } ?: return
        if (!tool.activationEligible) return

        mutableState.update {
            it.copy(
                activationInFlightToolId = toolId,
                actionStatus = null,
                error = null,
            )
        }
        viewModelScope.launch {
            try {
                val result = kernel.reviewAndActivateGeneratedTool(toolId)
                mutableState.update { it.copy(actionStatus = ToolCenterActionMessages.activation(result)) }
                loadWorkspace(showLoading = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.update {
                    it.copy(
                        actionStatus = "Tool-Aktivierung konnte nicht sicher abgeschlossen werden: ${error.message ?: error::class.simpleName ?: "unbekannter Fehler"}"
                    )
                }
            } finally {
                mutableState.update { it.copy(activationInFlightToolId = null) }
            }
        }
    }

    fun dismissActionStatus() {
        mutableState.update { it.copy(actionStatus = null) }
    }

    private suspend fun loadWorkspace(showLoading: Boolean) {
        if (showLoading) {
            mutableState.update { it.copy(loading = true, error = null) }
        }
        try {
            val runtime = ToolCenterProjector.project(reader.snapshot())
            val durableStatus = owner.generatedToolStatusReader.snapshot()
            val workspace = ToolCenterOwnerProjector.project(
                runtime = runtime,
                gaps = latestGaps,
                status = durableStatus,
            )
            mutableState.update { current ->
                current.copy(
                    workspace = workspace,
                    loading = false,
                    error = null,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            mutableState.update { current ->
                current.copy(
                    loading = false,
                    error = error.message ?: error::class.simpleName ?: "tool-center-load-failed",
                )
            }
        }
    }
}
