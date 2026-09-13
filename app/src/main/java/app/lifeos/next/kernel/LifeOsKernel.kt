package app.lifeos.next.kernel

import app.lifeos.core.image.DeterministicPngEncoder
import app.lifeos.core.image.ImageAssetDescriptor
import app.lifeos.core.image.ImagePhotonFactory
import app.lifeos.core.image.nativebackend.MmsiRuntimeBackendProbe
import app.lifeos.core.language.GoalFrame
import app.lifeos.core.language.GoalPhotonFactory
import app.lifeos.core.language.IntentType
import app.lifeos.core.language.LanguageUnderstandingEngine
import app.lifeos.core.language.PhotonLanguageContextBuilder
import app.lifeos.core.model.BinaryAssetStore
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.runtime.LifeOsRuntime
import app.lifeos.core.runtime.RuntimeSupervisor
import app.lifeos.core.runtime.ThoughtMatrix
import app.lifeos.core.runtime.boot.BootContext
import app.lifeos.core.runtime.boot.BootCoordinator
import app.lifeos.core.runtime.boot.BootRunResult
import app.lifeos.core.runtime.capability.CapabilityGap
import app.lifeos.core.runtime.capability.GeneratedToolUserActionCoordinator
import app.lifeos.core.runtime.capability.GeneratedToolUserActionResult
import app.lifeos.core.runtime.capability.LanguageGoalCapabilityRouter
import app.lifeos.core.runtime.capability.PrivateGeneratedToolTrialSuite
import app.lifeos.core.runtime.cognition.CognitiveDeltaIdentity
import app.lifeos.core.runtime.cognition.CognitiveOutcomeJournal
import app.lifeos.core.runtime.cognition.CognitivePriority
import app.lifeos.core.runtime.cognition.CognitiveTriggerSink
import app.lifeos.core.runtime.cognition.CognitiveWorkBudget
import app.lifeos.core.runtime.cognition.ContinuousCognitionEngine
import app.lifeos.core.runtime.cognition.PhotonDelta
import app.lifeos.core.runtime.cognition.PhotonDeltaType
import app.lifeos.core.runtime.cognition.PhotonTransactionJournal
import app.lifeos.core.runtime.cognition.SalienceVector
import app.lifeos.core.runtime.evolution.PrivateNovelCapabilityActivationResult
import app.lifeos.core.runtime.goal.GoalResumeEngine
import app.lifeos.core.runtime.goal.DurableGoalPlanLedger
import app.lifeos.core.runtime.goal.GoalResumeResult
import app.lifeos.core.runtime.goal.LocalCommunicationGoalEngine
import app.lifeos.core.runtime.goal.LocalCommunicationGoalResult
import app.lifeos.core.runtime.goal.LocalDeepSearchGoalEngine
import app.lifeos.core.runtime.goal.LocalDeepSearchGoalResult
import app.lifeos.core.runtime.goal.LocalKnowledgeGoalEngine
import app.lifeos.core.runtime.goal.LocalKnowledgeGoalResult
import app.lifeos.core.runtime.goal.LocalSharePreparation
import app.lifeos.core.scene.ProceduralSceneCompiler
import app.lifeos.core.scene.SceneGraphPhotonFactory
import app.lifeos.core.scene.SceneRasterizer
import java.security.MessageDigest
import java.time.Instant
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
    /** Durable V7 state, verified and replayed before the runtime is started. */
    val goalPlans: DurableGoalPlanLedger,
    /** Lazily probes and selects the strongest offline MMSI execution path supported by this device. */
    val mmsiRuntime: MmsiRuntimeBackendProbe,
    /** Deterministic GoalFrame -> SceneGraph compiler used by image action execution. */
    val sceneCompiler: ProceduralSceneCompiler,
    /** Deterministic reference rasterizer; native backends may replace it behind the same contract. */
    val sceneRasterizer: SceneRasterizer,
    /** Encrypted binary vault; Photon.content stores only compact asset references. */
    val imageAssets: BinaryAssetStore,
    private val proceduralImageGenerator: ProceduralImageGenerationEngine,
    private val languageUnderstanding: LanguageUnderstandingEngine,
    private val goalPhotonFactory: GoalPhotonFactory,
    private val languageContextBuilder: PhotonLanguageContextBuilder,
    private val goalCapabilityRouter: LanguageGoalCapabilityRouter,
    private val privateGeneratedToolRuntime: PrivateGeneratedToolRuntimeResources,
    private val evolutionRuntime: EvolutionRuntimeResources,
    private val localReminderScheduler: LocalReminderScheduler,
    private val supervisor: RuntimeSupervisor,
    private val scope: CoroutineScope,
    private val bootCoordinator: BootCoordinator,
    private val continuousCognition: ContinuousCognitionEngine,
    private val goalResumeEngine: GoalResumeEngine = GoalResumeEngine(),
    private val localKnowledgeGoalEngine: LocalKnowledgeGoalEngine = LocalKnowledgeGoalEngine(),
    private val localDeepSearchGoalEngine: LocalDeepSearchGoalEngine = LocalDeepSearchGoalEngine(),
    private val localCommunicationGoalEngine: LocalCommunicationGoalEngine = LocalCommunicationGoalEngine(),
    private val pngEncoder: DeterministicPngEncoder = DeterministicPngEncoder(),
    private val imagePhotonFactory: ImagePhotonFactory = ImagePhotonFactory(),
    private val sceneGraphPhotonFactory: SceneGraphPhotonFactory = SceneGraphPhotonFactory(),
) {
    private val startLock = Any()
    private var bootstrapJob: Job? = null

    private val mutableBootstrapState = MutableStateFlow(KernelBootstrapState())
    val bootstrapState: StateFlow<KernelBootstrapState> = mutableBootstrapState.asStateFlow()

    private val generatedToolUserActions = GeneratedToolUserActionCoordinator(
        requests = privateGeneratedToolRuntime.requests,
        persist = { photon ->
            persistAndIngest(photon)
            Unit
        },
        load = photonStore::load,
        trialSuite = PrivateGeneratedToolTrialSuite(
            runner = privateGeneratedToolRuntime.trialRunner,
            artifacts = privateGeneratedToolRuntime.artifactRepository,
        ),
    )

    private val localImageTransformExecutor = LocalImageTransformActionExecutor(
        photons = photonStore,
        assets = imageAssets,
        persistAndIngest = ::persistAndIngest,
    )

    private val localScheduleExecutor = LocalScheduleActionExecutor(
        scheduler = localReminderScheduler,
        persistAndIngest = ::persistAndIngest,
    )

    private val goalActionDispatcher = GoalActionDispatcher(
        executeKnowledge = { context ->
            executeLocalKnowledge(
                goal = context.goal,
                sourcePhoton = context.sourcePhoton,
                goalPhotonId = context.goalPhotonId,
            )
        },
        executeDeepSearch = { context ->
            executeLocalDeepSearch(
                goal = context.goal,
                sourcePhoton = context.sourcePhoton,
                goalPhotonId = context.goalPhotonId,
            )
        },
        executeImageGeneration = { context ->
            generateImage(
                goal = context.goal,
                sourcePhotonId = context.sourcePhoton.id,
                goalPhotonId = context.goalPhotonId,
                referenceInstant = context.sourcePhoton.provenance.createdAt,
            )
        },
        executeImageTransform = localImageTransformExecutor::execute,
        executeSchedule = localScheduleExecutor::execute,
        prepareCommunication = { context ->
            executeLocalCommunication(
                goal = context.goal,
                sourcePhoton = context.sourcePhoton,
                goalPhotonId = context.goalPhotonId,
            )
        },
    )

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
     * Persists the user's exact utterance first, derives a GoalPhoton, resolves capabilities, and
     * executes supported action-ready goals entirely offline before returning to the caller.
     * CONTINUE first resumes the exact persisted substantive goal and then re-routes that goal.
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
            val goalResume = when {
                understanding.goal.intent != IntentType.CONTINUE -> null
                !routing.ready -> null
                else -> executeGoalResume(
                    requestGoal = understanding.goal,
                    requestSource = photon,
                    requestGoalPhotonId = goalPhoton.photon.id,
                )
            }
            val resumed = goalResume as? GoalResumeExecutionResult.Resumed
            val effectiveGoal = resumed?.frame ?: understanding.goal
            val effectiveRouting = resumed?.routing ?: routing
            val effectiveSource = resumed?.sourcePhoton ?: photon
            val effectiveGoalPhotonId = resumed?.resumedGoal?.photon?.id ?: goalPhoton.photon.id
            val actions = goalActionDispatcher.execute(
                GoalActionContext(
                    goal = effectiveGoal,
                    routing = effectiveRouting,
                    sourcePhoton = effectiveSource,
                    goalPhotonId = effectiveGoalPhotonId,
                )
            )

            LanguageSubmissionResult(
                source = source,
                understanding = understanding,
                goalPhoton = goalPhoton,
                goal = goal,
                routing = routing,
                goalResume = goalResume,
                imageGeneration = actions.imageGeneration,
                localImageTransform = actions.localImageTransform,
                localKnowledge = actions.localKnowledge,
                localDeepSearch = actions.localDeepSearch,
                localSchedule = actions.localSchedule,
                localCommunication = actions.localCommunication,
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

    /**
     * Explicit private-user action for one blocking gap. It persists a typed request and a separate
     * exact approval before bounded Genesis runs. The result can only become TRIAL or REJECTED here.
     */
    suspend fun generateExplicitlyApprovedTool(gap: CapabilityGap): GeneratedToolUserActionResult {
        requireCompletedBoot("Generated-tool action")
        return generatedToolUserActions.generateExplicitlyApproved(gap)
    }

    /**
     * Separate explicit private-owner action for one already-TRIAL bounded tool. It runs the five
     * non-productive Novel Canary probes and the independent evidence gates before guarded ACTIVE.
     */
    suspend fun reviewAndActivateGeneratedTool(toolId: String): PrivateNovelCapabilityActivationResult {
        requireCompletedBoot("Generated-tool review and activation")
        return evolutionRuntime.privateNovelActivation.reviewAndActivate(
            toolId = toolId,
            ownerActorId = PRIVATE_OWNER_ACTOR_ID,
        )
    }

    /** Records only local handoff to Android's chooser; it never claims external delivery. */
    suspend fun recordCommunicationHandoff(share: LocalSharePreparation): PhotonSubmissionResult =
        persistAndIngest(localCommunicationGoalEngine.createHandoffReceipt(share))

    /** Reads and integrity-verifies an image asset referenced by a generated image photon. */
    suspend fun loadImageAsset(photon: Photon): ByteArray? {
        if (photon.mimeType != ImagePhotonFactory.IMAGE_REFERENCE_MIME) return null
        val descriptor = runCatching { ImageAssetDescriptor.decode(photon.content) }.getOrNull() ?: return null
        return imageAssets.load(descriptor.asset)
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
                    deltaId = CognitiveDeltaIdentity.photonRevision(photon.id, photon.revision),
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

    private fun requireCompletedBoot(action: String) {
        require(
            mutableBootstrapState.value.status == KernelBootstrapStatus.READY ||
                mutableBootstrapState.value.status == KernelBootstrapStatus.DEGRADED
        ) { "$action requires a completed kernel boot" }
    }

    private suspend fun executeGoalResume(
        requestGoal: GoalFrame,
        requestSource: Photon,
        requestGoalPhotonId: PhotonId,
    ): GoalResumeExecutionResult {
        return try {
            when (
                val result = goalResumeEngine.resume(
                    request = requestGoal,
                    requestSource = requestSource,
                    requestGoalPhotonId = requestGoalPhotonId,
                    photons = photonStore.loadAll(),
                    createdAt = requestSource.provenance.createdAt,
                )
            ) {
                is GoalResumeResult.Blocked -> GoalResumeExecutionResult.Blocked(
                    reason = result.reason,
                    message = result.message,
                )
                is GoalResumeResult.Resumed -> {
                    val resumedGoal = persistAndIngest(result.resumedPhoton)
                    val resumedRouting = goalCapabilityRouter.route(result.frame)
                    GoalResumeExecutionResult.Resumed(
                        targetGoalId = result.targetGoal.id,
                        sourcePhoton = result.sourcePhoton,
                        frame = result.frame,
                        resumedGoal = resumedGoal,
                        routing = resumedRouting,
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            GoalResumeExecutionResult.Failed(
                error.message ?: error::class.simpleName ?: "goal resume failed",
            )
        }
    }

    private suspend fun executeLocalKnowledge(
        goal: GoalFrame,
        sourcePhoton: Photon,
        goalPhotonId: PhotonId,
    ): LocalKnowledgeExecutionResult {
        return try {
            val result = localKnowledgeGoalEngine.execute(
                goal = goal,
                sourcePhoton = sourcePhoton,
                goalPhotonId = goalPhotonId,
                photons = photonStore.loadAll(),
                createdAt = sourcePhoton.provenance.createdAt,
            )
            when (result) {
                is LocalKnowledgeGoalResult.Produced -> LocalKnowledgeExecutionResult.Produced(
                    kind = result.kind,
                    output = persistAndIngest(result.photon),
                    evidencePhotonIds = result.evidencePhotonIds,
                )
                is LocalKnowledgeGoalResult.Unsupported -> LocalKnowledgeExecutionResult.Failed(
                    "Local knowledge executor does not support ${result.intent.name}",
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            LocalKnowledgeExecutionResult.Failed(
                error.message ?: error::class.simpleName ?: "local knowledge execution failed",
            )
        }
    }

    private suspend fun executeLocalDeepSearch(
        goal: GoalFrame,
        sourcePhoton: Photon,
        goalPhotonId: PhotonId,
    ): LocalDeepSearchExecutionResult {
        return try {
            when (
                val result = localDeepSearchGoalEngine.execute(
                    goal = goal,
                    sourcePhoton = sourcePhoton,
                    goalPhotonId = goalPhotonId,
                    photons = photonStore.loadAll(),
                    createdAt = sourcePhoton.provenance.createdAt,
                )
            ) {
                is LocalDeepSearchGoalResult.Produced -> LocalDeepSearchExecutionResult.Produced(
                    status = result.result.status,
                    output = persistAndIngest(result.photon),
                    evidencePhotonIds = result.evidencePhotonIds,
                    workUnitsUsed = result.result.workUnitsUsed,
                )
                is LocalDeepSearchGoalResult.Unsupported -> LocalDeepSearchExecutionResult.Failed(
                    "Local DeepSearch executor does not support ${result.intent.name}",
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            LocalDeepSearchExecutionResult.Failed(
                error.message ?: error::class.simpleName ?: "local DeepSearch execution failed",
            )
        }
    }

    private suspend fun executeLocalCommunication(
        goal: GoalFrame,
        sourcePhoton: Photon,
        goalPhotonId: PhotonId,
    ): LocalCommunicationExecutionResult {
        return try {
            when (
                val result = localCommunicationGoalEngine.prepare(
                    goal = goal,
                    sourcePhoton = sourcePhoton,
                    goalPhotonId = goalPhotonId,
                    photons = photonStore.loadAll(),
                )
            ) {
                is LocalCommunicationGoalResult.Prepared ->
                    LocalCommunicationExecutionResult.Prepared(result.share)
                is LocalCommunicationGoalResult.Blocked ->
                    LocalCommunicationExecutionResult.Blocked(result.reason)
                is LocalCommunicationGoalResult.Unsupported ->
                    LocalCommunicationExecutionResult.Failed(
                        "Local communication executor does not support ${result.intent.name}",
                    )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            LocalCommunicationExecutionResult.Failed(
                error.message ?: error::class.simpleName ?: "local communication preparation failed",
            )
        }
    }

    private suspend fun generateImage(
        goal: GoalFrame,
        sourcePhotonId: PhotonId,
        goalPhotonId: PhotonId,
        referenceInstant: Instant,
    ): ImageGenerationResult {
        return try {
            when (val rendered = proceduralImageGenerator.render(goal, referenceInstant)) {
                is ProceduralImageRenderResult.Blocked -> ImageGenerationResult.Blocked(rendered.reasons)
                is ProceduralImageRenderResult.Rendered -> {
                    val createdAt = Instant.now()
                    val sceneGraphPhoton = sceneGraphPhotonFactory.create(
                        graph = rendered.graph,
                        goalPhotonId = goalPhotonId,
                        createdAt = createdAt,
                    )
                    val sceneSubmission = PhotonSubmissionResult(
                        photon = sceneGraphPhoton.photon,
                        processingQueued = false,
                        processingFailure = OWNER_ASSET_REVIEW_PENDING,
                    )
                    val pngBytes = pngEncoder.encode(rendered.image)
                    val asset = imageAssets.save(pngBytes, "image/png")
                    try {
                        val descriptor = ImageAssetDescriptor(
                            asset = asset,
                            width = rendered.image.width,
                            height = rendered.image.height,
                            pixelSha256 = sha256(rendered.image.copyRgba()),
                            sceneId = rendered.graph.sceneId,
                            rendererId = rendered.rendererId,
                        )
                        val imagePhoton = imagePhotonFactory.create(
                            descriptor = descriptor,
                            parentIds = setOf(sourcePhotonId, goalPhotonId, sceneGraphPhoton.photon.id),
                            confidence = rendered.graph.confidence,
                            createdAt = createdAt,
                        )
                        val imageSubmission = PhotonSubmissionResult(
                            photon = imagePhoton,
                            processingQueued = false,
                            processingFailure = OWNER_ASSET_REVIEW_PENDING,
                        )
                        ImageGenerationResult.Generated(
                            GeneratedImageResult(
                                scene = sceneSubmission,
                                image = imageSubmission,
                                descriptor = descriptor,
                                rendererId = rendered.rendererId,
                            ),
                        )
                    } catch (cancelled: CancellationException) {
                        runCatching { imageAssets.delete(asset.id) }
                        throw cancelled
                    } catch (error: Exception) {
                        runCatching { imageAssets.delete(asset.id) }
                        ImageGenerationResult.Failed(error.message ?: error::class.simpleName ?: "image commit failed")
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            ImageGenerationResult.Failed(error.message ?: error::class.simpleName ?: "image generation failed")
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

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
        // Restore the process-local read model without enqueuing a second task family.
        // Durable reconciliation has already restored missing work; terminal work stays terminal.
        runtimePhotons.forEach { matrix.influence(it) }

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
        const val PRIVATE_OWNER_ACTOR_ID = "private-owner"
        const val OWNER_ASSET_REVIEW_PENDING = "awaiting-owner-review"
        val LIVE_SUBMISSION_BUDGET = CognitiveWorkBudget(
            maxDurationMs = 30_000,
            maxModuleInvocations = 16,
            maxNewPhotons = 16,
            maxNetworkCalls = 0,
        )
    }
}
