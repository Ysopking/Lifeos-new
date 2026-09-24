package app.lifeos.core.runtime.world

import app.lifeos.core.language.Ambiguity
import app.lifeos.core.language.GoalFrame
import app.lifeos.core.language.IntentType
import app.lifeos.core.language.LanguageCode
import app.lifeos.core.language.LanguageEpistemicStatus
import app.lifeos.core.language.LanguageModalStatus
import app.lifeos.core.language.LanguagePropositionRealization
import app.lifeos.core.language.LanguageRealizationState
import app.lifeos.core.language.LanguageReferenceGrounding
import app.lifeos.core.language.LanguageReferenceGroundingState
import app.lifeos.core.language.LanguageReferenceGroundingStatus
import app.lifeos.core.language.LanguageRepresentationLevel
import app.lifeos.core.language.LanguageTemporalStatus
import app.lifeos.core.language.ReferenceExpression
import app.lifeos.core.language.ReferenceKind
import app.lifeos.core.language.SemanticNodeId
import app.lifeos.core.language.SemanticPropositionGraph
import app.lifeos.core.language.SpeechActType
import app.lifeos.core.runtime.life.ObservationAuthorityClass
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LanguageStateSufficiencyCoordinatorTest {
    private val now = Instant.parse("2026-09-24T12:00:00Z")
    private val coordinator = LanguageStateSufficiencyCoordinator()

    @Test
    fun ambiguousReferenceBecomesClarificationNotPerceptionGuess() {
        val goal = goal(
            groundingStatus = LanguageReferenceGroundingStatus.AMBIGUOUS,
        )

        val plan = coordinator.plan(goal)

        assertTrue(plan.perceptionNeeds.isEmpty())
        assertEquals(1, plan.clarificationNeeds.size)
        assertEquals("ambiguous-reference", plan.clarificationNeeds.single().reason)
        assertEquals(null, plan.contract)
    }

    @Test
    fun staleReferenceBecomesExplicitPerceptionContract() {
        val goal = goal(
            groundingStatus = LanguageReferenceGroundingStatus.STALE_REVISION,
        )

        val assessment = coordinator.evaluate(
            goal = goal,
            evidence = emptyList(),
            at = now,
        )

        val result = assertNotNull(assessment.result)
        assertEquals(StateSufficiencyStatus.INSUFFICIENT, result.status)
        assertEquals(1, assessment.plan.perceptionNeeds.size)
        assertTrue(assessment.worldGaps.single() is WorldGap.Perception)
        assertFalse(assessment.interpretationReady)
        assertFalse(assessment.executionAuthority)
        assertFalse(assessment.directWorldStateMutationAllowed)
    }

    @Test
    fun suppliedStateEvidenceCanClosePerceptionNeedButNotGrantAuthority() {
        val goal = goal(
            groundingStatus = LanguageReferenceGroundingStatus.MISSING,
        )
        val plan = coordinator.plan(goal)
        val dimension = requireNotNull(plan.perceptionNeeds.single().stateDimension)

        val assessment = coordinator.evaluate(
            goal = goal,
            evidence = listOf(
                StateDimensionEvidence(
                    dimension = dimension,
                    evidenceIds = setOf("world-evidence-1"),
                    strongestAuthority = ObservationAuthorityClass.UI_OBSERVATION,
                    latestObservedAt = now,
                )
            ),
            at = now,
        )

        assertEquals(StateSufficiencyStatus.SUFFICIENT, assessment.result?.status)
        assertTrue(assessment.worldGaps.isEmpty())
        assertTrue(assessment.interpretationReady)
        assertFalse(assessment.executionAuthority)
    }

    @Test
    fun missingSemanticRoleRequestsClarification() {
        val nodeId = SemanticNodeId.create("language-state-test", "role")
        val realization = LanguageRealizationState(
            utteranceFingerprint = "utterance",
            utteranceRepresentation = LanguageRepresentationLevel.ACTUAL,
            utteranceEpistemicStatus = LanguageEpistemicStatus.OBSERVED_UTTERANCE,
            propositions = listOf(
                LanguagePropositionRealization(
                    nodeId = nodeId,
                    representation = LanguageRepresentationLevel.POSSIBILITY,
                    epistemicStatus = LanguageEpistemicStatus.UNRESOLVED,
                    temporalStatus = LanguageTemporalStatus.UNSPECIFIED,
                    modalStatuses = setOf(LanguageModalStatus.REQUESTED),
                    speechAct = SpeechActType.REQUEST,
                    groundedReferences = emptySet(),
                    unresolvedReasons = setOf("role:recipient"),
                )
            ),
        )
        val goal = baseGoal(
            realization = realization,
            grounding = LanguageReferenceGroundingState.empty(),
        )

        val plan = coordinator.plan(goal)

        assertEquals(1, plan.clarificationNeeds.size)
        assertEquals("role:recipient", plan.clarificationNeeds.single().reason)
        assertTrue(plan.perceptionNeeds.isEmpty())
    }

    private fun goal(
        groundingStatus: LanguageReferenceGroundingStatus,
    ): GoalFrame {
        val grounding = LanguageReferenceGrounding(
            expression = ReferenceExpression(
                kind = ReferenceKind.THAT,
                rawText = "das",
                confidence = 0.9,
            ),
            selectedPhotonId = null,
            selectedRevisionRef = null,
            status = groundingStatus,
            score = if (groundingStatus == LanguageReferenceGroundingStatus.MISSING) 0.0 else 0.7,
            runnerUpScore = if (groundingStatus == LanguageReferenceGroundingStatus.AMBIGUOUS) 0.66 else null,
            matchedContextFingerprint = null,
        )
        return baseGoal(
            realization = LanguageRealizationState.empty(),
            grounding = LanguageReferenceGroundingState(listOf(grounding)),
        )
    }

    private fun baseGoal(
        realization: LanguageRealizationState,
        grounding: LanguageReferenceGroundingState,
    ) = GoalFrame(
        intent = IntentType.QUERY,
        objective = "query: test",
        entities = emptyList(),
        references = emptyList(),
        constraints = emptyList(),
        ambiguities = emptyList<Ambiguity>(),
        confidence = 1.0,
        language = LanguageCode.DE,
        languageRealization = realization,
        propositionGraph = SemanticPropositionGraph.empty(),
        referenceGrounding = grounding,
    )
}
