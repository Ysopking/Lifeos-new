package app.lifeos.next

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.lifeos.core.model.Photon
import app.lifeos.core.model.Provenance
import app.lifeos.next.kernel.KernelBootstrapStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LifeOsState(
    val photons: List<Photon> = emptyList(),
    val draft: String = "",
    val loading: Boolean = true,
    val saving: Boolean = false,
    val loadFailed: Boolean = false,
    val unreadable: Int = 0,
    val error: String? = null,
)

class LifeOsViewModel(application: Application) : AndroidViewModel(application) {
    private val kernel = (application as LifeOsApplication).kernel
    private val mutableState = MutableStateFlow(LifeOsState())

    val state = mutableState.asStateFlow()
    val runtimeState = kernel.runtime.state
    val matrixState = kernel.matrix.state
    val healthState = kernel.health.graph.states
    val safeModeReasons = kernel.health.safeMode.reasons

    init {
        observeKernel()
    }

    fun editDraft(text: String) {
        if (!mutableState.value.saving) mutableState.update { it.copy(draft = text) }
    }

    fun retryLoad() {
        val current = mutableState.value
        if (!current.loadFailed || current.loading) return

        mutableState.update {
            it.copy(
                loading = true,
                loadFailed = false,
                error = null,
            )
        }
        kernel.retryBootstrap()
    }

    fun dismissError() {
        mutableState.update { it.copy(error = null) }
    }

    fun saveDraft() {
        val current = mutableState.value
        if (current.loading || current.loadFailed || current.saving || current.draft.isBlank()) return

        val photon = Photon(
            content = current.draft.trim(),
            provenance = Provenance("local-chat", "user"),
            tags = setOf("chat"),
        )
        mutableState.update { it.copy(saving = true, error = null) }

        viewModelScope.launch {
            try {
                val result = kernel.persistAndIngest(photon)
                mutableState.update {
                    it.copy(
                        draft = "",
                        error = if (result.processingQueued) {
                            null
                        } else {
                            "Gedanke wurde gespeichert, konnte aber nicht zur Verarbeitung eingereiht werden."
                        },
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.update {
                    it.copy(
                        error = "Gedanke konnte nicht gespeichert werden. Die Eingabe bleibt im Textfeld.",
                    )
                }
            } finally {
                mutableState.update { it.copy(saving = false) }
            }
        }
    }

    private fun observeKernel() {
        viewModelScope.launch {
            kernel.bootstrapState.collect { bootstrap ->
                mutableState.update { current ->
                    val loadError = bootstrap.status == KernelBootstrapStatus.FAILED
                    current.copy(
                        photons = bootstrap.photons.asReversed(),
                        loading = bootstrap.status == KernelBootstrapStatus.CREATED ||
                            bootstrap.status == KernelBootstrapStatus.LOADING,
                        loadFailed = loadError,
                        unreadable = bootstrap.unreadableFiles,
                        error = when {
                            loadError -> LOAD_ERROR_MESSAGE
                            current.error == LOAD_ERROR_MESSAGE -> null
                            else -> current.error
                        },
                    )
                }
            }
        }
    }

    private companion object {
        const val LOAD_ERROR_MESSAGE = "Speicher konnte nicht geladen werden. Bitte erneut versuchen."
    }
}

