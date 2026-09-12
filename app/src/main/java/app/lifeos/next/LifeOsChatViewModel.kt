package app.lifeos.next

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonRelation
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.RelationType
import app.lifeos.core.runtime.chat.ChatEvent
import app.lifeos.core.runtime.chat.ConversationProjector
import app.lifeos.next.kernel.KernelBootstrapStatus
import app.lifeos.next.kernel.LifeOsResponseComposer
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LifeOsChatUiState(
    val events: List<ChatEvent> = emptyList(),
    val draft: String = "",
    val sending: Boolean = false,
    val bootStatus: KernelBootstrapStatus = KernelBootstrapStatus.CREATED,
    val error: String? = null,
)

class LifeOsChatViewModel(application: Application) : AndroidViewModel(application) {
    private val owner = application as LifeOsApplication
    private val kernel = owner.kernel
    private val mutableState = MutableStateFlow(LifeOsChatUiState())

    val state = mutableState.asStateFlow()

    init {
        observeKernel()
    }

    fun editDraft(text: String) {
        if (!mutableState.value.sending) {
            mutableState.update { it.copy(draft = text) }
        }
    }

    fun dismissError() {
        mutableState.update { it.copy(error = null) }
    }

    fun sendMessage() {
        val current = mutableState.value
        val text = current.draft.trim()
        if (
            text.isBlank() ||
            current.sending ||
            (current.bootStatus != KernelBootstrapStatus.READY &&
                current.bootStatus != KernelBootstrapStatus.DEGRADED)
        ) return

        val turnId = UUID.randomUUID().toString()
        val conversationTag = "${ConversationProjector.CONVERSATION_PREFIX}${ConversationProjector.DEFAULT_CONVERSATION_ID}"
        val turnTag = "${ConversationProjector.TURN_PREFIX}$turnId"
        val userPhoton = Photon(
            content = text,
            provenance = Provenance(
                source = "lifeos-chat",
                actor = "user",
            ),
            tags = setOf("chat", "chat:user", conversationTag, turnTag),
        )

        mutableState.update {
            it.copy(
                draft = "",
                sending = true,
                error = null,
            )
        }

        viewModelScope.launch {
            try {
                val result = kernel.persistUserUtterance(userPhoton)
                val response = LifeOsResponseComposer.compose(result)
                val assistantPhoton = Photon(
                    content = response,
                    provenance = Provenance(
                        source = "lifeos-chat",
                        actor = "lifeos",
                        parentIds = setOf(userPhoton.id),
                    ),
                    relations = setOf(
                        PhotonRelation(
                            target = userPhoton.id,
                            type = RelationType.DERIVED_FROM,
                        )
                    ),
                    tags = setOf("chat", "chat:assistant", conversationTag, turnTag),
                )
                val stored = kernel.persistAndIngest(assistantPhoton)
                if (!stored.processingQueued) {
                    mutableState.update {
                        it.copy(
                            error = stored.processingFailure
                                ?: "Die LIFEOS-Antwort wurde gespeichert, aber nicht vollständig zur Cognition eingereiht.",
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.update {
                    it.copy(error = error.message ?: error::class.simpleName ?: "Chat-Verarbeitung fehlgeschlagen")
                }
            } finally {
                mutableState.update { it.copy(sending = false) }
            }
        }
    }

    private fun observeKernel() {
        viewModelScope.launch {
            kernel.bootstrapState.collect { boot ->
                mutableState.update { current ->
                    current.copy(
                        events = ConversationProjector.project(boot.photons),
                        bootStatus = boot.status,
                        error = boot.failureMessage ?: current.error,
                    )
                }
            }
        }
    }
}
