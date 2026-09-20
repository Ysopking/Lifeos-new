package app.lifeos.next.kernel

import app.lifeos.core.language.GoalFrame
import app.lifeos.core.language.GoalPhotonFactory
import app.lifeos.core.language.IntentType
import app.lifeos.core.language.LanguageContextRetriever
import app.lifeos.core.language.LanguageUnderstandingEngine
import app.lifeos.core.language.VersionedLanguageRuntime
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonIndexOrder
import app.lifeos.core.model.PhotonIndexQuery
import app.lifeos.core.model.RevisionedPhotonRepository
import app.lifeos.core.runtime.ConversationPath
import app.lifeos.core.runtime.ConversationSignalClassifier
import app.lifeos.core.runtime.FastConversationContext
import app.lifeos.core.runtime.PhotonIngressMode
import app.lifeos.core.runtime.capability.GoalCapabilityResolution
import app.lifeos.core.runtime.cognition.CognitiveDeltaIdentity
import app.lifeos.core.runtime.cognition.CognitivePriority
import app.lifeos.core.runtime.cognition.CognitiveWorkBudget
import app.lifeos.core.runtime.cognition.ContinuousCognitionEngine
import app.lifeos.core.runtime.cognition.PhotonDelta
import app.lifeos.core.runtime.cognition.PhotonDeltaType
import app.lifeos.core.runtime.cognition.SalienceVector
import app.lifeos.core.runtime.personal.PersonalCorpusLanguageRuntime
import app.lifeos.core.runtime.personal.ProductivePersonalLanguageLearningRuntime
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
        val context = languageContextRetriever.retrieve(
            utterance = photon.content,
            now = photon.provenance.createdAt,
            excludeIds = setOf(photon.id),
        ).context
        val source = persistAndIngest(photon, PhotonIngressMode.ORIGIN)
        return try {
            val corpusDecision = personalCorpusLanguage?.understand(
                utterance = photon.content,
                context = context,
                now = photon.provenance.createdAt,
            )
            val understanding = corpusDecision?.understanding ?: run {
                val understandingEngine =
                    languageRuntime?.current()?.understanding ?: languageUnderstanding
                understandingEngine.understand(photon.content, context)
            }
            corpusDecision?.evidencePhoton(source.photon)?.let { evidence ->
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
        val recent = refs.mapNotNull(revisionedPhotonStore::load)
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
