package app.lifeos.next.kernel

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.runtime.LifeOsRuntime
import app.lifeos.core.runtime.RuntimeSupervisor
import app.lifeos.core.runtime.ThoughtMatrix
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Process-level owner for the LIFEOS runtime graph.
 *
 * Bootstrap recovers durable task ownership before starting scheduling, then
 * re-submits stored photons through revision-aware idempotent task keys.
 */
class LifeOsKernel internal constructor(
    val runtime: LifeOsRuntime,
    val matrix: ThoughtMatrix,
    val photonStore: PhotonRepository,
    private val supervisor: RuntimeSupervisor,
    private val scope: CoroutineScope,
    private val durableResources: DurableRuntimeResources,
) {
    private val startLock = Any()
    private var bootstrapJob: Job? = null

    private val mutableBootstrapState = MutableStateFlow(KernelBootstrapState())
    val bootstrapState: StateFlow<KernelBootstrapState> = mutableBootstrapState.asStateFlow()

    fun start(): Job = synchronized(startLock) {
        bootstrapJob ?: scope.launch {
            bootstrap()
        }.also { bootstrapJob = it }
    }

    fun retryBootstrap(): Job = synchronized(startLock) {
        val existing = bootstrapJob
        if (mutableBootstrapState.value.status != KernelBootstrapStatus.FAILED && existing != null) {
            existing
        } else {
            scope.launch {
                bootstrap()
            }.also { bootstrapJob = it }
        }
    }

    fun stop(): Job = scope.launch {
        synchronized(startLock) {
            bootstrapJob?.cancel()
            bootstrapJob = null
        }
        supervisor.stop()
    }

    suspend fun persistAndIngest(photon: Photon): PhotonSubmissionResult {
        photonStore.save(photon)
        mutableBootstrapState.update { current ->
            val photons = (current.photons.filterNot { it.id == photon.id } + photon)
                .sortedBy { it.provenance.createdAt }
            current.copy(photons = photons)
        }

        return try {
            runtime.ingest(photon)
            PhotonSubmissionResult(
                photon = photon,
                processingQueued = true,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            PhotonSubmissionResult(
                photon = photon,
                processingQueued = false,
                processingFailure = error.message ?: error::class.simpleName,
            )
        }
    }

    /** Final process teardown hook; normal Activity/ViewModel destruction must not call this. */
    internal fun shutdown() {
        synchronized(startLock) {
            bootstrapJob?.cancel()
            bootstrapJob = null
        }
        runtime.stop()
        scope.cancel()
    }

    private suspend fun bootstrap() {
        mutableBootstrapState.update {
            it.copy(
                status = KernelBootstrapStatus.LOADING,
                failureMessage = null,
            )
        }

        try {
            recoverExpiredTaskLeases()
            supervisor.start()

            val report = photonStore.loadReport()
            report.photons.forEach { runtime.ingest(it) }

            mutableBootstrapState.value = KernelBootstrapState(
                status = KernelBootstrapStatus.READY,
                photons = report.photons,
                unreadableFiles = report.unreadableFiles.size,
            )
        } catch (cancelled: CancellationException) {
            mutableBootstrapState.update {
                it.copy(
                    status = KernelBootstrapStatus.CREATED,
                    failureMessage = null,
                )
            }
            throw cancelled
        } catch (error: Exception) {
            mutableBootstrapState.update {
                it.copy(
                    status = KernelBootstrapStatus.FAILED,
                    failureMessage = error.message ?: error::class.simpleName,
                )
            }
        }
    }

    private suspend fun recoverExpiredTaskLeases() {
        while (true) {
            val result = durableResources.leaseRecovery.recoverExpired(limit = RECOVERY_BATCH_SIZE)
            if (result.scanned < RECOVERY_BATCH_SIZE || result.recovered == 0) return
        }
    }

    private companion object {
        const val RECOVERY_BATCH_SIZE = 100
    }
}
