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
import app.lifeos.next.ui.memory.MemorySearchIndex
import app.lifeos.next.ui.memory.MemorySourceUi
import app.lifeos.next.ui.memory.MemoryWorkspacePageState
import app.lifeos.next.ui.memory.MemoryWorkspacePager
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
    private var searchIndex: MemorySearchIndex = MemorySearchIndex.build(emptyList())
    private var fullWorkspace: MemoryWorkspaceUiModel = MemoryWorkspaceUiModel.empty()
    private var pageState: MemoryWorkspacePageState = MemoryWorkspacePageState()

    val state = mutableState.asStateFlow()

    init {
        observeKernel()
    }

    fun editQuery(query: String) {
        pageState = MemoryWorkspacePageState()
        fullWorkspace = MemoryWorkspaceProjector.project(
            snapshot = latestSnapshot,
            searchIndex = searchIndex,
            query = query,
        )
        mutableState.update { current ->
            current.copy(
                query = query,
                workspace = MemoryWorkspacePager.page(fullWorkspace, pageState),
            )
        }
    }

    fun loadMoreNow() {
        pageState = pageState.expandNow()
        publishPagedWorkspace()
    }

    fun loadMoreTopics() {
        pageState = pageState.expandTopics()
        publishPagedWorkspace()
    }

    fun loadMoreTimeline() {
        pageState = pageState.expandTimeline()
        publishPagedWorkspace()
    }

    fun selectSource(photonId: PhotonId) {
        val resolved = MemoryWorkspaceProjector.resolveSource(
            photonId = photonId,
            searchIndex = searchIndex,
            snapshot = latestSnapshot,
        )
        mutableState.update {
            it.copy(
                selectedSourceId = photonId,
                selectedSource = resolved,
            )
        }
        if (resolved != null) return

        viewModelScope.launch {
            val exact = runCatching {
                kernel.productivePhotonQueries.exact(setOf(photonId), limit = 1).singleOrNull()
            }.getOrNull()
            val loaded = exact?.let { photon ->
                MemoryWorkspaceProjector.resolveSource(
                    photonId = photonId,
                    photons = listOf(photon),
                    snapshot = latestSnapshot,
                )
            }
            mutableState.update { current ->
                if (current.selectedSourceId != photonId) {
                    current
                } else {
                    current.copy(selectedSource = loaded)
                }
            }
        }
    }

    fun dismissSourceDetails() {
        mutableState.update {
            it.copy(selectedSourceId = null, selectedSource = null)
        }
    }

    suspend fun loadImagePreview(photonId: PhotonId): PhotonImagePreviewState {
        val photon = searchIndex.source(photonId)
            ?: runCatching {
                kernel.productivePhotonQueries.exact(setOf(photonId), limit = 1).singleOrNull()
            }.getOrNull()
            ?: return PhotonImagePreviewState.Failed("Quell-Photon ist nicht verfügbar.")
        return imagePreviewLoader.load(photon)
    }

    private fun observeKernel() {
        viewModelScope.launch {
            kernel.bootstrapState.collect { boot ->
                latestPhotons = boot.photons
                searchIndex = MemorySearchIndex.reuseOrBuild(searchIndex, latestPhotons)
                latestSnapshot = owner.lifeMemoryRuntime.current()
                fullWorkspace = MemoryWorkspaceProjector.project(
                    snapshot = latestSnapshot,
                    searchIndex = searchIndex,
                    query = mutableState.value.query,
                )
                mutableState.update { current ->
                    val selected = current.selectedSourceId?.let { id ->
                        MemoryWorkspaceProjector.resolveSource(
                            photonId = id,
                            searchIndex = searchIndex,
                            snapshot = latestSnapshot,
                        )
                    }
                    current.copy(
                        workspace = MemoryWorkspacePager.page(fullWorkspace, pageState),
                        loading = boot.status == KernelBootstrapStatus.CREATED ||
                            boot.status == KernelBootstrapStatus.LOADING,
                        selectedSource = selected,
                        error = boot.failureMessage ?: current.error,
                    )
                }
            }
        }
    }

    private fun publishPagedWorkspace() {
        mutableState.update { current ->
            current.copy(workspace = MemoryWorkspacePager.page(fullWorkspace, pageState))
        }
    }

    override fun onCleared() {
        imagePreviewLoader.clear()
        super.onCleared()
    }
}
