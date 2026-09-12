package app.lifeos.core.runtime.goal

import app.lifeos.core.field.HypothesisId
import app.lifeos.core.language.GoalFrame
import app.lifeos.core.language.IntentType
import app.lifeos.core.language.LanguageCode
import app.lifeos.core.model.PhotonId
import app.lifeos.core.runtime.convergence.ConvergenceDecision
import app.lifeos.core.runtime.convergence.ConvergenceDecisionId
import app.lifeos.core.runtime.convergence.ConvergenceDecisionState
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GoalPlanExecutionCoordinatorTest {
    private val at = Instant.parse("2026-09-11T14:00:00Z")

    @Test
    fun `running is durable before action returns and restart recovers identical action`() = runBlocking {
        val repository = MemoryGoalPlanRepository()
        val blueprint = blueprint()
        val ledger = DurableGoalPlanLedger(repository)
        ledger.create(blueprint.definition)
        val actionStep = actionStep(blueprint)
        val decision = actionable("first")

        val prepared = assertIs<GoalPlanExecutionPreparation.PreparedAction>(
            GoalPlanExecutionCoordinator(ledger).prepareNext(
                blueprint = blueprint,
                decisions = mapOf(actionStep.id to decision),
                at = at,
            )
        )
        assertTrue(!prepared.recovered)
        assertEquals(
            GoalStepState.RUNNING,
            ledger.state(blueprint.definition.id)?.stepStates?.get(actionStep.id),
        )
        assertEquals(prepared.action.actionId.value, ledger.state(blueprint.definition.id)?.activeActionIds?.get(actionStep.id))

        val restartedLedger = DurableGoalPlanLedger(repository)
        restartedLedger.rehydrate()
        val recovered = assertIs<GoalPlanExecutionPreparation.PreparedAction>(
            GoalPlanExecutionCoordinator(restartedLedger).prepareNext(
                blueprint = blueprint,
                decisions = emptyMap(),
                at = at.plusSeconds(10),
            )
        )
        assertTrue(recovered.recovered)
        assertEquals(prepared.action, recovered.action)
        assertEquals(prepared.contract, recovered.contract)
    }

    @Test
    fun `persisted action outcome drives verification and completes plan`() = runBlocking {
        val repository = MemoryGoalPlanRepository()
        val blueprint = blueprint()
        val ledger = DurableGoalPlanLedger(repository)
        ledger.create(blueprint.definition)
        val coordinator = GoalPlanExecutionCoordinator(ledger)
        val actionStep = actionStep(blueprint)
        val prepared = assertIs<GoalPlanExecutionPreparation.PreparedAction>(
            coordinator.prepareNext(
                blueprint,
                mapOf(actionStep.id to actionable("complete")),
                at,
            )
        )
        val outcomeId = PhotonId("outcome:complete")

        val afterOutcome = coordinator.recordOutcome(
            blueprint = blueprint,
            stepId = actionStep.id,
            actionIdempotencyKey = prepared.action.idempotencyKey,
            outcomePhotonId = outcomeId,
            succeeded = true,
            sourceFingerprint = "persisted-outcome-fingerprint",
            at = at.plusSeconds(1),
        )
        assertEquals(GoalStepState.COMPLETED, afterOutcome.stepStates.getValue(actionStep.id))
        assertEquals(outcomeId, afterOutcome.outcomePhotonIds[actionStep.id])

        val verified = assertIs<GoalPlanExecutionPreparation.VerificationCompleted>(
            coordinator.prepareNext(blueprint, emptyMap(), at.plusSeconds(2))
        )
        assertEquals(outcomeId, verified.outcomePhotonId)
        val verifyStep = verifyStep(blueprint)
        assertEquals(verifyStep.id, verified.stepId)
        assertEquals(
            GoalStepState.COMPLETED,
            ledger.state(blueprint.definition.id)?.stepStates?.get(verifyStep.id),
        )
        assertIs<GoalPlanExecutionPreparation.PlanCompleted>(
            coordinator.prepareNext(blueprint, emptyMap(), at.plusSeconds(3))
        )
    }

    @Test
    fun `terminal outcome replay is idempotent but mismatched replay is rejected`() = runBlocking {
        val repository = MemoryGoalPlanRepository()
        val blueprint = blueprint()
        val ledger = DurableGoalPlanLedger(repository)
        ledger.create(blueprint.definition)
        val coordinator = GoalPlanExecutionCoordinator(ledger)
        val actionStep = actionStep(blueprint)
        val prepared = assertIs<GoalPlanExecutionPreparation.PreparedAction>(
            coordinator.prepareNext(blueprint, mapOf(actionStep.id to actionable("replay")), at)
        )
        val outcome = PhotonId("outcome:stable")
        val first = coordinator.recordOutcome(
            blueprint,
            actionStep.id,
            prepared.action.idempotencyKey,
            outcome,
            true,
            "outcome-source",
            at.plusSeconds(1),
        )
        val revision = first.revision
        val replay = coordinator.recordOutcome(
            blueprint,
            actionStep.id,
            prepared.action.idempotencyKey,
            outcome,
            true,
            "outcome-source",
            at.plusSeconds(2),
        )
        assertEquals(revision, replay.revision)
        assertFailsWith<IllegalArgumentException> {
            coordinator.recordOutcome(
                blueprint,
                actionStep.id,
                prepared.action.idempotencyKey,
                PhotonId("outcome:different"),
                true,
                "outcome-source",
                at.plusSeconds(3),
            )
        }
    }

    @Test
    fun `non actionable convergence remains an explicit wait state without action`() = runBlocking {
        val repository = MemoryGoalPlanRepository()
        val blueprint = blueprint()
        val ledger = DurableGoalPlanLedger(repository)
        ledger.create(blueprint.definition)
        val actionStep = actionStep(blueprint)
        val result = assertIs<GoalPlanExecutionPreparation.Waiting>(
            GoalPlanExecutionCoordinator(ledger).prepareNext(
                blueprint,
                mapOf(actionStep.id to decision(ConvergenceDecisionState.EVIDENCE_REQUIRED, "evidence")),
                at,
            )
        )
        assertEquals(GoalStepState.WAITING_EVIDENCE, result.state)
        assertEquals(emptyMap(), ledger.state(blueprint.definition.id)?.activeActionIds)
    }

    @Test
    fun `expired ready candidate becomes replan required`() = runBlocking {
        val repository = MemoryGoalPlanRepository()
        val built = GoalPlanBuilder().build(
            goal = goal(),
            sourceGoalPhotonId = PhotonId("goal:deadline"),
            sourceGoalPhotonRevision = 1,
            createdAt = at.minusSeconds(60),
            deadline = at.minusSeconds(1),
        )
        val blueprint = assertIs<GoalPlanBuildResult.Built>(built).blueprint
        val ledger = DurableGoalPlanLedger(repository)
        ledger.create(blueprint.definition)

        val result = assertIs<GoalPlanExecutionPreparation.ReplanRequired>(
            GoalPlanExecutionCoordinator(ledger).prepareNext(
                blueprint,
                emptyMap(),
                at,
            )
        )
        assertEquals(actionStep(blueprint).id, result.stepId)
        assertEquals(
            GoalStepState.REPLAN_REQUIRED,
            ledger.state(blueprint.definition.id)?.stepStates?.get(result.stepId),
        )
    }

    private fun blueprint(): GoalPlanBlueprint = assertIs<GoalPlanBuildResult.Built>(
        GoalPlanBuilder().build(
            goal = goal(),
            sourceGoalPhotonId = PhotonId("goal:query"),
            sourceGoalPhotonRevision = 2,
            createdAt = at.minusSeconds(10),
        )
    ).blueprint

    private fun goal() = GoalFrame(
        intent = IntentType.QUERY,
        objective = "Resolve durable query",
        entities = emptyList(),
        references = emptyList(),
        constraints = emptyList(),
        ambiguities = emptyList(),
        confidence = 1.0,
        language = LanguageCode.EN,
    )

    private fun actionStep(blueprint: GoalPlanBlueprint): GoalStepDefinition =
        blueprint.definition.steps.single { it.key.startsWith("action:") }

    private fun verifyStep(blueprint: GoalPlanBlueprint): GoalStepDefinition =
        blueprint.definition.steps.single { it.key == "verify:outcome" }

    private fun actionable(suffix: String): ConvergenceDecision =
        decision(ConvergenceDecisionState.ACTIONABLE, suffix)

    private fun decision(
        state: ConvergenceDecisionState,
        suffix: String,
    ): ConvergenceDecision = ConvergenceDecision(
        id = ConvergenceDecisionId("convergence-decision-$suffix"),
        state = state,
        selectedHypothesisIds = if (state == ConvergenceDecisionState.ACTIONABLE) {
            listOf(HypothesisId("hypothesis-$suffix"))
        } else {
            emptyList()
        },
        candidates = emptyList(),
        evidenceRequests = emptyList(),
        capabilityGaps = emptyList(),
        escalation = null,
        reasons = listOf("test-$suffix"),
        sourceFingerprint = "source-fingerprint-$suffix",
    )

    private class MemoryGoalPlanRepository : GoalPlanRepository {
        private val definitions = linkedMapOf<GoalPlanId, GoalPlanDefinition>()
        private val transitions = linkedMapOf<GoalTransitionId, GoalPlanTransition>()

        override suspend fun saveDefinition(
            definition: GoalPlanDefinition,
        ): GoalPlanDefinitionWriteResult {
            val existing = definitions[definition.id]
            if (existing != null) {
                require(existing == definition)
                return GoalPlanDefinitionWriteResult.Duplicate(existing)
            }
            definitions[definition.id] = definition
            return GoalPlanDefinitionWriteResult.Stored(definition)
        }

        override suspend fun loadDefinition(id: GoalPlanId): GoalPlanDefinition? = definitions[id]

        override suspend fun saveTransition(
            transition: GoalPlanTransition,
        ): GoalPlanTransitionWriteResult {
            val existing = transitions[transition.id]
            if (existing != null) {
                require(existing == transition)
                return GoalPlanTransitionWriteResult.Duplicate(existing)
            }
            transitions[transition.id] = transition
            return GoalPlanTransitionWriteResult.Stored(transition)
        }

        override suspend fun loadTransitions(planId: GoalPlanId): List<GoalPlanTransition> =
            transitions.values.filter { it.planId == planId }.reversed()

        override suspend fun loadReport(): GoalPlanRepositoryLoadReport = GoalPlanRepositoryLoadReport(
            definitions = definitions.values.toList(),
            transitions = transitions.values.reversed(),
            unreadableEntries = emptyList(),
        )
    }
}
