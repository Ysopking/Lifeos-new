package app.lifeos.next.kernel

import app.lifeos.core.image.DeterministicPngEncoder
import app.lifeos.core.image.ImageAssetDescriptor
import app.lifeos.core.image.ImagePhotonFactory
import app.lifeos.core.image.nativebackend.MmsiRuntimeBackendProbe
import app.lifeos.core.language.GoalFrame
import app.lifeos.core.language.GoalPhotonFactory
import app.lifeos.core.language.IntentType
import app.lifeos.core.language.LanguageUnderstandingEngine
import app.lifeos.core.language.LanguageRuntimeSnapshot
import app.lifeos.core.language.VersionedLanguageRuntime
import app.lifeos.core.language.LanguageContextRetriever
import app.lifeos.core.language.PhotonLanguageContextBuilder
import app.lifeos.core.model.BinaryAssetStore
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonIndexOrder
import app.lifeos.core.model.PhotonIndexQuery
import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.model.RevisionedPhotonRepository
import app.lifeos.core.runtime.LifeOsRuntime
import app.lifeos.core.runtime.FastConversationContext
import app.lifeos.core.runtime.ConversationSignalClassifier
import app.lifeos.core.runtime.ConversationPath
import app.lifeos.core.runtime.PhotonIngressMode
import app.lifeos.core.runtime.RuntimeSupervisor
import app.lifeos.core.runtime.CognitiveModule
import app.lifeos.core.runtime.CognitiveModuleRegistry
import app.lifeos.core.runtime.CognitiveModuleSnapshotRepository
import app.lifeos.core.runtime.VersionedCognitiveModuleRegistry
import app.lifeos.core.runtime.ThoughtMatrix
import app.lifeos.core.runtime.boot.BootContext
import app.lifeos.core.runtime.boot.BootCoordinator
import app.lifeos.core.runtime.boot.BootEngineRecoveryResult
import app.lifeos.core.runtime.boot.BootEngineRuntime
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
import app.lifeos.core.runtime.evolution.WorldEquationAutoEvolutionCoordinator
import app.lifeos.core.runtime.evolution.WorldEquationAutoEvolutionResult
import app.lifeos.core.runtime.evolution.WorldEquationEvaluationProtocol
import app.lifeos.core.runtime.evolution.WorldEquationShadowCase
import app.lifeos.core.field.world.WorldEquationSpec
import app.lifeos.core.runtime.goal.GoalResumeEngine
import app.lifeos.core.runtime.goal.GoalConvergenceDecisionProvider
import app.lifeos.core.runtime.goal.GoalOutcomeLearningHook
import app.lifeos.core.runtime.goal.DurableGoalPlanLedger
import app.lifeos.core.runtime.goal.GoalResumeResult
import app.lifeos.core.runtime.goal.LocalCommunicationGoalEngine
import app.lifeos.core.runtime.goal.LocalCommunicationGoalResult
import app.lifeos.core.runtime.goal.LocalDeepSearchGoalEngine
import app.lifeos.core.runtime.goal.LocalDeepSearchGoalResult
import app.lifeos.core.runtime.personal.PersonalCorpusLanguageRuntime
import app.lifeos.core.runtime.personal.ProductivePersonalLanguageLearningRuntime
import app.lifeos.core.runtime.goal.LocalKnowledgeGoalEngine
import app.lifeos.core.runtime.goal.LocalKnowledgeGoalResult
import app.lifeos.core.runtime.goal.LocalSharePreparation
import app.lifeos.core.runtime.query.ProductivePhotonQueryService
import app.lifeos.core.scene.ProceduralSceneCompiler
import app.lifeos.core.scene.SceneGraphPhotonFactory
import app.lifeos.core.scene.SceneRasterizer
import java.security.MessageDigest
import java.time.Instant
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
    val photonStore: RevisionedPhotonRepository,
    val photonTransactions: PhotonTransactionJournal,
    val cognitiveOutcomes: CognitiveOutcomeJournal,
    val cognitiveTriggers: CognitiveTriggerSink,
    /** Durable V7 state, verified and replayed before the runtime is started. */
    val goalPlans: DurableGoalPlanLedger,
    /** Sole productive Goal -> ThoughtGraph -> WorldFormula -> Convergence authority. */
    val productiveGoalConvergence: GoalConvergenceDecisionProvider,
    /** BootEngine-owned persisted Outcome -> WorldFormula -> learning bridge. */
    val goalOutcomeLearning: GoalOutcomeLearningHook,
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
    private val worldEquationAutoEvolution: WorldEquationAutoEvolutionCoordinator,
    private val localReminderScheduler: LocalReminderScheduler,
    private val supervisor: RuntimeSupervisor,
    private val scope: CoroutineScope,
    private val bootCoordinator: BootCoordinator,
    private val bootEngineRuntime: BootEngineRuntime,
    private val continuousCognition: ContinuousCognitionEngine,
    private val cognitiveModuleSnapshotRepository: CognitiveModuleSnapshotRepository? = null,
    private val activeExtensionSnapshotId: suspend () -> String? = { null },
    private val bootReadyMaintenanceTrigger: () -> Unit = {},
    private val goalResumeEngine: GoalResumeEngine = GoalResumeEngine(),
    private val localKnowledgeGoalEngine: LocalKnowledgeGoalEngine = LocalKnowledgeGoalEngine(),
    private val localDeepSearchGoalEngine: LocalDeepSearchGoalEngine = LocalDeepSearchGoalEngine(),
    private val localCommunicationGoalEngine: LocalCommunicationGoalEngine = LocalCommunicationGoalEngine(),
    private val pngEncoder: DeterministicPngEncoder = DeterministicPngEncoder(),
    private val imagePhotonFactory: ImagePhotonFactory = ImagePhotonFactory(),
    private val sceneGraphPhotonFactory: SceneGraphPhotonFactory = SceneGraphPhotonFactory(),
    private val languageRuntime: VersionedLanguageRuntime? = null,
    private val personalLanguageLearning: ProductivePersonalLanguageLearningRuntime? = null,
    private val personalCorpusLanguage: PersonalCorpusLanguageRuntime? = null,
) {
    private val startLock = Any()
    private val conversationClassifier = ConversationSignalClassifier()
    private val revisionedPhotonStore: RevisionedPhotonRepository =
        requireNotNull(photonStore as? RevisionedPhotonRepository) {
            "LifeOsKernel requires RevisionedPhotonRepository for bounded language retrieval"
        }
    private val languageContextRetriever = LanguageContextRetriever(
        photons = revisionedPhotonStore,
        builder = languageContextBuilder,
    )
    val productivePhotonQueries: ProductivePhotonQueryService =
        ProductivePhotonQueryService(revisionedPhotonStore)

    fun currentLanguageSnapshot(): LanguageRuntimeSnapshot =
        requireNotNull(languageRuntime) { "Versioned language runtime is unavailable" }.current()
    private var bootstrapJob: Job? = null

    private val mutableBootstrapState = MutableStateFlow(KernelBootstrapState())
    val bootstrapState: StateFlow<KernelBootstrapState> = mutableBootstrapState.asStateFlow()

    private val photonIngress = PhotonIngressCoordinator(
        photonStore = photonStore,
        liveSubmissionBudget = LIVE_SUBMISSION_BUDGET,
        submitCognition = { delta, priority, salience, targetModules, budget ->
            continuousCognition.submit(
                delta = delta,
                priority = priority,
                salience = salience,
                targetModules = targetModules,
                budget = budget,
            )
        },
        onPhotonPersisted = { photon ->
            mutableBootstrapState.update { current ->
                current.copy(
                    photons = (current.photons.filterNot { it.id == photon.id } + photon)
                        .sortedBy { it.provenance.createdAt }
                )
            }
        },
    )

    suspend fun freezeCognitiveModulesForCurrentCycle(
        builtIns: Collection<CognitiveModule>,
    ): CognitiveModuleRegistry {
        require(builtIns.isNotEmpty()) {
            "At least one built-in cognitive module is required"
        }
        val repository = requireNotNull(cognitiveModuleSnapshotRepository) {
            "Versioned cognitive module repository is not installed"
        }
        val extensionSnapshotId =
            activeExtensionSnapshotId() ?: BUILTIN_EXTENSION_SNAPSHOT_ID
        return VersionedCognitiveModuleRegistry(
            builtIns = builtIns,
            snapshots = repository,
        ).freezeForCycle(extensionSnapshotId)
    }

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
        persistAndIngest = { photon ->
            persistAndIngest(photon, PhotonIngressMode.DERIVED)
        },
    )

    private val localScheduleExecutor = LocalScheduleActionExecutor(
        scheduler = localReminderScheduler,
        persistAndIngest = { photon ->
            persistAndIngest(photon, PhotonIngressMode.DERIVED)
        },
    )

    private val goalActions = GoalActionCoordinator(
        productivePhotonQueries = productivePhotonQueries,
        routeGoal = goalCapabilityRouter::route,
        persistAndIngest = { photon, mode ->
            persistAndIngest(photon, mode)
        },
        goalResumeEngine = goalResumeEngine,
        localKnowledgeGoalEngine = localKnowledgeGoalEngine,
        localDeepSearchGoalEngine = localDeepSearchGoalEngine,
        localCommunicationGoalEngine = localCommunicationGoalEngine,
    )

    private val imageActions = ImageActionCoordinator(
        proceduralImageGenerator = proceduralImageGenerator,
        pngEncoder = pngEncoder,
        imageAssets = imageAssets,
        imagePhotonFactory = imagePhotonFactory,
        sceneGraphPhotonFactory = sceneGraphPhotonFactory,
        ownerReviewPending = OWNER_ASSET_REVIEW_PENDING,
    )

    private val goalActionDispatcher = GoalActionDispatcher(
        executeKnowledge = { context ->
            goalActions.executeLocalKnowledge(
                goal = context.goal,
                sourcePhoton = context.sourcePhoton,
                goalPhotonId = context.goalPhotonId,
            )
        },
        executeDeepSearch = { context ->
            goalActions.executeLocalDeepSearch(
                goal = context.goal,
                sourcePhoton = context.sourcePhoton,
                goalPhotonId = context.goalPhotonId,
            )
        },
        executeImageGeneration = { context ->
            imageActions.generateImage(
                goal = context.goal,
                sourcePhotonId = context.sourcePhoton.id,
                goalPhotonId = context.goalPhotonId,
                referenceInstant = context.sourcePhoton.provenance.createdAt,
            )
        },
        executeImageTransform = localImageTransformExecutor::execute,
        executeSchedule = localScheduleExecutor::execute,
        prepareCommunication = { context ->
            goalActions.executeLocalCommunication(
                goal = context.goal,
                sourcePhoton = context.sourcePhoton,
                goalPhotonId = context.goalPhotonId,
            )
        },
    )

    private val semanticActionGraphRouter = SemanticActionGraphRouter(
        capabilities = goalCapabilityRouter,
        dispatcher = goalActionDispatcher,
    )

    private val conversationTurns = ConversationTurnCoordinator(
        revisionedPhotonStore = revisionedPhotonStore,
        languageContextRetriever = languageContextRetriever,
        languageUnderstanding = languageUnderstanding,
        goalPhotonFactory = goalPhotonFactory,
        routeGoal = goalCapabilityRouter::route,
        semanticActionGraphRouter = semanticActionGraphRouter,
        goalActions = goalActions,
        scope = scope,
        continuousCognition = continuousCognition,
        persistWithoutCognition = { photon, mode ->
            persistWithoutCognition(photon, mode)
        },
        persistAndIngest = { photon, mode ->
            persistAndIngest(photon, mode)
        },
        languageRuntime = languageRuntime,
        personalLanguageLearning = personalLanguageLearning,
        personalCorpusLanguage = personalCorpusLanguage,
        fastBackgroundBudget = FAST_CHAT_BACKGROUND_BUDGET,
    )

    fun start(): Job = synchronized(startLock) {
        bootstrapJob ?: scope.launch {
            bootstrap()
        }.also { bootstrapJob = it }
    }

    suspend fun startWorldEquationEvolution(
        candidate: WorldEquationSpec,
        protocol: WorldEquationEvaluationProtocol,
    ): WorldEquationAutoEvolutionResult {
        requireCognitiveReady()
        return worldEquationAutoEvolution.start(candidate, protocol)
    }

    suspend fun observeWorldEquationEvolution(
        candidate: WorldEquationSpec,
        case: WorldEquationShadowCase,
    ): WorldEquationAutoEvolutionResult {
        requireCognitiveReady()
        return worldEquationAutoEvolution.observe(candidate, case)
    }

    suspend fun promoteWorldEquationIfEligible(
        candidate: WorldEquationSpec,
    ): WorldEquationAutoEvolutionResult {
        requireCognitiveReady()
        return worldEquationAutoEvolution.promoteIfEligible(candidate)
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
        bootEngineRuntime.recover()
        supervisor.stop()
    }

    fun requireCognitiveReady() {
        val bootstrap = mutableBootstrapState.value
        val state = bootstrap.status
        require(
            state == KernelBootstrapStatus.READY || state == KernelBootstrapStatus.DEGRADED
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

    /** Single kernel-owned conversation entrypoint. */
    suspend fun submitConversationTurn(photon: Photon): ConversationTurnResult =
        conversationTurns.submitConversationTurn(photon)

    /**
     * Persists the exact utterance and executes the existing semantic/action path through the
     * extracted conversation coordinator.
     */
    suspend fun persistUserUtterance(photon: Photon): LanguageSubmissionResult =
        conversationTurns.persistUserUtterance(photon)

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
        persistAndIngest(
            localCommunicationGoalEngine.createHandoffReceipt(share),
            PhotonIngressMode.DERIVED,
        )

    /** Reads and integrity-verifies an image asset referenced by a generated image photon. */
    suspend fun loadImageAsset(photon: Photon): ByteArray? =
        imageActions.loadImageAsset(photon)

    private suspend fun persistWithoutCognition(
        photon: Photon,
        mode: PhotonIngressMode,
    ): PhotonSubmissionResult =
        photonIngress.persistWithoutCognition(photon, mode)

    /** Backward-compatible external boundary: direct submissions are ORIGIN. */
    suspend fun persistAndIngest(photon: Photon): PhotonSubmissionResult =
        photonIngress.persistAndIngest(photon)

    internal suspend fun persistAndIngest(
        photon: Photon,
        mode: PhotonIngressMode,
    ): PhotonSubmissionResult =
        photonIngress.persistAndIngest(photon, mode)



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
            status = if (degraded) KernelBootstrapStatus.DEGRADED else KernelBootstrapStatus.READY,
            photons = context.photons.allPhotons,
            unreadableFiles = context.photons.unreadableFiles.size,
            warnings = warnings,
        )
    }

    private companion object {
        const val BUILTIN_EXTENSION_SNAPSHOT_ID = "extension-registry:builtin-baseline"

        const val PRIVATE_OWNER_ACTOR_ID = "private-owner"
        const val OWNER_ASSET_REVIEW_PENDING = "awaiting-owner-review"
        val FAST_CHAT_BACKGROUND_BUDGET = CognitiveWorkBudget(
            maxDurationMs = 5_000,
            maxModuleInvocations = 4,
            maxNewPhotons = 4,
            maxNetworkCalls = 0,
        )
        val LIVE_SUBMISSION_BUDGET = CognitiveWorkBudget(
            maxDurationMs = 30_000,
            maxModuleInvocations = 16,
            maxNewPhotons = 16,
            maxNetworkCalls = 0,
        )
    }
}
