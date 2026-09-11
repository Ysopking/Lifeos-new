package app.lifeos.core.runtime.goal

import app.lifeos.core.language.Ambiguity
import app.lifeos.core.language.GoalFrame
import app.lifeos.core.language.IntentType
import app.lifeos.core.language.LanguageCode
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.Provenance
import app.lifeos.core.runtime.capability.CapabilityGap
import app.lifeos.core.runtime.capability.CapabilityGapType
import app.lifeos.core.runtime.capability.CapabilityId
import app.lifeos.core.runtime.capability.CapabilityRequirement
import app.lifeos.core.runtime.capability.GoalCapabilityPlan
import app.lifeos.core.runtime.capability.GoalCapabilityResolution
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GoalPlanFactoryTest {
    private val at = Instant.parse("2026-09-11T10:00:00Z")
    private val factory = GoalPlanFactory()

    @Test
    fun `creates stable durable plan identity from same goal photon revision`() {
        val goal = goal(IntentType.QUERY, "query: Was ist der nächste Schritt?")
        val photon = goalPhoton(revision = 2L)
        val routing = routing(goal)

        val first = factory.create(photon, goal, routing, at)
        val second = factory.create(photon, goal, routing, at)

        assertEquals(first, second)
        assertEquals(photon.id, first.sourceGoalPhotonId)
        assertEquals(2L, first.sourceGoalPhotonRevision)
        assertEquals(listOf("record-outcome", "resolve-query"), first.steps.map { it.key }.sorted())
        val execute = first.steps.single { it.key == "resolve-query" }
        val record = first.steps.single { it.key == "record-outcome" }
        assertEquals(setOf(execute.id), record.dependencyIds)
    }

    @Test
    fun `blocking ambiguity inserts clarification before capability and execution`() {
        val goal = goal(
            intent = IntentType.TRANSFORM_IMAGE,
            objective = "transform_image: mach dieses bild heller",
            ambiguities = listOf(
                Ambiguity(
                    code = "image_source_missing",
                    message = "Image transformation requires a source image reference",
                    alternatives = emptyList(),
                    severity = 0.92,
                )
            ),
        )
        val photon = goalPhoton()
        val routing = routing(
            goal = goal,
            languageBlocking = true,
            gap = CapabilityGap(
                requirement = CapabilityRequirement(CapabilityId("image.transform.mmsi")),
                type = CapabilityGapType.CAPABILITY_MISSING,
            ),
        )

        val plan = factory.create(photon, goal, routing, at)

        val clarify = plan.steps.single { it.key == "clarify-goal" }
        val capability = plan.steps.single { it.key == "resolve-capability" }
        val execute = plan.steps.single { it.key == "transform-image" }
        val record = plan.steps.single { it.key == "record-outcome" }
        assertEquals(emptySet(), clarify.dependencyIds)
        assertEquals(setOf(clarify.id), capability.dependencyIds)
        assertEquals(setOf(clarify.id, capability.id), execute.dependencyIds)
        assertEquals(setOf(execute.id), record.dependencyIds)
    }

    @Test
    fun `fails closed when source photon is not an authoritative goal photon`() {
        val goal = goal(IntentType.SEARCH, "search: finde belege")
        val photon = goalPhoton(tags = setOf("chat"))

        assertFailsWith<IllegalArgumentException> {
            factory.create(photon, goal, routing(goal), at)
        }
    }

    @Test
    fun `durable ledger deduplicates automatically recreated goal plan after restart`() = kotlinx.coroutines.runBlocking {
        val goal = goal(IntentType.STORE_OR_REMEMBER, "store_or_remember: merke die architekturregel")
        val photon = goalPhoton(id = PhotonId("goal-restart"), revision = 1L)
        val routing = routing(goal)
        val repository = FactoryRecordingRepository()
        val firstLedger = DurableGoalPlanLedger(repository)
        val firstPlan = factory.create(photon, goal, routing, at)
        val firstState = firstLedger.create(firstPlan)

        val restartedLedger = DurableGoalPlanLedger(repository)
        restartedLedger.rehydrate()
        val secondState = restartedLedger.create(factory.create(photon, goal, routing, at))

        assertEquals(firstState.definition.id, secondState.definition.id)
        assertEquals(0L, secondState.revision)
        assertEquals(1, repository.definitions.size)
    }

    private fun goal(
        intent: IntentType,
        objective: String,
        ambiguities: List<Ambiguity> = emptyList(),
    ): GoalFrame = GoalFrame(
        intent = intent,
        objective = objective,
        entities = emptyList(),
        references = emptyList(),
        constraints = emptyList(),
        ambiguities = ambiguities,
        confidence = 0.91,
        language = LanguageCode.DE,
    )

    private fun goalPhoton(
        id: PhotonId = PhotonId("goal-source"),
        revision: Long = 1L,
        tags: Set<String> = setOf("goal", "language-understood", "intent:query"),
    ): Photon = Photon(
        id = id,
        revision = revision,
        content = "goal/v2\nobjective=test",
        mimeType = "application/vnd.lifeos.goal+text",
        provenance = Provenance(
            source = "language-understanding",
            actor = "LanguageUnderstandingEngine",
            createdAt = at,
        ),
        tags = tags,
    )

    private fun routing(
        goal: GoalFrame,
        languageBlocking: Boolean = false,
        gap: CapabilityGap? = null,
    ): GoalCapabilityResolution = GoalCapabilityResolution(
        plan = GoalCapabilityPlan(
            goal = goal,
            requirements = gap?.let { listOf(it.requirement) }.orEmpty(),
            languageBlocking = languageBlocking,
        ),
        selectedProviders = emptyMap(),
        gaps = gap?.let { listOf(it) }.orEmpty(),
    )

    private class FactoryRecordingRepository : GoalPlanRepository {
        val definitions = linkedMapOf<GoalPlanId, GoalPlanDefinition>()
        override suspend fun saveDefinition(definition: GoalPlanDefinition): GoalPlanDefinitionWriteResult {
            val existing = definitions[definition.id]
            if (existing != null) {
                require(existing == definition)
                return GoalPlanDefinitionWriteResult.Duplicate(existing)
            }
            definitions[definition.id] = definition
            return GoalPlanDefinitionWriteResult.Stored(definition)
        }
        override suspend fun loadDefinition(id: GoalPlanId): GoalPlanDefinition? = definitions[id]
        override suspend fun saveTransition(transition: GoalPlanTransition): GoalPlanTransitionWriteResult =
            GoalPlanTransitionWriteResult.Stored(transition)
        override suspend fun loadTransitions(planId: GoalPlanId): List<GoalPlanTransition> = emptyList()
        override suspend fun loadReport(): GoalPlanRepositoryLoadReport = GoalPlanRepositoryLoadReport(
            definitions = definitions.values.toList(),
            transitions = emptyList(),
            unreadableEntries = emptyList(),
        )
    }
}
