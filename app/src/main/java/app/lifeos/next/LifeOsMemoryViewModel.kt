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
import app.lifeos.next.ui.memory.MemoryPhotonPager
import app.lifeos.next.ui.memory.MemoryPhotonWindow
import app.lifeos.next.ui.memory.MemorySearchIndex
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
    val pageLoading: Boolean = false,
    val hasOlderSources: Boolean = false,
    val error: String? = null,
)

/** Read-only UI adapter over the productive DurableLifeMemoryRuntime snapshot. */
class LifeOsMemoryViewModel(application: Application) : AndroidViewModel(application) {
    private val owner = application as LifeOsApplication
    private val kernel = owner.kernel
    private val imagePreviewLoader = PhotonImagePreviewLoader(kernel)
    private val photonPager = MemoryPhotonPager(kernel.productivePhotonQueries)
    private val mutableState = MutableStateFlow(LifeOsMemoryUiState())

    private var latestSnapshot: DurableLifeMemorySnapshot? = null
    private var searchIndex: MemorySearchIndex = MemorySearchIndex.build(emptyList())

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
                    searchIndex = searchIndex,
                    query = query,
                ),
            )
        }
    }

    fun selectSource(photonId: PhotonId) {
        val local = MemoryWorkspaceProjector.resolveSource(
            photonId = photonId,
            searchIndex = searchIndex,
            snapshot = latestSnapshot,
        )
        mutableState.update {
            it.copy(
                selectedSourceId = photonId,
                selectedSource = local,
            )
        }
        if (local != null) return

        viewModelScope.launch {
            try {
                val exact = kernel.productivePhotonQueries.exact(setOf(photonId), limit = 1).singleOrNull()
                val resolved = exact?.let { photon ->
                    MemoryWorkspaceProjector.resolveSource(
                        photonId = photonId,
                        searchIndex = MemorySearchIndex.build(listOf(photon), maxIndexedSources = 1),
                        snapshot = latestSnapshot,
                    )
                }
                mutableState.update { current ->
                    if (current.selectedSourceId == photonId) {
                        current.copy(selectedSource = resolved)
                    } else {
                        current
                    }
                }
            } catch (error: Exception) {
                mutableState.update { current ->
                    if (current.selectedSourceId == photonId) {
                        current.copy(error = error.message ?: error::class.simpleName)
                    } else {
                        current
                    }
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
            ?: kernel.productivePhotonQueries.exact(setOf(photonId), limit = 1).singleOrNull()
            ?: return PhotonImagePreviewState.Failed("Quell-Photon ist nicht verfügbar.")
        return imagePreviewLoader.load(photon)
    }

    fun loadOlderSources() {
        val current = mutableState.value
        if (current.pageLoading || !current.hasOlderSources) return
        mutableState.update { it.copy(pageLoading = true) }
        viewModelScope.launch {
            try {
                var window = photonPager.loadMore()
                if (window.loadedPages == 0) {
                    window = photonPager.refreshFront()
                }
                applyMemoryWindow(window)
                mutableState.update {
                    it.copy(
                        pageLoading = false,
                        hasOlderSources = window.hasMore,
                    )
                }
            } catch (error: Exception) {
                mutableState.update {
                    it.copy(
                        pageLoading = false,
                        error = error.message ?: error::class.simpleName ?: "Gedächtnisquellen konnten nicht geladen werden",
                    )
                }
            }
        }
    }

    private fun observeKernel() {
        viewModelScope.launch {
            kernel.bootstrapState.collect { boot ->
                val window = if (boot.ready) {
                    try {
                        photonPager.refreshFront(boot.photons)
                    } catch (_: Exception) {
                        photonPager.seedFallback(boot.photons)
                    }
                } else {
                    photonPager.clear()
                    MemoryPhotonWindow(
                        photons = emptyList(),
                        next = null,
                        hasMore = false,
                        loadedPages = 0,
                    )
                }
                latestSnapshot = owner.lifeMemoryRuntime.current()
                searchIndex = MemorySearchIndex.reuseOrBuild(
                    existing = searchIndex,
                    photons = window.photons,
                    maxIndexedSources = MemoryPhotonPager.DEFAULT_MAX_LOADED_PHOTONS,
                )
                mutableState.update { current ->
                    val selected = current.selectedSourceId?.let { id ->
                        MemoryWorkspaceProjector.resolveSource(
                            photonId = id,
                            searchIndex = searchIndex,
                            snapshot = latestSnapshot,
                        )
                    } ?: current.selectedSource
                    current.copy(
                        workspace = MemoryWorkspaceProjector.project(
                            snapshot = latestSnapshot,
                            searchIndex = searchIndex,
                            query = current.query,
                        ),
                        loading = boot.status == KernelBootstrapStatus.CREATED ||
                            boot.status == KernelBootstrapStatus.LOADING,
                        hasOlderSources = window.hasMore,
                        selectedSource = selected,
                        error = boot.failureMessage ?: current.error,
                    )
                }
            }
        }
    }

    private fun applyMemoryWindow(window: MemoryPhotonWindow) {
        searchIndex = MemorySearchIndex.reuseOrBuild(
            existing = searchIndex,
            photons = window.photons,
            maxIndexedSources = MemoryPhotonPager.DEFAULT_MAX_LOADED_PHOTONS,
        )
        mutableState.update { current ->
            current.copy(
                workspace = MemoryWorkspaceProjector.project(
                    snapshot = latestSnapshot,
                    searchIndex = searchIndex,
                    query = current.query,
                ),
                hasOlderSources = window.hasMore,
            )
        }
    }

    override fun onCleared() {
        imagePreviewLoader.clear()
        super.onCleared()
    }
}
