package app.lifeos.next.kernel

import app.lifeos.core.language.GoalFrame
import app.lifeos.core.language.IntentType
import app.lifeos.core.language.LanguageCode
import app.lifeos.core.language.LanguageUnderstandingEngine
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.Provenance
import app.lifeos.core.runtime.capability.GoalCapabilityResolution
import app.lifeos.core.runtime.capability.LanguageGoalCapabilityMapper
import app.lifeos.core.runtime.convergence.ConvergenceDecisionCheckpoint
import app.lifeos.core.runtime.convergence.ConvergenceDecisionCheckpointId
import app.lifeos.core.runtime.convergence.ConvergenceDecisionCheckpointLoadReport
import app.lifeos.core.runtime.convergence.ConvergenceDecisionCheckpointRepository
import app.lifeos.core.runtime.convergence.ConvergenceDecisionCheckpointWriteResult
import app.lifeos.core.runtime.convergence.DurableConvergenceDecisionCoordinator
import app.lifeos.core.runtime.goal.DurableGoalPlanLedger
import app.lifeos.core.runtime.goal.GoalCognitiveCycleBindingRecord
import app.lifeos.core.runtime.goal.GoalCognitiveCycleBindingRepository
import app.lifeos.core.runtime.goal.GoalCognitiveCycleBindingState
import app.lifeos.core.runtime.goal.GoalConvergenceCycleBinding
import app.lifeos.core.runtime.goal.GoalConvergenceDecisionProvider
import app.lifeos.core.runtime.goal.GoalConvergenceDecisionResult
import app.lifeos.core.runtime.goal.GoalConvergenceDecisionSource
import app.lifeos.core.runtime.goal.GoalOutcomeLearningHook
import app.lifeos.core.runtime.goal.GoalOutcomeLearningReceipt
import app.lifeos.core.runtime.goal.GoalPlanDefinition
import app.lifeos.core.runtime.goal.GoalPlanDefinitionWriteResult
import app.lifeos.core.runtime.goal.GoalPlanId
import app.lifeos.core.runtime.goal.GoalPlanRepository
import app.lifeos.core.runtime.goal.GoalPlanRepositoryLoadReport
import app.lifeos.core.runtime.goal.GoalPlanTransition
import app.lifeos.core.runtime.goal.GoalPlanTransitionWriteResult
import app.lifeos.core.runtime.goal.GoalStepState
import app.lifeos.core.runtime.goal.GoalTransitionId
import app.lifeos.core.runtime.goal.LocalKnowledgeGoalKind
import app.lifeos.core.runtime.goal.LocalShareKind
import app.lifeos.core.runtime.goal.LocalSharePreparation
import app.lifeos.core.runtime.query.GoalOutcomeLookup
import app.lifeos.core.runtime.world.CognitiveCycleId
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DurableGoalPlanRuntimeTest {
    private val at = Instant.parse("2026-09-11T12:10:00Z")

    @Test
    fun `dispatcher persists plan V5 decision running outcome and verification without duplicate action`() = runTest {
        val goalRepository = MemoryGoalPlanRepository()
        val checkpointRepository = MemoryCheckpointRepository()
        val ledger = DurableGoalPlanLedger(goalRepository)
        val persisted = mutableListOf<Photon>()
        val durableRuntime = runtime(
            ledger = ledger,
            checkpoints = checkpointRepository,
            loadPersistedPhotons = { persisted.toList() },
        )
        val goal = goal(IntentType.QUERY, "Resolve the local query")
        val routing = routing(goal)
        val goalId = PhotonId("goal-e2e")
        val source = photon("source-e2e", tags = setOf("chat"))
        val outcome = photon(
            id = "outcome-e2e",
            tags = setOf("answer", "result", "local-query-answer"),
            parentIds = setOf(goalId),
        )
        val context = GoalActionContext(
            goal = goal,
            routing = routing,
            sourcePhoton = source,
            goalPhotonId = goalId,
            goalPhotonRevision = 1,
        )
        var executions = 0
        val dispatcher = GoalActionDispatcher(
            executeKnowledge = {
                executions += 1
                persisted += outcome
                LocalKnowledgeExecutionResult.Produced(
                    kind = LocalKnowledgeGoalKind.QUERY_ANSWER,
                    output = PhotonSubmissionResult(outcome, processingQueued = true),
                    evidencePhotonIds = emptyList(),
                )
            },
            executeDeepSearch = { error("unexpected DeepSearch") },
            executeImageGeneration = { error("unexpected image generation") },
            executeImageTransform = { error("unexpected image transform") },
            executeSchedule = { error("unexpected schedule") },
            prepareCommunication = { error("unexpected communication") },
            executionGuard = PassThroughGoalActionExecutionGuard,
            durableRuntimeProvider = { durableRuntime },
        )

        val first = dispatcher.execute(context)

        assertNotNull(first.localKnowledge as? LocalKnowledgeExecutionResult.Produced)
        assertEquals(1, executions)
        assertEquals(1, checkpointRepository.checkpoints.size)
        val state = ledger.states.value.values.single()
        assertTrue(state.stepStates.values.all { it == GoalStepState.COMPLETED })
        assertEquals(outcome.id, state.outcomePhotonIds.values.first())
        assertTrue(goalRepository.transitions.values.any { it.toState == GoalStepState.RUNNING })
        assertTrue(goalRepository.transitions.values.any {
            it.toState == GoalStepState.COMPLETED && it.outcomePhotonId == outcome.id
        })

        val replay = dispatcher.execute(context)

        assertEquals(1, executions)
        assertEquals(outcome, replay.recoveredOutcome)
        assertEquals(1, checkpointRepository.checkpoints.size)
        assertTrue(ledger.states.value.values.single().stepStates.values.all {
            it == GoalStepState.COMPLETED
        })
    }

    @Test
    fun `productive goal outcome is learned exactly once from durable cycle lineage`() = runTest {
        val goalRepository = MemoryGoalPlanRepository()
        val checkpointRepository = MemoryCheckpointRepository()
        val ledger = DurableGoalPlanLedger(goalRepository)
        val persisted = mutableListOf<Photon>()
        val bindings = MemoryGoalCognitiveCycleBindingRepository()
        var learningCalls = 0
        val baseConvergence = testGoalConvergenceDecisionSource(checkpointRepository)
        val boundConvergence = object : GoalConvergenceDecisionSource {
            override suspend fun decide(
                goal: GoalFrame,
                routing: GoalCapabilityResolution,
                sourcePhoton: Photon,
                goalPhotonId: PhotonId,
                goalPhotonRevision: Long,
                at: Instant,
            ): ConvergenceDecisionCheckpoint =
                baseConvergence.decide(
                    goal = goal,
                    routing = routing,
                    sourcePhoton = sourcePhoton,
                    goalPhotonId = goalPhotonId,
                    goalPhotonRevision = goalPhotonRevision,
                    at = at,
                )

            override suspend fun decideBound(
                goal: GoalFrame,
                routing: GoalCapabilityResolution,
                sourcePhoton: Photon,
                goalPhotonId: PhotonId,
                goalPhotonRevision: Long,
                at: Instant,
            ): GoalConvergenceDecisionResult = GoalConvergenceDecisionResult(
                checkpoint = decide(
                    goal = goal,
                    routing = routing,
                    sourcePhoton = sourcePhoton,
                    goalPhotonId = goalPhotonId,
                    goalPhotonRevision = goalPhotonRevision,
                    at = at,
                ),
                cycleBinding = GoalConvergenceCycleBinding(
                    cycleId = CognitiveCycleId("cycle:test-goal-learning"),
                    sourceWorldSnapshotId = "world:test-before",
                    equationVersion = "lifeos-world-cognitive-v1",
                ),
            )
        }
        val learning = GoalOutcomeLearningHook { binding, outcome, succeeded ->
            learningCalls += 1
            assertEquals("world:test-before", binding.sourceWorldSnapshotId)
            assertEquals("lifeos-world-cognitive-v1", binding.equationVersion)
            assertEquals("outcome-learning", outcome.id.value)
            assertTrue(succeeded)
            GoalOutcomeLearningReceipt(
                outcomeWorldSnapshotId = "world:test-after",
                learningWatermarkRevision = 17L,
            )
        }
        val runtime = DurableGoalPlanRuntime(
            ledger = ledger,
            convergence = boundConvergence,
            persistDerivedOutcome = { null },
            outcomeLookup = GoalOutcomeLookup { goalPhotonId, limit ->
                persisted.filter { goalPhotonId in it.provenance.parentIds }.take(limit)
            },
            cognitiveBindings = bindings,
            outcomeLearning = learning,
            now = { at },
        )
        val goal = goal(IntentType.QUERY, "Learn from this local query")
        val goalId = PhotonId("goal-learning")
        val source = photon("source-learning", tags = setOf("chat"))
        val outcome = photon(
            id = "outcome-learning",
            tags = setOf("answer", "result", "local-query-answer"),
            parentIds = setOf(goalId),
        )
        val context = GoalActionContext(
            goal = goal,
            routing = routing(goal),
            sourcePhoton = source,
            goalPhotonId = goalId,
        )
        val dispatcher = GoalActionDispatcher(
            executeKnowledge = {
                persisted += outcome
                LocalKnowledgeExecutionResult.Produced(
                    kind = LocalKnowledgeGoalKind.QUERY_ANSWER,
                    output = PhotonSubmissionResult(outcome, processingQueued = true),
                    evidencePhotonIds = emptyList(),
                )
            },
            executeDeepSearch = { error("unexpected DeepSearch") },
            executeImageGeneration = { error("unexpected image generation") },
            executeImageTransform = { error("unexpected image transform") },
            executeSchedule = { error("unexpected schedule") },
            prepareCommunication = { error("unexpected communication") },
            executionGuard = PassThroughGoalActionExecutionGuard,
            durableRuntimeProvider = { runtime },
        )

        dispatcher.execute(context)
        assertEquals(1, learningCalls)
        val record = requireNotNull(bindings.single())
        assertEquals(GoalCognitiveCycleBindingState.LEARNED, record.state)
        assertEquals(outcome.id, record.outcomePhotonId)
        assertEquals("world:test-after", record.outcomeWorldSnapshotId)
        assertEquals(17L, record.learningWatermarkRevision)

        val replay = dispatcher.execute(context)
        assertEquals(outcome, replay.recoveredOutcome)
        assertEquals(1, learningCalls)
        assertEquals(GoalCognitiveCycleBindingState.LEARNED, requireNotNull(bindings.single()).state)
    }

    @Test
    fun `communication completes on persisted preparation and never claims delivery`() = runTest {
        val goalRepository = MemoryGoalPlanRepository()
        val checkpointRepository = MemoryCheckpointRepository()
        val ledger = DurableGoalPlanLedger(goalRepository)
        val persisted = mutableListOf<Photon>()
        val durableRuntime = runtime(
            ledger = ledger,
            checkpoints = checkpointRepository,
            persistDerivedOutcome = { photon ->
                persisted += photon
                PhotonSubmissionResult(photon, processingQueued = true)
            },
        )
        val goal = goal(IntentType.COMMUNICATE, "Prepare this result for sharing")
        val source = photon("source-share", tags = setOf("chat"))
        val target = photon("share-target", tags = setOf("answer", "result"))
        val goalId = PhotonId("goal-share")
        val share = LocalSharePreparation(
            requestSourceId = source.id,
            requestGoalId = goalId,
            target = target,
            kind = LocalShareKind.TEXT,
        )
        val dispatcher = GoalActionDispatcher(
            executeKnowledge = { error("unexpected knowledge") },
            executeDeepSearch = { error("unexpected DeepSearch") },
            executeImageGeneration = { error("unexpected image generation") },
            executeImageTransform = { error("unexpected image transform") },
            executeSchedule = { error("unexpected schedule") },
            prepareCommunication = { LocalCommunicationExecutionResult.Prepared(share) },
            executionGuard = PassThroughGoalActionExecutionGuard,
            durableRuntimeProvider = { durableRuntime },
        )

        val result = dispatcher.execute(
            GoalActionContext(
                goal = goal,
                routing = routing(goal),
                sourcePhoton = source,
                goalPhotonId = goalId,
            )
        )

        assertNotNull(result.localCommunication as? LocalCommunicationExecutionResult.Prepared)
        assertEquals(1, persisted.size)
        val preparation = persisted.single()
        assertTrue("share-preparation" in preparation.tags)
        assertTrue("status=prepared" in preparation.content)
        assertTrue("delivered" !in preparation.content)
        assertTrue(
            "Communication preparation Photon id must be accepted by EncryptedPhotonStore",
            preparation.id.value.matches(Regex("[A-Za-z0-9_-]{1,128}")),
        )
        assertTrue(preparation.id.value.startsWith("communication-preparation-"))
        assertTrue(ledger.states.value.values.single().stepStates.values.all {
            it == GoalStepState.COMPLETED
        })
        assertTrue(ledger.states.value.values.single().outcomePhotonIds.values.contains(preparation.id))
    }

    private fun runtime(
        ledger: DurableGoalPlanLedger,
        checkpoints: MemoryCheckpointRepository,
        persistDerivedOutcome: suspend (Photon) -> PhotonSubmissionResult? = { null },
        loadPersistedPhotons: suspend () -> List<Photon> = { emptyList() },
    ) = DurableGoalPlanRuntime(
        ledger = ledger,
        convergence = testGoalConvergenceDecisionSource(checkpoints),
        persistDerivedOutcome = persistDerivedOutcome,
        outcomeLookup = GoalOutcomeLookup { goalPhotonId, limit ->
            loadPersistedPhotons()
                .filter { goalPhotonId in it.provenance.parentIds }
                .take(limit)
        },
        now = { at },
    )

    private fun goal(intent: IntentType, objective: String): GoalFrame {
        val text = when (intent) {
            IntentType.QUERY -> "What is LIFEOS?"
            IntentType.COMMUNICATE -> "Send the report."
            else -> error("Unsupported test intent: " + intent)
        }
        return LanguageUnderstandingEngine()
            .understand(text)
            .goal
            .copy(
                objective = objective,
                confidence = 1.0,
            )
    }

    private fun routing(goal: GoalFrame) = GoalCapabilityResolution(
        plan = LanguageGoalCapabilityMapper().plan(goal),
        selectedProviders = emptyMap(),
        gaps = emptyList(),
    )

    private fun photon(
        id: String,
        tags: Set<String>,
        parentIds: Set<PhotonId> = emptySet(),
    ) = Photon(
        id = PhotonId(id),
        content = "test content",
        semanticMass = 1.0,
        energy = 1.0,
        confidence = 1.0,
        provenance = Provenance(
            source = "unit-test",
            actor = "test",
            createdAt = at,
            parentIds = parentIds,
        ),
        tags = tags,
    )

    private class MemoryGoalCognitiveCycleBindingRepository :
        GoalCognitiveCycleBindingRepository {
        private val records = linkedMapOf<GoalPlanId, GoalCognitiveCycleBindingRecord>()

        override suspend fun load(planId: GoalPlanId): GoalCognitiveCycleBindingRecord? =
            records[planId]

        override suspend fun compareAndSet(
            planId: GoalPlanId,
            expectedRevision: Long?,
            next: GoalCognitiveCycleBindingRecord,
        ): Boolean {
            if (records[planId]?.revision != expectedRevision) return false
            require(next.planId == planId)
            records[planId] = next
            return true
        }

        fun single(): GoalCognitiveCycleBindingRecord? = records.values.singleOrNull()
    }

    private class MemoryGoalPlanRepository : GoalPlanRepository {
        val definitions = linkedMapOf<GoalPlanId, GoalPlanDefinition>()
        val transitions = linkedMapOf<GoalTransitionId, GoalPlanTransition>()

        override suspend fun saveDefinition(
            definition: GoalPlanDefinition,
        ): GoalPlanDefinitionWriteResult {
            val previous = definitions.putIfAbsent(definition.id, definition)
            return if (previous == null) {
                GoalPlanDefinitionWriteResult.Stored(definition)
            } else {
                require(previous == definition)
                GoalPlanDefinitionWriteResult.Duplicate(previous)
            }
        }

        override suspend fun loadDefinition(id: GoalPlanId): GoalPlanDefinition? = definitions[id]

        override suspend fun saveTransition(
            transition: GoalPlanTransition,
        ): GoalPlanTransitionWriteResult {
            val previous = transitions.putIfAbsent(transition.id, transition)
            return if (previous == null) {
                GoalPlanTransitionWriteResult.Stored(transition)
            } else {
                require(previous == transition)
                GoalPlanTransitionWriteResult.Duplicate(previous)
            }
        }

        override suspend fun loadTransitions(planId: GoalPlanId): List<GoalPlanTransition> =
            transitions.values.filter { it.planId == planId }

        override suspend fun loadReport(): GoalPlanRepositoryLoadReport = GoalPlanRepositoryLoadReport(
            definitions = definitions.values.toList(),
            transitions = transitions.values.toList(),
            unreadableEntries = emptyList(),
        )
    }

    private class MemoryCheckpointRepository : ConvergenceDecisionCheckpointRepository {
        val checkpoints = linkedMapOf<ConvergenceDecisionCheckpointId, ConvergenceDecisionCheckpoint>()

        override suspend fun save(
            checkpoint: ConvergenceDecisionCheckpoint,
        ): ConvergenceDecisionCheckpointWriteResult {
            val previous = checkpoints.putIfAbsent(checkpoint.id, checkpoint)
            return if (previous == null) {
                ConvergenceDecisionCheckpointWriteResult.Stored(checkpoint)
            } else {
                require(previous == checkpoint)
                ConvergenceDecisionCheckpointWriteResult.Duplicate(previous)
            }
        }

        override suspend fun load(
            id: ConvergenceDecisionCheckpointId,
        ): ConvergenceDecisionCheckpoint? = checkpoints[id]

        override suspend fun loadReport(): ConvergenceDecisionCheckpointLoadReport =
            ConvergenceDecisionCheckpointLoadReport(
                checkpoints = checkpoints.values.toList(),
                unreadableEntries = emptyList(),
            )
    }
}
