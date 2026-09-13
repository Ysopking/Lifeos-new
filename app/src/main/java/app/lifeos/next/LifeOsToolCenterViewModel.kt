package app.lifeos.next

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.lifeos.core.runtime.capability.ToolCenterRuntimeSnapshotReader
import app.lifeos.next.ui.tools.ToolCenterProjector
import app.lifeos.next.ui.tools.ToolCenterUiModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LifeOsToolCenterUiState(
    val workspace: ToolCenterUiModel? = null,
    val loading: Boolean = true,
    val error: String? = null,
)

/** Read-only adapter over the productive capability/generated-tool runtime pair. */
class LifeOsToolCenterViewModel(application: Application) : AndroidViewModel(application) {
    private val reader = ToolCenterRuntimeSnapshotReader()
    private val mutableState = MutableStateFlow(LifeOsToolCenterUiState())

    val state = mutableState.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            mutableState.update { it.copy(loading = true, error = null) }
            try {
                val workspace = ToolCenterProjector.project(reader.snapshot())
                mutableState.update { current ->
                    current.copy(
                        workspace = workspace,
                        loading = false,
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
}
