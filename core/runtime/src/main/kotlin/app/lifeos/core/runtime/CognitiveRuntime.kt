package app.lifeos.core.runtime

import app.lifeos.core.model.FieldInfluence
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

fun interface ForceField { suspend fun influence(photon: Photon): FieldInfluence? }

data class RuntimeState(
    val running: Boolean = false,
    val processed: Long = 0,
    val lastPhotonId: PhotonId? = null,
    val lastError: String? = null,
)

class CognitiveRuntime(
    private val scope: CoroutineScope,
    private val fields: List<ForceField>,
) {
    private val queue = Channel<Photon>(Channel.BUFFERED)
    private val mutableState = MutableStateFlow(RuntimeState())
    val state: StateFlow<RuntimeState> = mutableState.asStateFlow()

    fun start() {
        if (mutableState.value.running) return
        mutableState.value = mutableState.value.copy(running = true)
        scope.launch {
            for (photon in queue) {
                runCatching { fields.mapNotNull { it.influence(photon) } }
                    .onSuccess { mutableState.value = mutableState.value.copy(processed = mutableState.value.processed + 1, lastPhotonId = photon.id, lastError = null) }
                    .onFailure { mutableState.value = mutableState.value.copy(lastError = it.message) }
            }
        }
    }

    suspend fun ingest(photon: Photon) { queue.send(photon) }
}
