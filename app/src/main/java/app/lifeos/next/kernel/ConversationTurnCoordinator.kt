package app.lifeos.next.kernel

import app.lifeos.core.language.GoalFrame
import app.lifeos.core.language.GoalPhotonFactory
import app.lifeos.core.language.IntentType
import app.lifeos.core.language.LanguageContext
import app.lifeos.core.language.LanguageContextRetriever
import app.lifeos.core.language.LanguageUnderstandingEngine
import app.lifeos.core.language.VersionedLanguageRuntime
import app.lifeos.core.language.WorldFormulaContextNeedPlanner
import app.lifeos.core.language.LanguageWorldInterpretationEvidence
import app.lifeos.core.language.LanguageUnderstandingResult
import app.lifeos.core.language.LanguageReferenceGroundingStatus
import app.lifeos.core.language.LanguageContextRetrievalNeeds
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonIndexOrder
import app.lifeos.core.model.PhotonIndexQuery
import app.lifeos.core.model.RevisionedPhotonRepository
import app.lifeos.core.model.StableCognitiveIds
import app.lifeos.core.runtime.ConversationPath
import app.lifeos.core.runtime.ConversationSignalClassifier
import app.lifeos.core.runtime.FastConversationContext
import app.lifeos.core.runtime.PhotonIngressMode
import app.lifeos.core.runtime.life.ObservationAuthorityClass
import app.lifeos.core.runtime.capability.GoalCapabilityResolution
import app.lifeos.core.runtime.cognition.CognitiveDeltaIdentity
import app.lifeos.core.runtime.cognition.CognitivePriority
import app.lifeos.core.runtime.cognition.CognitiveWorkBudget
import app.lifeos.core.runtime.cognition.ContinuousCognitionEngine
import app.lifeos.core.runtime.cognition.PhotonDelta
import app.lifeos.core.runtime.cognition.PhotonDeltaType
import app.lifeos.core.runtime.cognition.SalienceVector
import app.lifeos.core.runtime.personal.PersonalCorpusLanguageRuntime
import app.lifeos.core.runtime.personal.PersonalCorpusLanguageDecision
import app.lifeos.core.runtime.personal.ProductivePersonalLanguageLearningRuntime
import app.lifeos.core.runtime.world.StateDimensionEvidence
import app.lifeos.core.runtime.world.LanguageStateSufficiencyPlan
import app.lifeos.core.runtime.world.LanguageStateSufficiencyCoordinator
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class ConversationTurnCoordinator(
    private val revisionedPhotonStore: RevisionedPhotonRepository,
    private val languageContextRetriever: LanguageContextRetriever,
    private val languageUnderstanding: LanguageUnderstandingEngine,
    private val goalPhotonFactory: GoalPhotonFactory,
    private val routeGoal: suspend (GoalFrame) -> GoalCapabilityResolution,
    private val semanticActionGraphRouter: SemanticActionGraphRouter,
    private val goalActions: GoalActionCoordinator,
    private val scope: CoroutineScope,
    private val continuousCognition: ContinuousCognitionEngine,
    private val persistWithoutCognition: suspend (
        Photon,
        PhotonIngressMode,
    ) -> PhotonSubmissionResult,
    private val persistAndIngest: suspend (
        Photon,
        PhotonIngressMode,
    ) -> PhotonSubmissionResult,
    private val languageRuntime: VersionedLanguageRuntime? = null,
    private val personalLanguageLearning: ProductivePersonalLanguageLearningRuntime? = null,
    private val personalCorpusLanguage: PersonalCorpusLanguageRuntime? = null,
    private val fastBackgroundBudget: CognitiveWorkBudget,
    private val classifier: ConversationSignalClassifier = ConversationSignalClassifier(),
    private val languageStateSufficiency: LanguageStateSufficiencyCoordinator =
        LanguageStateSufficiencyCoordinator(),
    private val contextNeedPlanner: WorldFormulaContextNeedPlanner =
        WorldFormulaContextNeedPlanner(),
) {
    suspend fun submitConversationTurn(photon: Photon): ConversationTurnResult {
        require("chat" in photon.tags) { "User utterance photon must carry the chat tag" }
        val route = classifier.classify(photon.content, photon.tags)
        return if (route.path == ConversationPath.FAST_CHAT) {
            val context = fastConversationContext(photon)
            val source = persistWithoutCognition(photon, PhotonIngressMode.ORIGIN)
            observePersonalLanguageLearning(source.photon, understanding = null)
            val response = fastConversationReply(photon.content, context)
            val assistantPhoton = assistantPhotonFor(photon, response, fast = true)
            val assistant = persistWithoutCognition(
                assistantPhoton,
                PhotonIngressMode.DERIVED,
            )
            enqueueFastConversationBackground(photon)
            ConversationTurnResult(
                route = route,
                responseText = response,
                source = source,
                assistant = assistant,
                language = null,
            )
        } else {
            val language = persistUserUtterance(photon)
            val responseGenerator = languageRuntime?.current()?.responseGeneration
            val response = if (responseGenerator != null) {
                LifeOsResponseComposer(responseGenerator).compose(language)
            } else {
                LifeOsResponseComposer.compose(language)
            }
            val assistant = persistAndIngest(
                assistantPhotonFor(photon, response, fast = false),
                PhotonIngressMode.DERIVED,
            )
            ConversationTurnResult(
                route = route,
                responseText = response,
                source = language.source,
                assistant = assistant,
                language = language,
            )
        }
    }

    suspend fun persistUserUtterance(photon: Photon): LanguageSubmissionResult {
        require("chat" in photon.tags) { "User utterance photon must carry the chat tag" }
        val initialContext = languageContextRetriever.retrieve(
            utterance = photon.content,
            now = photon.provenance.createdAt,
            excludeIds = setOf(photon.id),
        ).context
        val source = persistAndIngest(photon, PhotonIngressMode.ORIGIN)
        return try {
            val refined = understandWithWorldFormula(
                photon = photon,
                initialContext = initialContext,
            )
            val understanding = refined.understanding
            refined.corpusDecision?.evidencePhoton(source.photon)?.let { evidence ->
                persistAndIngest(evidence, PhotonIngressMode.DERIVED)
            }
            observePersonalLanguageLearning(source.photon, understanding)
            val routing = routeGoal(understanding.goal)
            val goalPhoton = goalPhotonFactory.create(
                result = understanding,
                sourcePhotonId = photon.id,
                createdAt = photon.provenance.createdAt,
            )
            val goal = persistAndIngest(
                goalPhoton.photon,
                PhotonIngressMode.DERIVED,
            )
            val goalResume = when {
                understanding.goal.intent != IntentType.CONTINUE -> null
                !routing.ready -> null
                else -> goalActions.executeGoalResume(
                    requestGoal = understanding.goal,
                    requestSource = photon,
                    requestGoalPhotonId = goalPhoton.photon.id,
                )
            }
            val resumed = goalResume as? GoalResumeExecutionResult.Resumed
            val effectiveGoal = resumed?.frame ?: understanding.goal
            val effectiveSource = resumed?.sourcePhoton ?: photon
            val effectiveGoalPhotonId =
                resumed?.resumedGoal?.photon?.id ?: goalPhoton.photon.id
            val actionGraphExecution = semanticActionGraphRouter.execute(
                goal = effectiveGoal,
                sourcePhoton = effectiveSource,
                goalPhotonId = effectiveGoalPhotonId,
                goalPhotonRevision =
                    resumed?.resumedGoal?.photon?.revision ?: goalPhoton.photon.revision,
            )
            val actions =
                actionGraphExecution.primaryDispatch ?: GoalActionDispatchResult()

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
                externalEffect = actions.externalEffect,
                actionGraphExecution = actionGraphExecution,
                worldFormulaLanguage = refined.trace,
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

    private suspend fun understandWithWorldFormula(
        photon: Photon,
        initialContext: LanguageContext,
    ): RefinedLanguageTurn {
        val firstPass = understandOnce(
            utterance = photon.content,
            context = initialContext,
            now = photon.provenance.createdAt,
            worldEvidence = emptyList(),
        )
        val initialPlan = languageStateSufficiency.plan(firstPass.understanding.goal)
        val initialEvidence = stateEvidence(
            context = initialContext,
            plan = initialPlan,
        )
        val initialAssessment = languageStateSufficiency.evaluate(
            goal = firstPass.understanding.goal,
            evidence = initialEvidence,
            at = photon.provenance.createdAt,
        )

        if (initialPlan.perceptionNeeds.isEmpty()) {
            return RefinedLanguageTurn(
                understanding = firstPass.understanding,
                corpusDecision = firstPass.corpusDecision,
                trace = WorldFormulaLanguageRefinementTrace(
                    initialPlanFingerprint = initialPlan.fingerprint,
                    retrievalNeedsFingerprint = null,
                    initialPerceptionNeedCount = 0,
                    initialClarificationNeedCount =
                        initialPlan.clarificationNeeds.size,
                    secondPassApplied = false,
                    targetedContextItemCount = 0,
                    finalPlanFingerprint = initialPlan.fingerprint,
                    finalStateStatus = initialAssessment.result?.status,
                    finalWorldGapIds =
                        initialAssessment.worldGaps.map { it.id }.sorted(),
                    finalClarificationNeedIds =
                        initialPlan.clarificationNeeds.map { it.id }.sorted(),
                    finalInterpretationReady =
                        initialAssessment.interpretationReady,
                    worldEvidenceFingerprint =
                        firstPass.understanding.goal.interpretationLattice
                            .worldEvidenceFingerprint,
                ),
            )
        }

        val requiredStateDimensions = initialPlan.perceptionNeeds
            .mapNotNullTo(linkedSetOf()) { it.stateDimension?.value }
        val baseNeeds = contextNeedPlanner.plan(
            goal = firstPass.understanding.goal,
            requiredStateDimensionKeys = requiredStateDimensions,
        )
        val recoveryRefs = firstPass.understanding.goal.referenceGrounding.references
            .asSequence()
            .filter {
                it.status == LanguageReferenceGroundingStatus.STALE_REVISION ||
                    it.status == LanguageReferenceGroundingStatus.OUTSIDE_CONTEXT
            }
            .mapNotNull { it.selectedRevisionRef }
            .toSet()
        val exactRefs = (baseNeeds.exactRevisionRefs + recoveryRefs)
            .sortedWith(
                compareBy<app.lifeos.core.model.PhotonRevisionRef> {
                    it.photonId.value
                }.thenBy { it.revision }
            )
            .take(LanguageContextRetrievalNeeds.MAX_EXACT_REFS)
            .toSet()
        val needs = baseNeeds.copy(exactRevisionRefs = exactRefs)

        val targeted = languageContextRetriever.retrieve(
            utterance = photon.content,
            now = photon.provenance.createdAt,
            excludeIds = setOf(photon.id),
            zoneId = initialContext.zoneId,
            needs = needs,
        )
        val worldEvidence = worldEvidenceForSecondPass(
            first = firstPass.understanding,
            plan = initialPlan,
            context = targeted.context,
            needs = needs,
        )
        val secondPass = understandOnce(
            utterance = photon.content,
            context = targeted.context,
            now = photon.provenance.createdAt,
            worldEvidence = worldEvidence,
        )
        val finalPlan = languageStateSufficiency.plan(secondPass.understanding.goal)
        val finalAssessment = languageStateSufficiency.evaluate(
            goal = secondPass.understanding.goal,
            evidence = stateEvidence(targeted.context, finalPlan),
            at = photon.provenance.createdAt,
        )

        return RefinedLanguageTurn(
            understanding = secondPass.understanding,
            corpusDecision = secondPass.corpusDecision,
            trace = WorldFormulaLanguageRefinementTrace(
                initialPlanFingerprint = initialPlan.fingerprint,
                retrievalNeedsFingerprint = needs.fingerprint,
                initialPerceptionNeedCount = initialPlan.perceptionNeeds.size,
                initialClarificationNeedCount =
                    initialPlan.clarificationNeeds.size,
                secondPassApplied = true,
                targetedContextItemCount = targeted.context.items.size,
                finalPlanFingerprint = finalPlan.fingerprint,
                finalStateStatus = finalAssessment.result?.status,
                finalWorldGapIds =
                    finalAssessment.worldGaps.map { it.id }.sorted(),
                finalClarificationNeedIds =
                    finalPlan.clarificationNeeds.map { it.id }.sorted(),
                finalInterpretationReady =
                    finalAssessment.interpretationReady,
                worldEvidenceFingerprint =
                    secondPass.understanding.goal.interpretationLattice
                        .worldEvidenceFingerprint,
            ),
        )
    }

    private suspend fun understandOnce(
        utterance: String,
        context: LanguageContext,
        now: Instant,
        worldEvidence: List<LanguageWorldInterpretationEvidence>,
    ): LanguagePass {
        val corpusDecision = personalCorpusLanguage?.understand(
            utterance = utterance,
            context = context,
            now = now,
            worldEvidence = worldEvidence,
        )
        if (corpusDecision != null) {
            return LanguagePass(
                understanding = corpusDecision.understanding,
                corpusDecision = corpusDecision,
            )
        }

        val engine = languageRuntime?.current()?.understanding ?: languageUnderstanding
        val understanding = if (worldEvidence.isEmpty()) {
            engine.understand(utterance, context)
        } else {
            engine.understand(
                utterance,
                context,
                worldEvidence,
            )
        }
        return LanguagePass(
            understanding = understanding,
            corpusDecision = null,
        )
    }

    private fun worldEvidenceForSecondPass(
        first: LanguageUnderstandingResult,
        plan: LanguageStateSufficiencyPlan,
        context: LanguageContext,
        needs: LanguageContextRetrievalNeeds,
    ): List<LanguageWorldInterpretationEvidence> {
        val worldStateNeeds = plan.perceptionNeeds.filter {
            it.reason == "unresolved-condition"
        }
        if (worldStateNeeds.isEmpty()) return emptyList()

        val satisfied = worldStateNeeds.all { need ->
            val dimension = requireNotNull(need.stateDimension).value
            context.items.any { dimension in it.stateDimensionKeys }
        }
        val supportRefs = worldStateNeeds
            .flatMap { need ->
                val dimension = requireNotNull(need.stateDimension).value
                context.items
                    .filter { dimension in it.stateDimensionKeys }
                    .map { item ->
                        item.revisionRef?.stableKey ?: item.photonId.value
                    }
            }
            .distinct()
            .sorted()
        val sourceBase = StableCognitiveIds.fingerprint(
            "worldformula-language-second-pass-evidence/v1",
            plan.fingerprint,
            needs.fingerprint,
            satisfied.toString(),
            *supportRefs.toTypedArray(),
        )
        return first.intentEvidence
            .map { it.intent }
            .distinct()
            .sortedBy { it.name }
            .map { intent ->
                LanguageWorldInterpretationEvidence(
                    intent = intent,
                    reference = null,
                    support = 0.0,
                    contradiction = 0.0,
                    stateSufficient = satisfied,
                    sourceFingerprint = StableCognitiveIds.fingerprint(
                        "worldformula-language-second-pass-intent/v1",
                        sourceBase,
                        intent.name,
                    ),
                )
            }
    }

    private fun stateEvidence(
        context: LanguageContext,
        plan: LanguageStateSufficiencyPlan,
    ): List<StateDimensionEvidence> =
        plan.perceptionNeeds.mapNotNull { need ->
            val dimension = need.stateDimension ?: return@mapNotNull null
            val matches = context.items.filter {
                dimension.value in it.stateDimensionKeys
            }
            if (matches.isEmpty()) {
                null
            } else {
                StateDimensionEvidence(
                    dimension = dimension,
                    evidenceIds = matches.mapTo(linkedSetOf()) { item ->
                        item.revisionRef?.stableKey ?: item.photonId.value
                    },
                    strongestAuthority =
                        ObservationAuthorityClass.DERIVED_INFERENCE,
                    latestObservedAt =
                        matches.maxOf { it.createdAt },
                )
            }
        }

    private data class LanguagePass(
        val understanding: LanguageUnderstandingResult,
        val corpusDecision: PersonalCorpusLanguageDecision?,
    )

    private data class RefinedLanguageTurn(
        val understanding: LanguageUnderstandingResult,
        val corpusDecision: PersonalCorpusLanguageDecision?,
        val trace: WorldFormulaLanguageRefinementTrace,
    )

    private fun enqueueFastConversationBackground(photon: Photon) {
        scope.launch {
            try {
                continuousCognition.submit(
                    delta = PhotonDelta(
                        deltaId = CognitiveDeltaIdentity.photonRevision(
                            photon.id,
                            photon.revision,
                        ),
                        source = "kernel-fast-chat-background",
                        photonId = photon.id,
                        revisionAfter = photon.revision,
                        type = PhotonDeltaType.CREATED,
                        importanceHint = photon.semanticMass,
                        timestamp = photon.provenance.createdAt,
                        correlationId = photon.id.value,
                    ),
                    priority = CognitivePriority.BACKGROUND,
                    salience = SalienceVector(
                        novelty = 0.35,
                        relevance = 0.35,
                        urgency = 0.0,
                        semanticMass = photon.semanticMass,
                        confidenceImpact = photon.confidence,
                        goalAffinity = 0.15,
                    ),
                    targetModules = setOf("Gedankenmatrix"),
                    budget = fastBackgroundBudget,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The already-persisted fast response remains authoritative. Durable cognition
                // reconciliation can recover the source Photon if admission is unavailable.
            }
        }
    }

    private suspend fun fastConversationContext(
        photon: Photon,
    ): FastConversationContext {
        val conversationTag = photon.tags.firstOrNull {
            it.startsWith("conversation:")
        } ?: "conversation:default"
        val refs = revisionedPhotonStore.query(
            PhotonIndexQuery(
                allTags = setOf("chat", conversationTag),
                latestOnly = true,
                order = PhotonIndexOrder.NEWEST_FIRST,
                limit = 8,
            )
        )
        val recent = refs.mapNotNull { ref: app.lifeos.core.model.PhotonRevisionRef ->
            revisionedPhotonStore.load(ref)
        }
        return FastConversationContext(
            conversationId = conversationTag.substringAfter(':', "default"),
            recentTurnCount = recent.size,
            lastUserText = recent.firstOrNull { "chat:user" in it.tags }?.content,
        )
    }

    private fun fastConversationReply(
        text: String,
        context: FastConversationContext,
    ): String {
        val normalized = text.trim().lowercase()
        return when {
            normalized.startsWith("danke") ||
                normalized.startsWith("thanks") ||
                normalized.startsWith("thank you") -> "Gern."

            normalized.startsWith("wie geht") ||
                normalized.startsWith("how are you") ->
                "Mir geht es gut. Was möchtest du als Nächstes machen?"

            normalized in setOf(
                "ok",
                "okay",
                "alles klar",
                "verstanden",
                "passt",
                "gut",
            ) -> "Alles klar."

            else -> if (context.recentTurnCount > 0) {
                "Hallo, ich bin da."
            } else {
                "Hallo."
            }
        }
    }

    private fun assistantPhotonFor(
        source: Photon,
        response: String,
        fast: Boolean,
    ): Photon = Photon(
        content = response,
        provenance = source.provenance.copy(
            source = "lifeos-chat",
            actor = "lifeos",
            parentIds = setOf(source.id),
        ),
        relations = setOf(
            app.lifeos.core.model.PhotonRelation(
                target = source.id,
                type = app.lifeos.core.model.RelationType.DERIVED_FROM,
            )
        ),
        tags = buildSet {
            add("chat")
            add("chat:assistant")
            if (fast) add("conversation-fast-path")
            source.tags
                .filter {
                    it.startsWith("conversation:") ||
                        it.startsWith("turn:")
                }
                .forEach(::add)
        },
    )

    private suspend fun observePersonalLanguageLearning(
        source: Photon,
        understanding: app.lifeos.core.language.LanguageUnderstandingResult?,
    ) {
        val learning = personalLanguageLearning ?: return
        try {
            learning.observePersistedTurn(source, understanding)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Personalization is auxiliary and cannot bypass semantic/action authorities.
        }
    }
}
