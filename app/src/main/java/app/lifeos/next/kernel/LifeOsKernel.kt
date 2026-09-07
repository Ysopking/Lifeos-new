package app.lifeos.next.kernel

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.runtime.LifeOsRuntime
import app.lifeos.core.runtime.RuntimeSupervisor
import app.lifeos.core.runtime.health.*
import kotlinx.coroutines.delay
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
 * Process-level owner for the current LIFEOS runtime graph.
 *
 * Bootstrap is kept here so stored photons are replayed at most once during a
 * normal process lifetime instead of once per ViewModel instance.
 */
class LifeOsKernel internal constructor(
    val runtime: LifeOsRuntime,
    val matrix: ThoughtMatrix,
    val photonStore: PhotonRepository,
    private val supervisor: RuntimeSupervisor,
    private val scope: CoroutineScope,
    private val durableResources: DurableRuntimeResources,
    val health: RecoveryCoordinator,
    private val bootGuard: BootLoopGuard,
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
        health.execute(HealthNodes.PhotonStore) { photonStore.save(photon) }
        mutableBootstrapState.update { current ->
            val photons = (current.photons.filterNot { it.id == photon.id } + photon)
                .sortedBy { it.provenance.createdAt }
            current.copy(photons = photons)
        }

        return try {
            if (health.safeMode.active) throw ComponentUnavailable(HealthNodes.Runtime)
            health.execute(HealthNodes.TaskStore) { runtime.ingest(photon) }
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
            try {
                if (bootGuard.begin()) health.safeMode.enter("BootLoop")
            } catch (error: Exception) {
                health.safeMode.enter("BootGuard")
            }
            val report = health.execute(HealthNodes.PhotonStore) { photonStore.loadReport() }
            // Publish the vault independently of task storage and automatic processing.
            if (!health.safeMode.active) {
                try {
                    health.execute(HealthNodes.TaskStore) { recoverExpiredLeases() }
                    supervisor.start()
                    report.photons.forEach { photon ->
                        health.execute(HealthNodes.TaskStore) { runtime.ingest(photon) }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    health.safeMode.enter("BootstrapRuntime")
                    supervisor.stop()
                }
            }
            mutableBootstrapState.value = KernelBootstrapState(
                status = KernelBootstrapStatus.READY,
                photons = report.photons,
                unreadableFiles = report.unreadableFiles.size,
            )
            health.graph.record(HealthNodes.Kernel, if (health.safeMode.active)
                HealthState.DEGRADED else HealthState.HEALTHY, "vault-ready")
            if (!health.safeMode.active) {
                // Only a stable runtime window clears consecutive incomplete boots.
                delay(60_000)
                val states = health.graph.states.value
                if (!health.safeMode.active && runtime.state.value.running &&
                    states[HealthNodes.TaskScheduler.id] == HealthState.HEALTHY &&
                    states[HealthNodes.TaskStore.id] == HealthState.HEALTHY) {
                    try { bootGuard.markStable() } catch (error: Exception) {
                        health.safeMode.enter("BootGuard")
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            mutableBootstrapState.update {
                it.copy(
                    status = if (it.status == KernelBootstrapStatus.READY) it.status else KernelBootstrapStatus.CREATED,
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

    private suspend fun recoverExpiredLeases() {
        while (true) {
            val result = durableResources.leaseRecovery.recoverExpired(LEASE_RECOVERY_BATCH_SIZE)
            if (result.scanned < LEASE_RECOVERY_BATCH_SIZE || result.recovered == 0) return
        }
    }

    private companion object {
        const val LEASE_RECOVERY_BATCH_SIZE = 100
    }
}

