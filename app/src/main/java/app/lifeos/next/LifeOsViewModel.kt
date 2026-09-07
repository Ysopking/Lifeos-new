package app.lifeos.next

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.lifeos.core.data.EncryptedPhotonStore
import app.lifeos.core.model.Photon
import app.lifeos.core.model.Provenance
import app.lifeos.core.runtime.CognitiveRuntime
import app.lifeos.core.runtime.ThoughtMatrix
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val store = EncryptedPhotonStore(application)
    private val matrix = ThoughtMatrix()
    private val runtime = CognitiveRuntime(viewModelScope, listOf(matrix))
    private val mutableState = MutableStateFlow(LifeOsState())
    val state = mutableState.asStateFlow()
    val runtimeState = runtime.state
    val matrixState = matrix.state

    init { runtime.start(); load() }

    fun editDraft(text: String) {
        if (!mutableState.value.saving) mutableState.update { it.copy(draft = text) }
    }

    fun retryLoad() { if (mutableState.value.loadFailed && !mutableState.value.loading) load() }
    fun dismissError() { mutableState.update { it.copy(error = null) } }

    private fun load() {
        mutableState.update { it.copy(loading = true, loadFailed = false, error = null) }
        viewModelScope.launch {
            try {
                val report = store.loadReport()
                mutableState.update { it.copy(photons = report.photons.asReversed(), unreadable = report.unreadableFiles.size) }
                report.photons.forEach { runtime.ingest(it) }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                mutableState.update { it.copy(loadFailed = true, error = "Speicher konnte nicht geladen werden. Bitte erneut versuchen.") }
            } finally { mutableState.update { it.copy(loading = false) } }
        }
    }

    fun saveDraft() {
        val current = mutableState.value
        if (current.loading || current.loadFailed || current.saving || current.draft.isBlank()) return
        val photon = Photon(content = current.draft.trim(), provenance = Provenance("local-chat", "user"), tags = setOf("chat"))
        mutableState.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                store.save(photon)
                mutableState.update { it.copy(photons = listOf(photon) + it.photons, draft = "") }
                runtime.ingest(photon)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                mutableState.update { it.copy(error = "Speichern oder Verarbeitung fehlgeschlagen. Eine nicht gespeicherte Eingabe bleibt im Textfeld.") }
            } finally { mutableState.update { it.copy(saving = false) } }
        }
    }

    override fun onCleared() { runtime.stop(); super.onCleared() }
}
