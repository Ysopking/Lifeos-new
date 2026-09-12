package app.lifeos.core.runtime.goal

import app.lifeos.core.language.Ambiguity
import app.lifeos.core.language.GoalFrame
import app.lifeos.core.language.IntentType
import app.lifeos.core.language.LanguageCode
import app.lifeos.core.model.PhotonId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class GoalPlanBuilderTest {
    private val at = Instant.parse("2026-09-11T10:00:00Z")

    @Test
    fun buildsActionThenVerificationWithRouterCapabilities() {
        val result = GoalPlanBuilder().build(
            goal = goal(IntentType.CREATE_IMAGE),
            sourceGoalPhotonId = PhotonId("goal-photon"),
            sourceGoalPhotonRevision = 3,
            createdAt = at,
        )
        val blueprint = assertIs<GoalPlanBuildResult.Built>(result).blueprint
        assertEquals(2, blueprint.definition.steps.size)
        val action = blueprint.definition.steps.single { it.key.startsWith("action:") }
        val verify = blueprint.definition.steps.single { it.key == "verify:outcome" }
        assertEquals(setOf(action.id), verify.dependencyIds)
        val actionContract = blueprint.contract(action.id)
        assertEquals(GoalStepExecutionKind.ACTION, actionContract.kind)
        assertEquals(IntentType.CREATE_IMAGE, actionContract.actionIntent)
        assertEquals(
            setOf("scene.construct.procedural", "scene.rasterize.mmsi", "image.render.mmsi"),
            actionContract.requiredCapabilityIds.mapTo(mutableSetOf()) { it.value },
        )
        assertEquals(GoalStepExecutionKind.VERIFY_OUTCOME, blueprint.contract(verify.id).kind)
    }

    @Test
    fun sameInputsProduceSamePlanAndContracts() {
        val builder = GoalPlanBuilder()
        val first = assertIs<GoalPlanBuildResult.Built>(
            builder.build(goal(IntentType.SEARCH), PhotonId("g"), 1, at)
        ).blueprint
        val second = assertIs<GoalPlanBuildResult.Built>(
            builder.build(goal(IntentType.SEARCH), PhotonId("g"), 1, at)
        ).blueprint
        assertEquals(first, second)
    }

    @Test
    fun persistedDefinitionRebindsIdenticalContractsWithoutRegeneratingPlan() {
        val builder = GoalPlanBuilder()
        val frame = goal(IntentType.SEARCH)
        val initial = assertIs<GoalPlanBuildResult.Built>(
            builder.build(frame, PhotonId("goal:stored"), 4, at, planRevision = 3)
        ).blueprint

        val rebound = assertIs<GoalPlanBuildResult.Built>(
            builder.bindExisting(frame, initial.definition)
        ).blueprint

        assertEquals(initial.definition, rebound.definition)
        assertEquals(initial.contracts, rebound.contracts)
    }

    @Test
    fun persistedDefinitionWithDifferentShapeFailsClosed() {
        val frame = goal(IntentType.QUERY)
        val incompatible = GoalPlanDefinition.create(
            sourceGoalPhotonId = PhotonId("goal:stored"),
            sourceGoalPhotonRevision = 1,
            stepSpecs = listOf(GoalStepSpec("legacy-step", "Old planner shape")),
            createdAt = at,
        )

        val blocked = assertIs<GoalPlanBuildResult.Blocked>(
            GoalPlanBuilder().bindExisting(frame, incompatible)
        )
        assertTrue(blocked.reason.startsWith("persisted-plan-"))
    }

    @Test
    fun sourceRevisionChangesPlanIdentity() {
        val builder = GoalPlanBuilder()
        val first = assertIs<GoalPlanBuildResult.Built>(
            builder.build(goal(IntentType.QUERY), PhotonId("g"), 1, at)
        ).blueprint
        val second = assertIs<GoalPlanBuildResult.Built>(
            builder.build(goal(IntentType.QUERY), PhotonId("g"), 2, at)
        ).blueprint
        assertNotEquals(first.definition.id, second.definition.id)
        assertTrue(first.contracts.keys.intersect(second.contracts.keys).isEmpty())
    }

    @Test
    fun blockingAmbiguityIsNotArtificiallyPlanned() {
        val blocked = GoalPlanBuilder().build(
            goal = goal(IntentType.QUERY).copy(
                ambiguities = listOf(
                    Ambiguity(
                        code = "ambiguous-target",
                        message = "target unclear",
                        alternatives = listOf("a", "b"),
                        severity = 0.95,
                    )
                )
            ),
            sourceGoalPhotonId = PhotonId("g"),
            sourceGoalPhotonRevision = 1,
            createdAt = at,
        )
        assertEquals(
            "goal-language-or-ambiguity-blocking",
            assertIs<GoalPlanBuildResult.Blocked>(blocked).reason,
        )
    }

    private fun goal(intent: IntentType) = GoalFrame(
        intent = intent,
        objective = "Complete ${intent.name.lowercase()} goal",
        entities = emptyList(),
        references = emptyList(),
        constraints = emptyList(),
        ambiguities = emptyList(),
        confidence = 1.0,
        language = LanguageCode.EN,
    )
}
