package app.lifeos.core.runtime.goal

import app.lifeos.core.language.GoalFrame
import app.lifeos.core.language.IntentType
import app.lifeos.core.language.LanguageCode
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.Provenance
import app.lifeos.core.runtime.capability.CapabilityGap
import app.lifeos.core.runtime.capability.CapabilityGapType
import app.lifeos.core.runtime.capability.GoalCapabilityResolution
import app.lifeos.core.runtime.capability.LanguageGoalCapabilityMapper
import app.lifeos.core.runtime.convergence.ConvergenceDecisionCheckpoint
import app.lifeos.core.runtime.convergence.ConvergenceDecisionCheckpointId
import app.lifeos.core.runtime.convergence.ConvergenceDecisionCheckpointLoadReport
import app.lifeos.core.runtime.convergence.ConvergenceDecisionCheckpointRepository
import app.lifeos.core.runtime.convergence.ConvergenceDecisionCheckpointWriteResult
import app.lifeos.core.runtime.convergence.ConvergenceDecisionState
import app.lifeos.core.runtime.convergence.DurableConvergenceDecisionCoordinator
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GoalConvergenceDecisionProviderTest {
    private val at = Instant.parse("2026-09-11T12:05:00Z")

    @Test
    fun `persisted high confidence goal is evaluated by V5 and checkpointed before exposure`() = runBlocking {
        val repository = MemoryCheckpointRepository()
        val provider = GoalConvergenceDecisionProvider(
            DurableConvergenceDecisionCoordinator(repository)
        )
        val goal = goal(IntentType.QUERY)
        val source = sourcePhoton()
        val routing = GoalCapabilityResolution(
            plan = LanguageGoalCapabilityMapper().plan(goal),
            selectedProviders = emptyMap(),
            gaps = emptyList(),
        )

        val first = provider.decide(goal, routing, source, PhotonId("goal-v5"), at)
        val second = provider.decide(goal, routing, source, PhotonId("goal-v5"), at)

        assertEquals(ConvergenceDecisionState.ACTIONABLE, first.decision.state)
        assertEquals(first, second)
        assertEquals(1, repository.checkpoints.size)
        assertEquals(first, repository.checkpoints.values.single())
        assertTrue(first.decision.reasons.contains("all-convergence-action-gates-satisfied"))
    }

    @Test
    fun `blocking routed capability gap remains capability required through V5`() = runBlocking {
        val repository = MemoryCheckpointRepository()
        val provider = GoalConvergenceDecisionProvider(
            DurableConvergenceDecisionCoordinator(repository)
        )
        val goal = goal(IntentType.QUERY)
        val plan = LanguageGoalCapabilityMapper().plan(goal)
        val gap = CapabilityGap(
            requirement = plan.requirements.single(),
            type = CapabilityGapType.CAPABILITY_MISSING,
            candidateProviderIds = emptyList(),
        )
        val routing = GoalCapabilityResolution(
            plan = plan,
            selectedProviders = emptyMap(),
            gaps = listOf(gap),
        )

        val checkpoint = provider.decide(
            goal = goal,
            routing = routing,
            sourcePhoton = sourcePhoton(),
            goalPhotonId = PhotonId("goal-gap"),
            at = at,
        )

        assertEquals(ConvergenceDecisionState.CAPABILITY_REQUIRED, checkpoint.decision.state)
        assertEquals(listOf(gap), checkpoint.decision.capabilityGaps)
        assertTrue(checkpoint.decision.selectedHypothesisIds.isEmpty())
        assertEquals(checkpoint, repository.checkpoints.values.single())
    }

    private fun goal(intent: IntentType) = GoalFrame(
        intent = intent,
        objective = "Resolve persisted user goal",
        entities = emptyList(),
        references = emptyList(),
        constraints = emptyList(),
        ambiguities = emptyList(),
        confidence = 1.0,
        language = LanguageCode.EN,
    )

    private fun sourcePhoton() = Photon(
        id = PhotonId("source-v5"),
        revision = 1,
        content = "What is already known?",
        semanticMass = 1.0,
        energy = 1.0,
        confidence = 1.0,
        provenance = Provenance(
            source = "unit-test",
            actor = "user",
            createdAt = at,
        ),
        tags = setOf("chat"),
    )

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
