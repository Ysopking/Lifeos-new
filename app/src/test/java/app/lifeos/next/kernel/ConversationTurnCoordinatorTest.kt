package app.lifeos.next.kernel

import app.lifeos.core.language.GoalPhotonFactory
import app.lifeos.core.language.LanguageContext
import app.lifeos.core.language.LanguageContextRetriever
import app.lifeos.core.language.LanguageUnderstandingEngine
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonIndexOrder
import app.lifeos.core.model.PhotonIndexQuery
import app.lifeos.core.model.PhotonIndexReport
import app.lifeos.core.model.PhotonLoadReport
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.PhotonRevisionWriteResult
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.RevisionedPhotonRepository
import app.lifeos.core.runtime.ConversationPath
import app.lifeos.core.runtime.PhotonIngressMode
import app.lifeos.core.runtime.capability.CapabilityRegistry
import app.lifeos.core.runtime.capability.LanguageGoalCapabilityRouter
import app.lifeos.core.runtime.cognition.CognitiveScheduler
import app.lifeos.core.runtime.cognition.CognitiveWorkBudget
import app.lifeos.core.runtime.cognition.ContinuousCognitionEngine
import app.lifeos.core.runtime.cognition.InMemoryCognitiveEventJournal
import app.lifeos.core.runtime.query.ProductivePhotonQueryService
import app.lifeos.core.runtime.world.StateSufficiencyStatus
import app.lifeos.core.runtime.world.LanguageStateSufficiencyCoordinator
import java.time.Instant
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConversationTurnCoordinatorTest {
    private val now = Instant.parse("2026-09-20T14:30:00Z")

    @Test
    fun `fast chat persists source and assistant while background cognition remains separate`() =
        runTest {
            val repository = InMemoryRevisionedPhotonRepository()
            val journal = InMemoryCognitiveEventJournal()
            val cognition = ContinuousCognitionEngine(
                journal = journal,
                scheduler = CognitiveScheduler(),
            )
            val router = LanguageGoalCapabilityRouter(CapabilityRegistry())
            val actions = GoalActionCoordinator(
                productivePhotonQueries = ProductivePhotonQueryService(repository),
                routeGoal = router::route,
                persistAndIngest = { photon, _ ->
                    repository.save(photon)
                    PhotonSubmissionResult(photon, processingQueued = true)
                },
            )
            val dispatcher = GoalActionDispatcher(
                executeKnowledge = { error("unused knowledge action") },
                executeDeepSearch = { error("unused search action") },
                executeImageGeneration = { error("unused image action") },
                executeImageTransform = { error("unused transform action") },
                executeSchedule = { error("unused schedule action") },
                prepareCommunication = { error("unused communication action") },
            )
            val semantic = SemanticActionGraphRouter(
                capabilities = router,
                dispatcher = dispatcher,
            )
            val persisted = mutableListOf<Pair<Photon, PhotonIngressMode>>()
            val coordinator = ConversationTurnCoordinator(
                revisionedPhotonStore = repository,
                languageContextRetriever = LanguageContextRetriever(repository),
                languageUnderstanding = LanguageUnderstandingEngine(),
                goalPhotonFactory = GoalPhotonFactory(),
                routeGoal = router::route,
                semanticActionGraphRouter = semantic,
                goalActions = actions,
                scope = this,
                continuousCognition = cognition,
                persistWithoutCognition = { photon, mode ->
                    repository.save(photon)
                    persisted += photon to mode
                    PhotonSubmissionResult(
                        photon = photon,
                        processingQueued = false,
                    )
                },
                persistAndIngest = { photon, mode ->
                    repository.save(photon)
                    persisted += photon to mode
                    PhotonSubmissionResult(
                        photon = photon,
                        processingQueued = true,
                    )
                },
                fastBackgroundBudget = CognitiveWorkBudget(
                    maxDurationMs = 5_000,
                    maxModuleInvocations = 4,
                    maxNewPhotons = 4,
                    maxNetworkCalls = 0,
                ),
            )
            val source = Photon(
                id = PhotonId("fast-source"),
                content = "Hallo",
                provenance = Provenance(
                    source = "test",
                    actor = "owner",
                    createdAt = now,
                ),
                tags = setOf(
                    "chat",
                    "chat:user",
                    "conversation:test",
                ),
            )

            val result = coordinator.submitConversationTurn(source)
            advanceUntilIdle()

            assertEquals(ConversationPath.FAST_CHAT, result.route.path)
            assertTrue(result.fastPath)
            assertEquals("Hallo.", result.responseText)
            assertNull(result.language)
            assertEquals(source, result.source.photon)
            assertEquals(2, persisted.size)
            assertEquals(PhotonIngressMode.ORIGIN, persisted[0].second)
            assertEquals(PhotonIngressMode.DERIVED, persisted[1].second)
            assertTrue("chat:assistant" in result.assistant.photon.tags)
            assertTrue("conversation-fast-path" in result.assistant.photon.tags)
            assertEquals(setOf(source.id), result.assistant.photon.provenance.parentIds)
            assertEquals(1L, journal.size())
        }

    @Test
    fun `non-fast turn performs targeted WorldFormula second pass for missing condition state`() =
        runTest {
            val repository = InMemoryRevisionedPhotonRepository()
            val languageEngine = LanguageUnderstandingEngine()
            val utterance = "Wenn ich Zeit habe, erstelle ein Bild."
            val first = languageEngine.understand(
                utterance,
                LanguageContext(now = now),
            )
            val initialPlan = LanguageStateSufficiencyCoordinator()
                .plan(first.goal)
            val conditionNeed = initialPlan.perceptionNeeds.single {
                it.reason == "unresolved-condition"
            }
            val dimension = requireNotNull(conditionNeed.stateDimension)

            repository.save(
                Photon(
                    id = PhotonId("condition-state"),
                    content = "condition state observed",
                    provenance = Provenance(
                        source = "test-state",
                        actor = "lifeos",
                        createdAt = now.minusSeconds(1),
                    ),
                    tags = setOf(
                        "state-dimension:${dimension.value}",
                        "representation:projected",
                        "epistemic:observed",
                    ),
                )
            )

            val journal = InMemoryCognitiveEventJournal()
            val cognition = ContinuousCognitionEngine(
                journal = journal,
                scheduler = CognitiveScheduler(),
            )
            val router = LanguageGoalCapabilityRouter(CapabilityRegistry())
            val actions = GoalActionCoordinator(
                productivePhotonQueries = ProductivePhotonQueryService(repository),
                routeGoal = router::route,
                persistAndIngest = { photon, _ ->
                    repository.save(photon)
                    PhotonSubmissionResult(photon, processingQueued = true)
                },
            )
            val dispatcher = GoalActionDispatcher(
                executeKnowledge = { error("conditional action must not execute") },
                executeDeepSearch = { error("conditional action must not execute") },
                executeImageGeneration = { error("conditional action must not execute") },
                executeImageTransform = { error("conditional action must not execute") },
                executeSchedule = { error("conditional action must not execute") },
                prepareCommunication = { error("conditional action must not execute") },
            )
            val coordinator = ConversationTurnCoordinator(
                revisionedPhotonStore = repository,
                languageContextRetriever = LanguageContextRetriever(repository),
                languageUnderstanding = languageEngine,
                goalPhotonFactory = GoalPhotonFactory(),
                routeGoal = router::route,
                semanticActionGraphRouter = SemanticActionGraphRouter(
                    capabilities = router,
                    dispatcher = dispatcher,
                ),
                goalActions = actions,
                scope = this,
                continuousCognition = cognition,
                persistWithoutCognition = { photon, _ ->
                    repository.save(photon)
                    PhotonSubmissionResult(photon, processingQueued = false)
                },
                persistAndIngest = { photon, _ ->
                    repository.save(photon)
                    PhotonSubmissionResult(photon, processingQueued = true)
                },
                fastBackgroundBudget = CognitiveWorkBudget(
                    maxDurationMs = 5_000,
                    maxModuleInvocations = 4,
                    maxNewPhotons = 4,
                    maxNetworkCalls = 0,
                ),
            )
            val source = Photon(
                id = PhotonId("worldformula-language-source"),
                content = utterance,
                provenance = Provenance(
                    source = "test",
                    actor = "owner",
                    createdAt = now,
                ),
                tags = setOf(
                    "chat",
                    "chat:user",
                    "conversation:worldformula",
                ),
            )

            val result = coordinator.persistUserUtterance(source)
            val trace = requireNotNull(result.worldFormulaLanguage)

            assertTrue(trace.secondPassApplied)
            assertTrue(trace.initialPerceptionNeedCount >= 1)
            assertEquals(StateSufficiencyStatus.SUFFICIENT, trace.finalStateStatus)
            assertTrue(trace.finalInterpretationReady)
            assertTrue(trace.worldEvidenceFingerprint != null)
            assertTrue(
                result.understanding!!.goal.languageRealization.propositions.any {
                    app.lifeos.core.language.LanguageModalStatus.CONDITIONAL in
                        it.modalStatuses
                }
            )
            assertNull(result.externalEffect)
            assertTrue(!trace.executionAuthority)
            assertTrue(!trace.directWorldStateMutationAllowed)
        }

    private class InMemoryRevisionedPhotonRepository : RevisionedPhotonRepository {
        private val photons = linkedMapOf<PhotonId, Photon>()

        override suspend fun save(photon: Photon) {
            photons[photon.id] = photon
        }

        override suspend fun loadAll(): List<Photon> = photons.values.toList()

        override suspend fun delete(id: PhotonId) {
            photons.remove(id)
        }

        override suspend fun load(id: PhotonId): Photon? = photons[id]

        override suspend fun loadReport(): PhotonLoadReport =
            PhotonLoadReport(photons.values.toList(), emptyList())

        override suspend fun load(ref: PhotonRevisionRef): Photon? =
            photons[ref.photonId]?.takeIf { it.revision == ref.revision }

        override suspend fun latestRef(id: PhotonId): PhotonRevisionRef? =
            photons[id]?.let { PhotonRevisionRef(it.id, it.revision) }

        override suspend fun saveRevision(
            photon: Photon,
            expectedPreviousRevision: Long?,
        ): PhotonRevisionWriteResult {
            val previous = photons[photon.id]
            if (previous == null) {
                photons[photon.id] = photon
                return PhotonRevisionWriteResult.Created(photon)
            }
            if (previous == photon) {
                return PhotonRevisionWriteResult.Idempotent(photon, previous)
            }
            photons[photon.id] = photon
            return PhotonRevisionWriteResult.Advanced(photon, previous)
        }

        override suspend fun query(query: PhotonIndexQuery): List<PhotonRevisionRef> {
            val filtered = photons.values.asSequence().filter { photon ->
                (query.ids.isEmpty() || photon.id in query.ids) &&
                    (query.phases.isEmpty() || photon.phase in query.phases) &&
                    (query.mimeTypes.isEmpty() || photon.mimeType in query.mimeTypes) &&
                    photon.tags.containsAll(query.allTags) &&
                    (query.anyTags.isEmpty() || photon.tags.any { it in query.anyTags }) &&
                    photon.tags.none { it in query.excludedTags }
            }
            val sorted = when (query.order) {
                PhotonIndexOrder.NEWEST_FIRST ->
                    filtered.sortedByDescending { it.provenance.createdAt }
                PhotonIndexOrder.OLDEST_FIRST ->
                    filtered.sortedBy { it.provenance.createdAt }
                PhotonIndexOrder.HIGHEST_SEMANTIC_MASS ->
                    filtered.sortedByDescending { it.semanticMass }
                PhotonIndexOrder.HIGHEST_CONFIDENCE ->
                    filtered.sortedByDescending { it.confidence }
                PhotonIndexOrder.IDENTITY ->
                    filtered.sortedBy { it.id.value }
            }
            return sorted
                .take(query.limit)
                .map { PhotonRevisionRef(it.id, it.revision) }
                .toList()
        }

        override suspend fun indexReport(): PhotonIndexReport =
            PhotonIndexReport(
                formatVersion = 1,
                entryCount = photons.size,
                livePhotonCount = photons.size,
                tombstonedPhotonCount = 0,
                latestRefs = photons.values.associate { photon ->
                    photon.id to PhotonRevisionRef(photon.id, photon.revision)
                },
            )
    }
}
