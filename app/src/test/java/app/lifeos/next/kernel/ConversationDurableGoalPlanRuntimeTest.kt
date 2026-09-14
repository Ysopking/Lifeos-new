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
import app.lifeos.core.runtime.goal.LocalConversationGoalEngine
import app.lifeos.core.runtime.goal.LocalConversationGoalResult
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationDurableGoalPlanRuntimeTest {
    private val at = Instant.parse("2026-09-14T12:40:00Z")

    @After
    fun clearRegistry() {
        ConversationExecutionResultRegistry.clear()
    }

    @Test
    fun `conversation response is persisted before durable plan completes and exposed to chat`() = runTest {
        val goalRepository = MemoryGoalPlanRepository()
        val checkpointRepository = MemoryCheckpointRepository()
        val ledger = DurableGoalPlanLedger(goalRepository)
        val source = photon(
            id = "conversation-source",
            content = "Hallo",
            tags = setOf("chat", "chat:user"),
        )
        val persisted = mutableListOf(source)
        val durableRuntime = runtime(
            ledger = ledger,
            checkpoints = checkpointRepository,
            persisted = persisted,
        )
        val goal = conversationGoal("conversation: Hallo")
        val goalId = PhotonId("conversation-goal")
        val context = GoalActionContext(
            goal = goal,
            routing = routing(goal),
            sourcePhoton = source,
            goalPhotonId = goalId,
        )
        val dispatcher = dispatcher(durableRuntime)

        val result = dispatcher.execute(context)

        val produced = result.localConversation as? LocalConversationExecutionResult.Produced
        assertNotNull(produced)
        assertEquals("Hallo. Ich bin bereit.", produced!!.photon.content)
        assertEquals(1, persisted.count { "local-conversation-response" in it.tags })
        assertTrue(ledger.states.value.values.single().stepStates.values.all {
            it == GoalStepState.COMPLETED
        })
        assertEquals(
            produced.photon.id,
            ledger.states.value.values.single().outcomePhotonIds.values.single(),
        )
        assertEquals(produced, ConversationExecutionResultRegistry.current(goalId))

        val replay = dispatcher.execute(context)
        assertEquals(GoalActionDispatchResult(), replay)
        assertEquals(1, persisted.count { "local-conversation-response" in it.tags })
    }

    @Test
    fun `restart recovers already persisted conversation outcome without producing a duplicate`() = runTest {
        val goalRepository = MemoryGoalPlanRepository()
        val checkpointRepository = MemoryCheckpointRepository()
        val ledger = DurableGoalPlanLedger(goalRepository)
        val source = photon(
            id = "restart-source",
            content = "Hallo",
            tags = setOf("chat", "chat:user"),
        )
        val persisted = mutableListOf(source)
        val firstRuntime = runtime(
            ledger = ledger,
            checkpoints = checkpointRepository,
            persisted = persisted,
        )
        val goal = conversationGoal("conversation: Hallo")
        val goalId = PhotonId("restart-goal")
        val context = GoalActionContext(
            goal = goal,
            routing = routing(goal),
            sourcePhoton = source,
            goalPhotonId = goalId,
        )

        val admission = firstRuntime.prepare(context)
        assertTrue(admission is DurableGoalPlanAdmission.Ready)
        assertTrue(ledger.states.value.values.single().stepStates.values.contains(GoalStepState.RUNNING))

        val planned = LocalConversationGoalEngine().execute(
            goal = goal,
            sourcePhoton = source,
            goalPhotonId = goalId,
            photons = persisted,
            createdAt = at,
        ) as LocalConversationGoalResult.Produced
        // Simulated process death window: canonical response persistence succeeded, but the V7
        // outcome transition did not run yet.
        persisted += planned.photon

        val recoveredRuntime = runtime(
            ledger = ledger,
            checkpoints = checkpointRepository,
            persisted = persisted,
        )
        val recovered = dispatcher(recoveredRuntime).execute(context)

        assertEquals(GoalActionDispatchResult(), recovered)
        assertEquals(1, persisted.count { "local-conversation-response" in it.tags })
        assertTrue(ledger.states.value.values.single().stepStates.values.all {
            it == GoalStepState.COMPLETED
        })
        assertEquals(
            planned.photon.id,
            ledger.states.value.values.single().outcomePhotonIds.values.single(),
        )
    }

    private fun dispatcher(durableRuntime: DurableGoalPlanRuntime) = GoalActionDispatcher(
        executeKnowledge = { error("unexpected knowledge") },
        executeDeepSearch = { error("unexpected DeepSearch") },
        executeImageGeneration = { error("unexpected image generation") },
        executeImageTransform = { error("unexpected image transform") },
        executeSchedule = { error("unexpected schedule") },
        prepareCommunication = { error("unexpected communication") },
        executionGuard = PassThroughGoalActionExecutionGuard,
        durableRuntimeProvider = { durableRuntime },
    )

    private fun runtime(
        ledger: DurableGoalPlanLedger,
        checkpoints: MemoryCheckpointRepository,
        persisted: MutableList<Photon>,
    ) = DurableGoalPlanRuntime(
        ledger = ledger,
        convergence = GoalConvergenceDecisionProvider(
            DurableConvergenceDecisionCoordinator(checkpoints)
        ),
        persistDerivedOutcome = { photon ->
            if (persisted.none { it.id == photon.id }) persisted += photon
            PhotonSubmissionResult(photon, processingQueued = true)
        },
        loadPersistedPhotons = { persisted.toList() },
        now = { at },
    )

    private fun conversationGoal(objective: String) = GoalFrame(
        intent = IntentType.CONVERSATION,
        objective = objective,
        entities = emptyList(),
        references = emptyList(),
        constraints = emptyList(),
        ambiguities = emptyList(),
        confidence = 1.0,
        language = LanguageCode.DE,
    )

    private fun routing(goal: GoalFrame) = GoalCapabilityResolution(
        plan = LanguageGoalCapabilityMapper().plan(goal),
        selectedProviders = emptyMap(),
        gaps = emptyList(),
    )

    private fun photon(
        id: String,
        content: String,
        tags: Set<String>,
    ) = Photon(
        id = PhotonId(id),
        content = content,
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
        private val definitions = linkedMapOf<GoalPlanId, GoalPlanDefinition>()
        private val transitions = linkedMapOf<GoalTransitionId, GoalPlanTransition>()

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
        private val checkpoints = linkedMapOf<ConvergenceDecisionCheckpointId, ConvergenceDecisionCheckpoint>()

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
