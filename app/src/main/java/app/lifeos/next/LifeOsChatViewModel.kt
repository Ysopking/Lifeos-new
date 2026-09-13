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
import app.lifeos.core.runtime.life.LifeOsIntegratedCognitionSuiteRegistry
import app.lifeos.core.runtime.life.LifeOsReadinessSnapshot
import app.lifeos.core.runtime.life.LifeOsSelfValidation
import app.lifeos.core.runtime.topology.LifeOsProcessTopology
import app.lifeos.next.kernel.KernelBootstrapStatus
import app.lifeos.next.kernel.LifeOsResponseComposer
import app.lifeos.next.ui.chat.ChatTurnProcessingState
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
    val turnProcessing: ChatTurnProcessingState = ChatTurnProcessingState.idle(),
    val bootStatus: KernelBootstrapStatus = KernelBootstrapStatus.CREATED,
    val registeredSubsystems: Int = 0,
    val unavailableSubsystems: Int = 0,
    val capabilityProviders: Int = 0,
    val generatedProviders: Int = 0,
    val readiness: LifeOsReadinessSnapshot? = null,
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
        mutableState.update { it.copy(draft = text) }
    }

    fun dismissError() {
        mutableState.update { current ->
            current.copy(
                error = null,
                turnProcessing = if (current.turnProcessing.failureMessage != null) {
                    ChatTurnProcessingState.idle()
                } else {
                    current.turnProcessing
                },
            )
        }
    }

    fun sendMessage() {
        val current = mutableState.value
        val text = current.draft.trim()
        if (
            text.isBlank() ||
            current.turnProcessing.inFlight ||
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
                turnProcessing = ChatTurnProcessingState.submitting(turnId),
                error = null,
            )
        }

        viewModelScope.launch {
            var userTurnPersisted = false
            try {
                val result = kernel.persistUserUtterance(userPhoton)
                userTurnPersisted = true
                mutableState.update {
                    it.copy(
                        turnProcessing = ChatTurnProcessingState.persistingResponse(turnId),
                    )
                }

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
                mutableState.update { state ->
                    state.copy(
                        turnProcessing = ChatTurnProcessingState.idle(),
                        error = if (stored.processingQueued) {
                            state.error
                        } else {
                            stored.processingFailure
                                ?: "Die LIFEOS-Antwort wurde gespeichert, aber nicht vollständig zur Cognition eingereiht."
                        },
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val failure = error.message ?: error::class.simpleName ?: "Chat-Verarbeitung fehlgeschlagen"
                mutableState.update {
                    it.copy(
                        turnProcessing = ChatTurnProcessingState.failed(
                            turnId = turnId,
                            userTurnPersisted = userTurnPersisted,
                            message = failure,
                        ),
                        error = if (userTurnPersisted) {
                            "Deine Nachricht ist gespeichert, aber die LIFEOS-Antwort konnte nicht abgeschlossen werden: $failure"
                        } else {
                            "Die Nachricht konnte nicht gespeichert werden: $failure"
                        },
                    )
                }
            }
        }
    }

    private fun observeKernel() {
        viewModelScope.launch {
            kernel.bootstrapState.collect { boot ->
                val topology = if (boot.ready) LifeOsProcessTopology.snapshot() else null
                val readiness = if (boot.ready && mutableState.value.readiness == null) {
                    LifeOsIntegratedCognitionSuiteRegistry.current()?.let { suite ->
                        LifeOsSelfValidation(suite).validate().first
                    }
                } else {
                    mutableState.value.readiness
                }
                mutableState.update { current ->
                    current.copy(
                        events = ConversationProjector.project(boot.photons),
                        bootStatus = boot.status,
                        registeredSubsystems = topology?.registeredSubsystemCount ?: 0,
                        unavailableSubsystems = topology?.unavailableSubsystems?.size ?: 0,
                        capabilityProviders = topology?.capabilityProviderCount ?: 0,
                        generatedProviders = topology?.generatedProviderCount ?: 0,
                        readiness = readiness,
                        error = boot.failureMessage ?: current.error,
                    )
                }
            }
        }
    }
}
