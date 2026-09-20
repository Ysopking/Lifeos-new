package app.lifeos.next

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.lifeos.core.runtime.personal.PersonalConversationCorpusImporter
import app.lifeos.core.runtime.personal.PersonalConversationImportResult
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class PersonalConversationImportPhase {
    IDLE,
    PREVIEWING,
    READY,
    IMPORTING,
    COMPLETED,
    FAILED,
}

data class PersonalConversationImportUiState(
    val phase: PersonalConversationImportPhase = PersonalConversationImportPhase.IDLE,
    val ownerNamesInput: String = "",
    val previews: List<PersonalConversationFilePreview> = emptyList(),
    val error: String? = null,
    val result: PersonalConversationImportResult? = null,
) {
    val selectedTurns: Int get() = previews.sumOf { it.turnCount }
    val ownerTurns: Int get() = previews.sumOf { it.ownerTurns }
    val assistantTurns: Int get() = previews.sumOf { it.assistantTurns }
    val otherTurns: Int get() = previews.sumOf { it.otherTurns }
    val readyToImport: Boolean
        get() = phase == PersonalConversationImportPhase.READY && previews.isNotEmpty()
}

class PersonalConversationImportViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val owner = application as LifeOsApplication
    private val runtime = AndroidPersonalConversationImportRuntime(
        context = application.applicationContext,
        importer = PersonalConversationCorpusImporter(owner.kernel.photonStore),
    )
    private val mutableState = MutableStateFlow(PersonalConversationImportUiState())
    val state = mutableState.asStateFlow()

    fun updateOwnerNames(value: String) {
        mutableState.update { current ->
            val invalidatesPreview =
                current.previews.any { it.kind == PersonalConversationImportKind.WHATSAPP }
            current.copy(
                ownerNamesInput = value,
                phase = if (invalidatesPreview) {
                    PersonalConversationImportPhase.IDLE
                } else {
                    current.phase
                },
                previews = if (invalidatesPreview) emptyList() else current.previews,
                result = if (invalidatesPreview) null else current.result,
                error = null,
            )
        }
    }

    fun preview(
        kind: PersonalConversationImportKind,
        uris: List<Uri>,
    ) {
        if (uris.isEmpty()) return
        if (uris.size > MAX_SELECTED_FILES) {
            mutableState.update {
                it.copy(
                    phase = PersonalConversationImportPhase.FAILED,
                    error = "Maximal $MAX_SELECTED_FILES Dateien pro Import auswählen.",
                    previews = emptyList(),
                    result = null,
                )
            }
            return
        }

        val ownerNames = ownerNames()
        if (kind == PersonalConversationImportKind.WHATSAPP && ownerNames.isEmpty()) {
            mutableState.update {
                it.copy(
                    phase = PersonalConversationImportPhase.FAILED,
                    error = "Für WhatsApp muss mindestens dein eigener Anzeigename eingetragen sein.",
                    previews = emptyList(),
                    result = null,
                )
            }
            return
        }

        uris.forEach(::retainReadPermission)
        mutableState.update {
            it.copy(
                phase = PersonalConversationImportPhase.PREVIEWING,
                previews = emptyList(),
                result = null,
                error = null,
            )
        }

        viewModelScope.launch {
            try {
                val previews = uris
                    .distinctBy(Uri::toString)
                    .map { uri ->
                        runtime.preview(
                            kind = kind,
                            uri = uri,
                            ownerNames = ownerNames,
                        )
                    }
                mutableState.update {
                    it.copy(
                        phase = PersonalConversationImportPhase.READY,
                        previews = previews,
                        result = null,
                        error = null,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.update {
                    it.copy(
                        phase = PersonalConversationImportPhase.FAILED,
                        previews = emptyList(),
                        result = null,
                        error = error.message
                            ?: error::class.simpleName
                            ?: "Gesprächsarchiv konnte nicht geprüft werden.",
                    )
                }
            }
        }
    }

    fun confirmImport() {
        val current = mutableState.value
        if (!current.readyToImport) return
        val previews = current.previews.toList()
        val ownerNames = ownerNames()
        if (
            previews.any { it.kind == PersonalConversationImportKind.WHATSAPP } &&
            ownerNames.isEmpty()
        ) {
            mutableState.update {
                it.copy(
                    phase = PersonalConversationImportPhase.FAILED,
                    error = "WhatsApp-Ownername fehlt.",
                )
            }
            return
        }

        mutableState.update {
            it.copy(
                phase = PersonalConversationImportPhase.IMPORTING,
                result = null,
                error = null,
            )
        }
        viewModelScope.launch {
            try {
                var aggregate = PersonalConversationImportResult(
                    created = 0,
                    replayed = 0,
                    refs = emptyList(),
                )
                for (preview in previews) {
                    aggregate += runtime.import(
                        preview = preview,
                        ownerNames = ownerNames,
                    )
                }
                mutableState.update {
                    it.copy(
                        phase = PersonalConversationImportPhase.COMPLETED,
                        result = aggregate,
                        error = null,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.update {
                    it.copy(
                        phase = PersonalConversationImportPhase.FAILED,
                        result = null,
                        error = error.message
                            ?: error::class.simpleName
                            ?: "Gesprächsarchiv konnte nicht importiert werden.",
                    )
                }
            }
        }
    }

    fun clearSelection() {
        mutableState.update {
            it.copy(
                phase = PersonalConversationImportPhase.IDLE,
                previews = emptyList(),
                result = null,
                error = null,
            )
        }
    }

    fun dismissError() {
        mutableState.update { current ->
            current.copy(
                phase = if (current.previews.isEmpty()) {
                    PersonalConversationImportPhase.IDLE
                } else {
                    PersonalConversationImportPhase.READY
                },
                error = null,
            )
        }
    }

    private fun ownerNames(): Set<String> =
        mutableState.value.ownerNamesInput
            .split(',', ';', '\n')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .toSet()

    private fun retainReadPermission(uri: Uri) {
        runCatching {
            getApplication<Application>().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    private companion object {
        const val MAX_SELECTED_FILES = 32
    }
}
