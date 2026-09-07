package app.lifeos.core.runtime

import app.lifeos.core.model.FieldInfluence
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

fun interface ForceField { suspend fun influence(photon: Photon): FieldInfluence? }

data class RuntimeState(
    val running: Boolean = false,
    val processed: Long = 0,
    val lastPhotonId: PhotonId? = null,
    val lastError: String? = null,
    val failed: Long = 0,
    val recentInfluences: List<FieldInfluence> = emptyList(),
)

/** Owned by one lifecycle scope; start/stop are called on its dispatcher. */
class CognitiveRuntime(private val scope: CoroutineScope, private val fields: List<ForceField>) {
    private val queue = Channel<Photon>(Channel.BUFFERED)
    private var worker: Job? = null
    private val mutableState = MutableStateFlow(RuntimeState())
    val state: StateFlow<RuntimeState> = mutableState.asStateFlow()

    fun start() {
        if (worker?.isActive == true) return
        val next = scope.launch {
            for (photon in queue) {
                val influences = mutableListOf<FieldInfluence>()
                var failures = 0
                for (field in fields) {
                    try { field.influence(photon)?.let(influences::add)
                    } catch (cancelled: CancellationException) { throw cancelled
                    } catch (error: Exception) { failures++ }
                }
                mutableState.update { previous -> previous.copy(
                    processed = previous.processed + if (failures == 0) 1 else 0,
                    failed = previous.failed + if (failures > 0) 1 else 0,
                    lastPhotonId = photon.id,
                    lastError = if (failures > 0) "$failures Modul(e) fehlgeschlagen" else previous.lastError,
                    recentInfluences = (previous.recentInfluences + influences).takeLast(100),
                ) }
            }
        }
        worker = next
        mutableState.update { it.copy(running = next.isActive) }
        next.invokeOnCompletion {
            if (worker === next) mutableState.update { it.copy(running = false) }
        }
    }

    fun stop() {
        worker?.cancel()
        worker = null
        mutableState.update { it.copy(running = false) }
    }

    suspend fun ingest(photon: Photon) { queue.send(photon) }
}
