package app.lifeos.core.runtime

import app.lifeos.core.model.CausalTraceId
import app.lifeos.core.model.ModuleWorkspaceSnapshot
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

fun interface CausalPhotonSink {
    suspend fun accept(photon: Photon, traceId: CausalTraceId)

    companion object {
        val NO_OP = CausalPhotonSink { _, _ -> }
    }
}

/**
 * Primary deterministic module fan-out/fan-in runtime.
 * Canonical processing evidence is mirrored into ModuleEvidenceRuntime so workspace,
 * outcome learning and replay all consume the same records persisted by the causal engine.
 */
class CausalCognitiveRuntime(
    private val scope: CoroutineScope,
    private val moduleRegistry: CognitiveModuleRegistry,
    private val engine: CausalCognitionEngine,
    private val photonSink: CausalPhotonSink = CausalPhotonSink.NO_OP,
    private val moduleEvidence: ModuleEvidenceRuntime = ModuleEvidenceRuntime(),
) : LifeOsRuntime {
    private val queue = Channel<Photon>(Channel.BUFFERED)
    private var worker: Job? = null
    private val mutableState = MutableStateFlow(RuntimeState())
    override val state: StateFlow<RuntimeState> = mutableState.asStateFlow()

    override fun start() {
        if (worker?.isActive == true) return
        mutableState.update { it.copy(status = RuntimeStatus.STARTING) }
        val next = scope.launch {
            mutableState.update { it.copy(status = RuntimeStatus.RUNNING) }
            for (photon in queue) processPhoton(photon)
        }
        worker = next
        next.invokeOnCompletion { cause ->
            if (worker === next) {
                mutableState.update {
                    it.copy(
                        status = when {
                            cause == null || cause is CancellationException -> RuntimeStatus.STOPPED
                            else -> RuntimeStatus.FAILED
                        },
                    )
                }
            }
        }
    }

    override fun stop() {
        mutableState.update { it.copy(status = RuntimeStatus.STOPPING) }
        worker?.cancel()
        worker = null
        mutableState.update { it.copy(status = RuntimeStatus.STOPPED) }
    }

    override suspend fun ingest(photon: Photon) {
        queue.send(photon)
    }

    suspend fun moduleWorkspace(): ModuleWorkspaceSnapshot = moduleEvidence.workspace(
        moduleRegistry.activeModules().map { it.descriptor.identity },
    )

    suspend fun verifyRecordedReplay(): Boolean {
        val envelopes = moduleEvidence.replayEnvelopes()
        return moduleEvidence.verifyReplay(envelopes.map { it.processing })
    }

    fun evidenceRuntime(): ModuleEvidenceRuntime = moduleEvidence

    private suspend fun processPhoton(photon: Photon) {
        val result = try {
            engine.process(photon, moduleRegistry.activeModules())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            mutableState.update { previous ->
                previous.copy(
                    failed = previous.failed + 1,
                    lastPhotonId = photon.id,
                    lastFailure = RuntimeFailure(
                        category = RuntimeFailureCategory.UNKNOWN,
                        source = "causal-cognition",
                        message = error.message ?: error::class.simpleName ?: "Causal cognition failure",
                        photonId = photon.id,
                    ),
                )
            }
            return
        }

        result.ledgerEntry.processingRecords.forEach { moduleEvidence.recordProcessing(it) }

        val sinkFailures = mutableListOf<RuntimeFailure>()
        result.emittedPhotons.forEach { emitted ->
            try {
                photonSink.accept(emitted, result.traceId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                sinkFailures += RuntimeFailure(
                    category = RuntimeFailureCategory.STORAGE,
                    source = "causal-photon-sink",
                    message = error.message ?: error::class.simpleName ?: "Causal photon sink failure",
                    photonId = emitted.id,
                )
            }
        }

        val failures = result.failures + sinkFailures
        mutableState.update { previous ->
            previous.copy(
                processed = previous.processed + if (failures.isEmpty()) 1 else 0,
                failed = previous.failed + if (failures.isNotEmpty()) 1 else 0,
                lastPhotonId = photon.id,
                lastFailure = failures.lastOrNull() ?: previous.lastFailure,
                recentInfluences = (previous.recentInfluences + result.influences).takeLast(100),
            )
        }
    }
}
