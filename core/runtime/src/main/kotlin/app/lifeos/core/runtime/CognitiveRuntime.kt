package app.lifeos.core.runtime

import app.lifeos.core.model.FieldInfluence
import app.lifeos.core.model.Photon
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Owned by one lifecycle scope; start/stop are called on its dispatcher. */
class CognitiveRuntime(private val scope: CoroutineScope, private val fields: List<ForceField>) {
    private val queue = Channel<Photon>(Channel.BUFFERED)
    private var worker: Job? = null
    private val mutableState = MutableStateFlow(RuntimeState())
    val state: StateFlow<RuntimeState> = mutableState.asStateFlow()

    fun start() {
        if (worker?.isActive == true) return
        mutableState.update { it.copy(status = RuntimeStatus.STARTING) }
        val next = scope.launch {
            mutableState.update { it.copy(status = RuntimeStatus.RUNNING) }
            for (photon in queue) {
                val influences = mutableListOf<FieldInfluence>()
                var failures = 0
                for (field in fields) {
                    try {
                        field.influence(photon)?.let(influences::add)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        failures++
                    }
                }
                mutableState.update { previous ->
                    previous.copy(
                        processed = previous.processed + if (failures == 0) 1 else 0,
                        failed = previous.failed + if (failures > 0) 1 else 0,
                        lastPhotonId = photon.id,
                        lastFailure = if (failures > 0) {
                            RuntimeFailure(
                                category = RuntimeFailureCategory.FIELD,
                                source = "cognitive-runtime",
                                message = "$failures Modul(e) fehlgeschlagen",
                                photonId = photon.id,
                            )
                        } else {
                            previous.lastFailure
                        },
                        recentInfluences = (previous.recentInfluences + influences).takeLast(100),
                    )
                }
            }
        }
        worker = next
        next.invokeOnCompletion { cause ->
            if (worker === next) {
                mutableState.update {
                    it.copy(
                        status = when {
                            cause == null || cause is CancellationException -> RuntimeStatus.STOPPED
                            else -> RuntimeStatus.FAILED
                        }
                    )
                }
            }
        }
    }

    fun stop() {
        mutableState.update { it.copy(status = RuntimeStatus.STOPPING) }
        worker?.cancel()
        worker = null
        mutableState.update { it.copy(status = RuntimeStatus.STOPPED) }
    }

    suspend fun ingest(photon: Photon) {
        queue.send(photon)
    }
}
