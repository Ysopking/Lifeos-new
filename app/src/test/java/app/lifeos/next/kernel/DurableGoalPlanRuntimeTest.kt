package app.lifeos.next.kernel

import app.lifeos.core.language.GoalFrame
import app.lifeos.core.language.IntentType
import app.lifeos.core.language.LanguageCode
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
import app.lifeos.core.runtime.goal.GoalConvergenceDecisionProvider
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
        val durableRuntime = runtime(ledger, checkpointRepository)
        val goal = goal(IntentType.QUERY, "Resolve the local query")
        val routing = routing(goal)
        val source = photon("source-e2e", tags = setOf("chat"))
        val outcome = photon("outcome-e2e", tags = setOf("answer", "result"))
        val context = GoalActionContext(
            goal = goal,
            routing = routing,
            sourcePhoton = source,
            goalPhotonId = PhotonId("goal-e2e"),
            goalPhotonRevision = 1,
        )
        var executions = 0
        val dispatcher = GoalActionDispatcher(
            executeKnowledge = {
                executions += 1
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
        assertEquals(GoalActionDispatchResult(), replay)
        assertEquals(1, checkpointRepository.checkpoints.size)
        assertTrue(ledger.states.value.values.single().stepStates.values.all {
            it == GoalStepState.COMPLETED
        })
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
        assertTrue(ledger.states.value.values.single().stepStates.values.all {
            it == GoalStepState.COMPLETED
        })
        assertTrue(ledger.states.value.values.single().outcomePhotonIds.values.contains(preparation.id))
    }

    private fun runtime(
        ledger: DurableGoalPlanLedger,
        checkpoints: MemoryCheckpointRepository,
        persistDerivedOutcome: suspend (Photon) -> PhotonSubmissionResult? = { null },
    ) = DurableGoalPlanRuntime(
        ledger = ledger,
        convergence = GoalConvergenceDecisionProvider(
            DurableConvergenceDecisionCoordinator(checkpoints)
        ),
        persistDerivedOutcome = persistDerivedOutcome,
        now = { at },
    )

    private fun goal(intent: IntentType, objective: String) = GoalFrame(
        intent = intent,
        objective = objective,
        entities = emptyList(),
        references = emptyList(),
        constraints = emptyList(),
        ambiguities = emptyList(),
        confidence = 1.0,
        language = LanguageCode.EN,
    )

    private fun routing(goal: GoalFrame) = GoalCapabilityResolution(
        plan = LanguageGoalCapabilityMapper().plan(goal),
        selectedProviders = emptyMap(),
        gaps = emptyList(),
    )

    private fun photon(id: String, tags: Set<String>) = Photon(
        id = PhotonId(id),
        content = "test content",
        semanticMass = 1.0,
        energy = 1.0,
        confidence = 1.0,
        provenance = Provenance(
            source = "unit-test",
            actor = "test",
            createdAt = at,
        ),
        tags = tags,
    )

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
