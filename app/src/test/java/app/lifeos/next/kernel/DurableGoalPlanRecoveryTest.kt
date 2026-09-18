package app.lifeos.next.kernel

import app.lifeos.core.language.GoalFrame
import app.lifeos.core.language.IntentType
import app.lifeos.core.language.LanguageCode
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonPhase
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
import app.lifeos.core.runtime.query.GoalOutcomeLookup
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Test

class DurableGoalPlanRecoveryTest {
    private val at = Instant.parse("2026-09-11T12:20:00Z")

    @Test
    fun `restart binds already persisted outcome instead of repeating running action`() = runTest {
        val plans = MemoryGoalPlanRepository()
        val checkpoints = MemoryCheckpointRepository()
        val goal = GoalFrame(
            intent = IntentType.QUERY,
            objective = "Recover this query",
            entities = emptyList(),
            references = emptyList(),
            constraints = emptyList(),
            ambiguities = emptyList(),
            confidence = 1.0,
            language = LanguageCode.EN,
        )
        val routing = GoalCapabilityResolution(
            plan = LanguageGoalCapabilityMapper().plan(goal),
            selectedProviders = emptyMap(),
            gaps = emptyList(),
        )
        val source = Photon(
            id = PhotonId("recovery-source"),
            content = "recover",
            confidence = 1.0,
            provenance = Provenance("unit-test", "user", at),
            tags = setOf("chat"),
        )
        val goalId = PhotonId("recovery-goal")
        val context = GoalActionContext(
            goal = goal,
            routing = routing,
            sourcePhoton = source,
            goalPhotonId = goalId,
        )
        val firstLedger = DurableGoalPlanLedger(plans)
        val firstRuntime = runtime(firstLedger, checkpoints)

        val prepared = firstRuntime.prepare(context)
        assertTrue(prepared is DurableGoalPlanAdmission.Ready)
        assertEquals(
            GoalStepState.RUNNING,
            firstLedger.states.value.values.single().stepStates.values.first { it == GoalStepState.RUNNING },
        )
        assertEquals(1, checkpoints.checkpoints.size)

        // Simulate process death after LocalKnowledgeGoalEngine persisted its result but before V7
        // received the return value and appended the terminal transition.
        val alreadyPersistedOutcome = Photon(
            id = PhotonId("recovery-outcome"),
            content = "persisted answer",
            phase = PhotonPhase.CONVERGED,
            confidence = 1.0,
            provenance = Provenance(
                source = "local-knowledge-resolver",
                actor = "LocalKnowledgeGoalEngine",
                createdAt = at.plusSeconds(1),
                parentIds = setOf(source.id, goalId),
            ),
            tags = setOf("answer", "local-query-answer", "evidence-backed"),
        )

        val restartedLedger = DurableGoalPlanLedger(plans)
        val restartedRuntime = runtime(
            ledger = restartedLedger,
            checkpoints = checkpoints,
            loadPhotons = { listOf(source, alreadyPersistedOutcome) },
        )
        val resumed = restartedRuntime.prepare(context)

        assertTrue(resumed is DurableGoalPlanAdmission.Completed)
        val completed = resumed as DurableGoalPlanAdmission.Completed
        assertSame(alreadyPersistedOutcome, completed.outcome)
        assertEquals(alreadyPersistedOutcome.revision, completed.outcome?.revision)
        assertEquals(1, checkpoints.checkpoints.size)
        val restored = restartedLedger.states.value.values.single()
        assertTrue(restored.stepStates.values.all { it == GoalStepState.COMPLETED })
        assertTrue(restored.outcomePhotonIds.values.contains(alreadyPersistedOutcome.id))
    }

    private fun runtime(
        ledger: DurableGoalPlanLedger,
        checkpoints: MemoryCheckpointRepository,
        loadPhotons: suspend () -> List<Photon> = { emptyList() },
    ) = DurableGoalPlanRuntime(
        ledger = ledger,
        convergence = testGoalConvergenceDecisionSource(checkpoints),
        outcomeLookup = GoalOutcomeLookup { goalPhotonId, limit ->
            loadPhotons()
                .filter { goalPhotonId in it.provenance.parentIds }
                .take(limit)
        },
        now = { at },
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
