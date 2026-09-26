package app.lifeos.next.kernel

import app.lifeos.core.model.Photon
import app.lifeos.core.runtime.LifeOsRuntime
import app.lifeos.core.runtime.RuntimeSupervisor
import app.lifeos.core.runtime.ThoughtMatrix
import app.lifeos.core.runtime.boot.BootContext
import app.lifeos.core.runtime.boot.BootCoordinator
import app.lifeos.core.runtime.boot.BootEngineRecoveryResult
import app.lifeos.core.runtime.boot.BootEngineRuntime
import app.lifeos.core.runtime.boot.BootRehydrationReport
import app.lifeos.core.runtime.boot.BootRunResult
import app.lifeos.core.runtime.boot.RuntimeAvailability
import app.lifeos.core.runtime.reasoning.MetaTheoryMemoryProjector
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
    private val warmBootRehydrator: suspend () -> BootRehydrationReport,
    private val bootEngineRuntime: BootEngineRuntime,
    private val bootReadyMaintenanceTrigger: () -> Unit,
    private val metaTheoryMemoryProjector: MetaTheoryMemoryProjector = MetaTheoryMemoryProjector(),
) {
    private val startLock = Any()
    private var bootstrapJob: Job? = null
    private var warmBootstrapJob: Job? = null
    private val mutableBootstrapState = MutableStateFlow(KernelBootstrapState())

    val bootstrapState: StateFlow<KernelBootstrapState> =
        mutableBootstrapState.asStateFlow()

    fun start(): Job = synchronized(startLock) {
        bootstrapJob ?: scope.launch {
            bootstrap()
        }.also { bootstrapJob = it }
    }

    fun startWarmBoot(): Job = synchronized(startLock) {
        warmBootstrapJob ?: scope.launch {
            warmBootstrap()
        }.also { warmBootstrapJob = it }
    }

    fun retryBootstrap(): Job = synchronized(startLock) {
        val existing = bootstrapJob
        val retryable = mutableBootstrapState.value.status in setOf(
            KernelBootstrapStatus.READ_ONLY,
            KernelBootstrapStatus.RECOVERY,
            KernelBootstrapStatus.SAFE_MODE,
            KernelBootstrapStatus.FAILED,
        )
        if (!retryable && existing != null) {
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
            warmBootstrapJob?.cancel()
            warmBootstrapJob = null
        }
        if (mutableBootstrapState.value.actionable) {
            bootEngineRuntime.recover()
        }
        supervisor.stop()
    }

    fun requireReadable() {
        val bootstrap = mutableBootstrapState.value
        require(bootstrap.readable) {
            buildString {
                append("Runtime state is not readable: ")
                append(bootstrap.status)
                bootstrap.failureMessage
                    ?.takeIf { it.isNotBlank() }
                    ?.let {
                        append(" · ")
                        append(it)
                    }
            }
        }
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

    fun requireEffectReady(action: String = "Owner effect") {
        require(mutableBootstrapState.value.actionable) {
            "$action requires writable runtime availability"
        }
    }

    fun requireCompletedBoot(action: String) {
        requireEffectReady(action)
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
            warmBootstrapJob?.cancel()
            warmBootstrapJob = null
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

                is BootRunResult.RecoveryRequired -> completeReadOnlyBoot(
                    context = result.context,
                    warnings = result.snapshot.warnings,
                    failureMessage = result.snapshot.failures
                        .joinToString("; ")
                        .ifBlank { "Runtime recovery is required" },
                )

                is BootRunResult.Failed -> {
                    mutableBootstrapState.value = KernelBootstrapState(
                        status = KernelBootstrapStatus.SAFE_MODE,
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
                    status = KernelBootstrapStatus.SAFE_MODE,
                    failureMessage = error.message ?: error::class.simpleName,
                )
            }
        }
    }

    private suspend fun completeReadOnlyBoot(
        context: BootContext,
        warnings: List<String>,
        failureMessage: String,
    ) {
        val runtimePhotons = context.photons.hot + context.photons.warm
        rebuildBootMatrix(runtimePhotons)

        mutableBootstrapState.value = KernelBootstrapState(
            status = KernelBootstrapStatus.READ_ONLY,
            photons = context.photons.allPhotons,
            unreadableFiles = context.photons.unreadableFiles.size,
            warnings = warnings,
            failureMessage = failureMessage,
        )
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

        val runtimePhotons = context.photons.hot + context.photons.warm
        // Offensive first-read stays kernel-critical, but projects the complete eligible Photon set
        // as one matrix generation and persists it once instead of rewriting a growing snapshot per Photon.
        rebuildBootMatrix(runtimePhotons)

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

    private suspend fun rebuildBootMatrix(photons: List<Photon>) {
        matrix.rebuildFromProjectionInputs(
            photons.map { photon ->
                metaTheoryMemoryProjector.project(photon).projection
            }
        )
    }

    private suspend fun warmBootstrap() {
        if (!mutableBootstrapState.value.actionable) return
        bootReadyMaintenanceTrigger()
        try {
            val report = warmBootRehydrator()
            val limitations = buildList {
                addAll(
                    report.degraded.map {
                        "warm-required-degraded:${it.nodeId.value}:${it.message}"
                    }
                )
                addAll(
                    report.warmFailures.map {
                        "warm-optional-failed:${it.nodeId.value}:${it.message}"
                    }
                )
            }
            if (limitations.isNotEmpty()) {
                mutableBootstrapState.update { current ->
                    current.copy(
                        status = if (current.status == KernelBootstrapStatus.READY) {
                            KernelBootstrapStatus.DEGRADED
                        } else {
                            current.status
                        },
                        availability = if (current.availability == RuntimeAvailability.FULL) {
                            RuntimeAvailability.DEGRADED
                        } else {
                            current.availability
                        },
                        warnings = (current.warnings + limitations).distinct(),
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            mutableBootstrapState.update { current ->
                current.copy(
                    status = if (current.status == KernelBootstrapStatus.READY) {
                        KernelBootstrapStatus.DEGRADED
                    } else {
                        current.status
                    },
                    availability = if (current.availability == RuntimeAvailability.FULL) {
                        RuntimeAvailability.DEGRADED
                    } else {
                        current.availability
                    },
                    warnings = (
                        current.warnings +
                            "warm-rehydration-failed:" +
                            (error.message ?: error::class.simpleName.orEmpty())
                        ).distinct(),
                )
            }
        }
    }
}
