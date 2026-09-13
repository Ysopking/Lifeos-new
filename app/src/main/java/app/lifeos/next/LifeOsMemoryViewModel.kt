package app.lifeos.next

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.runtime.life.DurableLifeMemorySnapshot
import app.lifeos.next.kernel.KernelBootstrapStatus
import app.lifeos.next.ui.components.PhotonImagePreviewLoader
import app.lifeos.next.ui.components.PhotonImagePreviewState
import app.lifeos.next.ui.memory.MemorySourceUi
import app.lifeos.next.ui.memory.MemoryWorkspaceProjector
import app.lifeos.next.ui.memory.MemoryWorkspaceUiModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LifeOsMemoryUiState(
    val workspace: MemoryWorkspaceUiModel = MemoryWorkspaceUiModel.empty(),
    val loading: Boolean = true,
    val query: String = "",
    val selectedSourceId: PhotonId? = null,
    val selectedSource: MemorySourceUi? = null,
    val error: String? = null,
)

/** Read-only UI adapter over the productive DurableLifeMemoryRuntime snapshot. */
class LifeOsMemoryViewModel(application: Application) : AndroidViewModel(application) {
    private val owner = application as LifeOsApplication
    private val kernel = owner.kernel
    private val imagePreviewLoader = PhotonImagePreviewLoader(kernel)
    private val mutableState = MutableStateFlow(LifeOsMemoryUiState())

    private var latestPhotons: List<Photon> = emptyList()
    private var latestSnapshot: DurableLifeMemorySnapshot? = null

    val state = mutableState.asStateFlow()

    init {
        observeKernel()
    }

    fun editQuery(query: String) {
        mutableState.update { current ->
            current.copy(
                query = query,
                workspace = MemoryWorkspaceProjector.project(
                    snapshot = latestSnapshot,
                    photons = latestPhotons,
                    query = query,
                ),
            )
        }
    }

    fun selectSource(photonId: PhotonId) {
        val resolved = MemoryWorkspaceProjector.resolveSource(
            photonId = photonId,
            photons = latestPhotons,
            snapshot = latestSnapshot,
        )
        mutableState.update {
            it.copy(
                selectedSourceId = photonId,
                selectedSource = resolved,
            )
        }
    }

    fun dismissSourceDetails() {
        mutableState.update {
            it.copy(selectedSourceId = null, selectedSource = null)
        }
    }

    suspend fun loadImagePreview(photonId: PhotonId): PhotonImagePreviewState {
        val photon = latestPhotons
            .filter { it.id == photonId }
            .maxByOrNull { it.revision }
            ?: return PhotonImagePreviewState.Failed("Quell-Photon ist nicht verfügbar.")
        return imagePreviewLoader.load(photon)
    }

    private fun observeKernel() {
        viewModelScope.launch {
            kernel.bootstrapState.collect { boot ->
                latestPhotons = boot.photons
                latestSnapshot = owner.lifeMemoryRuntime.current()
                mutableState.update { current ->
                    val selected = current.selectedSourceId?.let { id ->
                        MemoryWorkspaceProjector.resolveSource(
                            photonId = id,
                            photons = latestPhotons,
                            snapshot = latestSnapshot,
                        )
                    }
                    current.copy(
                        workspace = MemoryWorkspaceProjector.project(
                            snapshot = latestSnapshot,
                            photons = latestPhotons,
                            query = current.query,
                        ),
                        loading = boot.status == KernelBootstrapStatus.CREATED ||
                            boot.status == KernelBootstrapStatus.LOADING,
                        selectedSource = selected,
                        error = boot.failureMessage ?: current.error,
                    )
                }
            }
        }
    }

    override fun onCleared() {
        imagePreviewLoader.clear()
        super.onCleared()
    }
}
