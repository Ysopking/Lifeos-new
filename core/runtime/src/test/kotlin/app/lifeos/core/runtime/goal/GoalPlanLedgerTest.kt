package app.lifeos.core.runtime.goal

import app.lifeos.core.model.PhotonId
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GoalPlanLedgerTest {
    private val at = Instant.parse("2026-09-11T12:00:00Z")

    @Test
    fun `definition and transition codecs round trip exact canonical content`() {
        val plan = plan()
        val first = plan.steps.single { it.key == "first" }
        val transition = GoalPlanTransition.create(
            planId = plan.id,
            predecessorId = null,
            stepId = first.id,
            fromState = GoalStepState.PLANNED,
            toState = GoalStepState.READY,
            reason = "dependencies-satisfied",
            sourceFingerprint = "source-fingerprint",
            decisionFingerprint = "decision-fingerprint",
            createdAt = at.plusSeconds(1),
        )

        assertEquals(plan, GoalPlanDefinitionCodec.decode(GoalPlanDefinitionCodec.encode(plan)))
        assertEquals(
            transition,
            GoalPlanTransitionCodec.decode(GoalPlanTransitionCodec.encode(transition)),
        )
    }

    @Test
    fun `durable ledger persists before publishing runtime state and rehydrates physical disorder`() = runBlocking {
        val repository = RecordingRepository()
        val ledger = DurableGoalPlanLedger(repository)
        val plan = plan()
        val first = plan.steps.single { it.key == "first" }
        val created = ledger.create(plan)
        assertEquals(0L, created.revision)
        assertEquals(listOf("definition:${plan.id.value}"), repository.writeOrder)

        val ready = transition(plan, null, first, GoalStepState.PLANNED, GoalStepState.READY, 1)
        val running = transition(
            plan,
            ready.id,
            first,
            GoalStepState.READY,
            GoalStepState.RUNNING,
            2,
            actionId = "action-first",
            idempotencyKey = "stable-idempotency-key",
        )
        val completed = transition(
            plan,
            running.id,
            first,
            GoalStepState.RUNNING,
            GoalStepState.COMPLETED,
            3,
            actionId = "action-first",
            outcomePhotonId = PhotonId("outcome:first"),
        )
        ledger.append(ready)
        ledger.append(running)
        ledger.append(completed)
        val finalState = requireNotNull(ledger.state(plan.id))
        assertEquals(3L, finalState.revision)
        assertEquals(GoalStepState.COMPLETED, finalState.stepStates.getValue(first.id))

        repository.returnTransitionsReversed = true
        val restoredLedger = DurableGoalPlanLedger(repository)
        val report = restoredLedger.rehydrate()
        assertEquals(1, report.restoredPlans)
        assertEquals(3, report.restoredTransitions)
        assertEquals(finalState, restoredLedger.state(plan.id))
    }

    @Test
    fun `definition persistence failure cannot leak a RAM plan`() {
        val repository = RecordingRepository(failDefinitionWrite = true)
        val ledger = DurableGoalPlanLedger(repository)
        val plan = plan()

        assertFailsWith<IllegalStateException> {
            runBlocking { ledger.create(plan) }
        }
        assertTrue(ledger.states.value.isEmpty())
    }

    @Test
    fun `transition persistence failure cannot advance RAM state`() = runBlocking {
        val repository = RecordingRepository()
        val ledger = DurableGoalPlanLedger(repository)
        val plan = plan()
        ledger.create(plan)
        val first = plan.steps.single { it.key == "first" }
        val ready = transition(plan, null, first, GoalStepState.PLANNED, GoalStepState.READY, 1)
        repository.failTransitionWrite = true

        assertFailsWith<IllegalStateException> {
            runBlocking { ledger.append(ready) }
        }
        val state = requireNotNull(ledger.state(plan.id))
        assertEquals(0L, state.revision)
        assertEquals(GoalStepState.PLANNED, state.stepStates.getValue(first.id))
    }

    @Test
    fun `rehydrate fails closed on unreadable or orphaned transition history`() {
        val corrupted = RecordingRepository(unreadable = listOf("transitions/bad.gtransition"))
        assertFailsWith<IllegalArgumentException> {
            runBlocking { DurableGoalPlanLedger(corrupted).rehydrate() }
        }

        val plan = plan()
        val first = plan.steps.single { it.key == "first" }
        val orphan = transition(plan, null, first, GoalStepState.PLANNED, GoalStepState.READY, 1)
        val orphanRepository = RecordingRepository().apply {
            transitions[orphan.id] = orphan
        }
        assertFailsWith<IllegalArgumentException> {
            runBlocking { DurableGoalPlanLedger(orphanRepository).rehydrate() }
        }
    }

    @Test
    fun `recreating a persisted plan restores progress instead of resetting it`() = runBlocking {
        val repository = RecordingRepository()
        val ledger = DurableGoalPlanLedger(repository)
        val plan = plan()
        ledger.create(plan)
        val first = plan.steps.single { it.key == "first" }
        ledger.append(transition(plan, null, first, GoalStepState.PLANNED, GoalStepState.READY, 1))

        val restarted = DurableGoalPlanLedger(repository)
        assertEquals(ledger.state(plan.id), restarted.create(plan))
        assertEquals(2, repository.writeOrder.size)
    }

    @Test
    fun `invalid and stale transitions never enter durable history`() = runBlocking {
        val repository = RecordingRepository()
        val ledger = DurableGoalPlanLedger(repository)
        val plan = plan()
        ledger.create(plan)
        val first = plan.steps.single { it.key == "first" }
        val illegal = transition(
            plan, null, first, GoalStepState.PLANNED, GoalStepState.RUNNING, 1,
            actionId = "action", idempotencyKey = "key",
        )
        assertFailsWith<IllegalArgumentException> { ledger.append(illegal) }
        assertTrue(repository.transitions.isEmpty())

        val ready = transition(plan, null, first, GoalStepState.PLANNED, GoalStepState.READY, 2)
        ledger.append(ready)
        val stale = transition(plan, null, first, GoalStepState.PLANNED, GoalStepState.PAUSED, 3)
        assertFailsWith<IllegalArgumentException> { ledger.append(stale) }
        assertEquals(listOf(ready), repository.transitions.values.toList())
        assertEquals(ledger.state(plan.id), DurableGoalPlanLedger(repository).create(plan))
    }

    @Test
    fun `lost write acknowledgement cannot fork durable history on retry`() = runBlocking {
        val repository = RecordingRepository()
        val ledger = DurableGoalPlanLedger(repository)
        val plan = plan()
        ledger.create(plan)
        val first = plan.steps.single { it.key == "first" }
        val ready = transition(plan, null, first, GoalStepState.PLANNED, GoalStepState.READY, 1)
        repository.failAfterTransitionWrite = true
        assertFailsWith<IllegalStateException> { ledger.append(ready) }
        assertEquals(0L, ledger.state(plan.id)?.revision)
        assertEquals(listOf(ready), repository.transitions.values.toList())

        val stale = transition(plan, null, first, GoalStepState.PLANNED, GoalStepState.PAUSED, 2)
        assertFailsWith<IllegalArgumentException> { ledger.append(stale) }
        repository.failAfterTransitionWrite = false
        assertTrue(ledger.append(ready).replayed)
        assertEquals(1L, ledger.state(plan.id)?.revision)
        assertEquals(2, repository.writeOrder.size)
        assertEquals(ledger.state(plan.id), DurableGoalPlanLedger(repository).create(plan))
    }

    @Test
    fun `failed rehydrate keeps the last valid RAM snapshot`() = runBlocking {
        val repository = RecordingRepository()
        val ledger = DurableGoalPlanLedger(repository)
        val plan = plan()
        ledger.create(plan)
        val first = plan.steps.single { it.key == "first" }
        val ready = transition(plan, null, first, GoalStepState.PLANNED, GoalStepState.READY, 1)
        ledger.append(ready)
        val previous = ledger.states.value
        val fork = transition(plan, null, first, GoalStepState.PLANNED, GoalStepState.PAUSED, 2)
        repository.transitions[fork.id] = fork

        assertFailsWith<IllegalArgumentException> { ledger.rehydrate() }
        assertEquals(previous, ledger.states.value)
    }

    private fun plan(): GoalPlanDefinition = GoalPlanDefinition.create(
        sourceGoalPhotonId = PhotonId("goal-source"),
        sourceGoalPhotonRevision = 2L,
        stepSpecs = listOf(
            GoalStepSpec("first", "First durable step", priority = 2),
            GoalStepSpec(
                "second",
                "Second durable step",
                dependencyKeys = setOf("first"),
                deadline = at.plusSeconds(86_400),
                priority = 1,
            ),
        ),
        createdAt = at,
    )

    private fun transition(
        plan: GoalPlanDefinition,
        predecessor: GoalTransitionId?,
        step: GoalStepDefinition,
        from: GoalStepState,
        to: GoalStepState,
        second: Long,
        actionId: String? = null,
        idempotencyKey: String? = null,
        outcomePhotonId: PhotonId? = null,
    ): GoalPlanTransition = GoalPlanTransition.create(
        planId = plan.id,
        predecessorId = predecessor,
        stepId = step.id,
        fromState = from,
        toState = to,
        reason = "test-$from-$to",
        sourceFingerprint = "source-$second",
        actionId = actionId,
        actionIdempotencyKey = idempotencyKey,
        outcomePhotonId = outcomePhotonId,
        createdAt = at.plusSeconds(second),
    )

    private class RecordingRepository(
        private val failDefinitionWrite: Boolean = false,
        private val unreadable: List<String> = emptyList(),
    ) : GoalPlanRepository {
        val definitions = linkedMapOf<GoalPlanId, GoalPlanDefinition>()
        val transitions = linkedMapOf<GoalTransitionId, GoalPlanTransition>()
        val writeOrder = mutableListOf<String>()
        var failTransitionWrite: Boolean = false
        var failAfterTransitionWrite: Boolean = false
        var returnTransitionsReversed: Boolean = false

        override suspend fun saveDefinition(
            definition: GoalPlanDefinition,
        ): GoalPlanDefinitionWriteResult {
            check(!failDefinitionWrite) { "synthetic definition write failure" }
            val existing = definitions[definition.id]
            if (existing != null) {
                require(existing == definition)
                return GoalPlanDefinitionWriteResult.Duplicate(existing)
            }
            definitions[definition.id] = definition
            writeOrder += "definition:${definition.id.value}"
            return GoalPlanDefinitionWriteResult.Stored(definition)
        }

        override suspend fun loadDefinition(id: GoalPlanId): GoalPlanDefinition? = definitions[id]

        override suspend fun saveTransition(
            transition: GoalPlanTransition,
        ): GoalPlanTransitionWriteResult {
            check(!failTransitionWrite) { "synthetic transition write failure" }
            val existing = transitions[transition.id]
            if (existing != null) {
                require(existing == transition)
                return GoalPlanTransitionWriteResult.Duplicate(existing)
            }
            transitions[transition.id] = transition
            writeOrder += "transition:${transition.id.value}"
            check(!failAfterTransitionWrite) { "synthetic lost write acknowledgement" }
            return GoalPlanTransitionWriteResult.Stored(transition)
        }

        override suspend fun loadTransitions(planId: GoalPlanId): List<GoalPlanTransition> =
            transitions.values.filter { it.planId == planId }

        override suspend fun loadReport(): GoalPlanRepositoryLoadReport {
            val events = transitions.values.toList().let { values ->
                if (returnTransitionsReversed) values.reversed() else values
            }
            return GoalPlanRepositoryLoadReport(
                definitions = definitions.values.toList(),
                transitions = events,
                unreadableEntries = unreadable,
            )
        }
    }
}
