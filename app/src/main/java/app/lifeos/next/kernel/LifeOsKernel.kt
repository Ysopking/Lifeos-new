package app.lifeos.next.kernel

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.runtime.LifeOsRuntime
import app.lifeos.core.runtime.RuntimeSupervisor
import app.lifeos.core.runtime.ThoughtMatrix
import app.lifeos.core.runtime.boot.BootContext
import app.lifeos.core.runtime.boot.BootCoordinator
import app.lifeos.core.runtime.boot.BootRunResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Process-level owner for the LIFEOS runtime graph and its deterministic boot lifecycle. */
class LifeOsKernel internal constructor(
    val runtime: LifeOsRuntime,
    val matrix: ThoughtMatrix,
    val photonStore: PhotonRepository,
    private val supervisor: RuntimeSupervisor,
    private val scope: CoroutineScope,
    private val bootCoordinator: BootCoordinator,
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
                warnings = emptyList(),
                failureMessage = null,
            )
        }

        try {
            when (val result = bootCoordinator.boot()) {
                is BootRunResult.Ready -> completeBoot(
                    context = result.context,
                    warnings = result.snapshot.warnings,
                    degraded = false,
                )

                is BootRunResult.Degraded -> completeBoot(
                    context = result.context,
                    warnings = result.snapshot.warnings,
                    degraded = true,
                )

                is BootRunResult.RecoveryRequired -> {
                    mutableBootstrapState.value = KernelBootstrapState(
                        status = KernelBootstrapStatus.FAILED,
                        unreadableFiles = result.context.photons.unreadableFiles.size,
                        warnings = result.snapshot.warnings,
                        failureMessage = result.snapshot.failures
                            .joinToString("; ")
                            .ifBlank { "Runtime recovery is required" },
                    )
                }

                is BootRunResult.Failed -> {
                    mutableBootstrapState.value = KernelBootstrapState(
                        status = KernelBootstrapStatus.FAILED,
                        warnings = result.snapshot.warnings,
                        failureMessage = result.cause.message ?: result.cause::class.simpleName,
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            mutableBootstrapState.update {
                it.copy(
                    status = KernelBootstrapStatus.CREATED,
                    warnings = emptyList(),
                    failureMessage = null,
                )
            }
            throw cancelled
        } catch (error: Exception) {
            runCatching { supervisor.stop() }
            mutableBootstrapState.update {
                it.copy(
                    status = KernelBootstrapStatus.FAILED,
                    failureMessage = error.message ?: error::class.simpleName,
                )
            }
        }
    }

    private suspend fun completeBoot(
        context: BootContext,
        warnings: List<String>,
        degraded: Boolean,
    ) {
        val displayReport = photonStore.loadReport()
        supervisor.start()

        val runtimePhotons = context.photons.hot + context.photons.warm
        runtimePhotons.forEach { runtime.ingest(it) }

        mutableBootstrapState.value = KernelBootstrapState(
            status = if (degraded) KernelBootstrapStatus.DEGRADED else KernelBootstrapStatus.READY,
            photons = displayReport.photons,
            unreadableFiles = maxOf(
                displayReport.unreadableFiles.size,
                context.photons.unreadableFiles.size,
            ),
            warnings = warnings,
        )
    }
}
