package app.lifeos.next.kernel

import app.lifeos.core.model.Photon
import app.lifeos.core.runtime.LifeOsRuntime
import app.lifeos.core.runtime.RuntimeSupervisor
import app.lifeos.core.runtime.ThoughtMatrix
import app.lifeos.core.runtime.boot.BootContext
import app.lifeos.core.runtime.boot.BootCoordinator
import app.lifeos.core.runtime.boot.BootEngineRecoveryResult
import app.lifeos.core.runtime.boot.BootEngineRuntime
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

internal class KernelBootLifecycle(
    private val runtime: LifeOsRuntime,
    private val matrix: ThoughtMatrix,
    private val supervisor: RuntimeSupervisor,
    private val scope: CoroutineScope,
    private val bootCoordinator: BootCoordinator,
    private val bootEngineRuntime: BootEngineRuntime,
    private val bootReadyMaintenanceTrigger: () -> Unit,
) {
    private val startLock = Any()
    private var bootstrapJob: Job? = null
    private val mutableBootstrapState = MutableStateFlow(KernelBootstrapState())

    val bootstrapState: StateFlow<KernelBootstrapState> =
        mutableBootstrapState.asStateFlow()

    fun start(): Job = synchronized(startLock) {
        bootstrapJob ?: scope.launch {
            bootstrap()
        }.also { bootstrapJob = it }
    }

    fun retryBootstrap(): Job = synchronized(startLock) {
        val existing = bootstrapJob
        if (
            mutableBootstrapState.value.status != KernelBootstrapStatus.FAILED &&
            existing != null
        ) {
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
        bootEngineRuntime.recover()
        supervisor.stop()
    }

    fun requireCognitiveReady() {
        val bootstrap = mutableBootstrapState.value
        val state = bootstrap.status
        require(
            state == KernelBootstrapStatus.READY ||
                state == KernelBootstrapStatus.DEGRADED
        ) {
            buildString {
                append("Cognitive runtime is not ready: ")
                append(state)
                bootstrap.failureMessage
                    ?.takeIf { it.isNotBlank() }
                    ?.let {
                        append(" · ")
                        append(it)
                    }
            }
        }
    }

    fun requireCompletedBoot(action: String) {
        require(
            mutableBootstrapState.value.status == KernelBootstrapStatus.READY ||
                mutableBootstrapState.value.status == KernelBootstrapStatus.DEGRADED
        ) {
            "$action requires a completed kernel boot"
        }
    }

    fun onPhotonPersisted(photon: Photon) {
        mutableBootstrapState.update { current ->
            current.copy(
                photons = (
                    current.photons.filterNot { it.id == photon.id } + photon
                    ).sortedBy { it.provenance.createdAt }
            )
        }
    }

    fun shutdown() {
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
                        failureMessage =
                            result.cause.message ?: result.cause::class.simpleName,
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
        val cognitiveRecovery = bootEngineRuntime.recover()
        when (cognitiveRecovery) {
            BootEngineRecoveryResult.NoActiveCycle,
            is BootEngineRecoveryResult.ResumePrepared,
            is BootEngineRecoveryResult.ResumeCommit,
            is BootEngineRecoveryResult.RecoveredCommitted -> Unit
        }

        supervisor.start()
        bootReadyMaintenanceTrigger()

        val runtimePhotons = context.photons.hot + context.photons.warm
        // Restore the process-local read model without enqueuing a second task family.
        // Durable reconciliation has already restored missing work; terminal work stays terminal.
        runtimePhotons.forEach { matrix.influence(it) }

        mutableBootstrapState.value = KernelBootstrapState(
            status = if (degraded) {
                KernelBootstrapStatus.DEGRADED
            } else {
                KernelBootstrapStatus.READY
            },
            photons = context.photons.allPhotons,
            unreadableFiles = context.photons.unreadableFiles.size,
            warnings = warnings,
        )
    }
}
