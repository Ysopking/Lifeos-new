package app.lifeos.next.kernel

import app.lifeos.core.image.nativebackend.MmsiRuntimeBackendProbe
import app.lifeos.core.language.GoalPhotonFactory
import app.lifeos.core.language.LanguageUnderstandingEngine
import app.lifeos.core.language.PhotonLanguageContextBuilder
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.runtime.LifeOsRuntime
import app.lifeos.core.runtime.RuntimeSupervisor
import app.lifeos.core.runtime.ThoughtMatrix
import app.lifeos.core.runtime.boot.BootContext
import app.lifeos.core.runtime.boot.BootCoordinator
import app.lifeos.core.runtime.boot.BootRunResult
import app.lifeos.core.runtime.capability.LanguageGoalCapabilityRouter
import app.lifeos.core.runtime.cognition.CognitiveOutcomeJournal
import app.lifeos.core.runtime.cognition.CognitivePriority
import app.lifeos.core.runtime.cognition.CognitiveTriggerSink
import app.lifeos.core.runtime.cognition.CognitiveWorkBudget
import app.lifeos.core.runtime.cognition.ContinuousCognitionEngine
import app.lifeos.core.runtime.cognition.PhotonDelta
import app.lifeos.core.runtime.cognition.PhotonDeltaType
import app.lifeos.core.runtime.cognition.PhotonTransactionJournal
import app.lifeos.core.runtime.cognition.SalienceVector
import app.lifeos.core.scene.ProceduralSceneCompiler
import app.lifeos.core.scene.SceneRasterizer
import kotlin.math.abs
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
    val photonTransactions: PhotonTransactionJournal,
    val cognitiveOutcomes: CognitiveOutcomeJournal,
    val cognitiveTriggers: CognitiveTriggerSink,
    /** Lazily probes and selects the strongest offline MMSI execution path supported by this device. */
    val mmsiRuntime: MmsiRuntimeBackendProbe,
    /** Deterministic GoalFrame -> SceneGraph compiler used by image action execution. */
    val sceneCompiler: ProceduralSceneCompiler,
    /** Deterministic reference rasterizer; native backends may replace it behind the same contract. */
    val sceneRasterizer: SceneRasterizer,
    private val languageUnderstanding: LanguageUnderstandingEngine,
    private val goalPhotonFactory: GoalPhotonFactory,
    private val languageContextBuilder: PhotonLanguageContextBuilder,
    private val goalCapabilityRouter: LanguageGoalCapabilityRouter,
    private val supervisor: RuntimeSupervisor,
    private val scope: CoroutineScope,
    private val bootCoordinator: BootCoordinator,
    private val continuousCognition: ContinuousCognitionEngine,
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

    /**
     * Persists the user's exact utterance first, then derives a structured GoalPhoton and a
     * capability-resolution decision. The original text is never replaced by interpretation.
     */
    suspend fun persistUserUtterance(photon: Photon): LanguageSubmissionResult {
        require("chat" in photon.tags) { "User utterance photon must carry the chat tag" }
        val context = languageContextBuilder.build(
            photons = mutableBootstrapState.value.photons,
            now = photon.provenance.createdAt,
            excludeIds = setOf(photon.id),
        )
        val source = persistAndIngest(photon)
        return try {
            val understanding = languageUnderstanding.understand(photon.content, context)
            val routing = goalCapabilityRouter.route(understanding.goal)
            val goalPhoton = goalPhotonFactory.create(
                result = understanding,
                sourcePhotonId = photon.id,
                createdAt = photon.provenance.createdAt,
            )
            val goal = persistAndIngest(goalPhoton.photon)
            LanguageSubmissionResult(
                source = source,
                understanding = understanding,
                goalPhoton = goalPhoton,
                goal = goal,
                routing = routing,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            LanguageSubmissionResult(
                source = source,
                languageFailure = error.message ?: error::class.simpleName,
            )
        }
    }

    suspend fun persistAndIngest(photon: Photon): PhotonSubmissionResult {
        val previous = photonStore.load(photon.id)
        photonStore.save(photon)
        mutableBootstrapState.update { current ->
            val photons = (current.photons.filterNot { it.id == photon.id } + photon)
                .sortedBy { it.provenance.createdAt }
            current.copy(photons = photons)
        }

        return try {
            val submission = continuousCognition.submit(
                delta = PhotonDelta(
                    source = "kernel-live-submit",
                    photonId = photon.id,
                    revisionBefore = previous?.revision,
                    revisionAfter = photon.revision,
                    type = if (previous == null) PhotonDeltaType.CREATED else PhotonDeltaType.UPDATED,
                    importanceHint = photon.semanticMass,
                    timestamp = photon.provenance.createdAt,
                    correlationId = photon.id.value,
                ),
                priority = CognitivePriority.USER_BLOCKING,
                salience = SalienceVector(
                    novelty = if (previous == null) 1.0 else 0.25,
                    relevance = 1.0,
                    urgency = 1.0,
                    semanticMass = photon.semanticMass,
                    confidenceImpact = abs(photon.confidence - (previous?.confidence ?: 0.0)),
                    goalAffinity = if ("chat" in photon.tags || "goal" in photon.tags) 1.0 else 0.5,
                ),
                targetModules = setOf("Gedankenmatrix"),
                budget = LIVE_SUBMISSION_BUDGET,
            )
            val durable = submission.accepted && submission.durableTaskId != null
            PhotonSubmissionResult(
                photon = photon,
                processingQueued = durable,
                processingFailure = if (durable) null else "Cognitive work was not durabilized",
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

    private companion object {
        val LIVE_SUBMISSION_BUDGET = CognitiveWorkBudget(
            maxDurationMs = 30_000,
            maxModuleInvocations = 16,
            maxNewPhotons = 16,
            maxNetworkCalls = 4,
        )
    }
}
