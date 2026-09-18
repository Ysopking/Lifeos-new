package app.lifeos.core.runtime.goal

import app.lifeos.core.language.GoalFrame
import app.lifeos.core.language.GoalPhotonFactory
import app.lifeos.core.language.IntentType
import app.lifeos.core.language.LanguageCode
import app.lifeos.core.language.LanguageUnderstandingEngine
import app.lifeos.core.language.ReferenceExpression
import app.lifeos.core.language.ReferenceKind
import app.lifeos.core.language.ResolvedReference
import app.lifeos.core.language.SemanticLinkType
import app.lifeos.core.language.SemanticModality
import app.lifeos.core.language.SemanticPolarity
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonPhase
import app.lifeos.core.model.PhotonRelation
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.RelationType
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GoalResumeEngineTest {
    private val engine = GoalResumeEngine()
    private val now = Instant.parse("2026-09-10T19:00:00Z")

    @Test
    fun `resume decodes persisted goal instead of reinterpreting current context`() {
        val source = source("source-1", "Erstelle ein Bild mit zwei Menschen und einem Ball.")
        val originalUnderstanding = LanguageUnderstandingEngine().understand(source.content)
        val target = GoalPhotonFactory().create(
            result = originalUnderstanding,
            sourcePhotonId = source.id,
            createdAt = source.provenance.createdAt,
        ).photon
        val requestSource = source("continue-source", "Weiter", now)
        val requestGoalId = PhotonId("continue-goal")

        val result = assertIs<GoalResumeResult.Resumed>(
            engine.resume(
                request = continueGoal(target.id),
                requestSource = requestSource,
                requestGoalPhotonId = requestGoalId,
                photons = listOf(source, target, requestSource),
                createdAt = now,
            )
        )

        assertEquals(target.id, result.targetGoal.id)
        assertEquals(source.id, result.sourcePhoton.id)
        assertEquals(originalUnderstanding.goal.intent, result.frame.intent)
        assertEquals(originalUnderstanding.goal.objective, result.frame.objective)
        assertEquals(
            originalUnderstanding.goal.entities.map { it.type to it.normalizedValue }.toSet(),
            result.frame.entities.map { it.type to it.normalizedValue }.toSet(),
        )
        assertEquals(
            originalUnderstanding.goal.semanticGraph.fingerprint,
            result.frame.semanticGraph.fingerprint,
        )
        assertEquals(
            originalUnderstanding.goal.semanticActionGraph.fingerprint,
            result.frame.semanticActionGraph.fingerprint,
        )
        assertEquals(
            originalUnderstanding.goal.semanticActionGraph,
            result.frame.semanticActionGraph,
        )
        assertEquals(target.content, result.resumedPhoton.content)
        assertEquals(setOf(target.id, requestGoalId, requestSource.id), result.resumedPhoton.provenance.parentIds)
        assertTrue("goal-resumed" in result.resumedPhoton.tags)
        assertTrue(result.resumedPhoton.relations.any {
            it.target == target.id && it.type == RelationType.DERIVED_FROM
        })
    }

    @Test
    fun `structured v4 semantics survive resume without reinterpreting source`() {
        val source = source(
            "source-structured",
            "Suche die Datei, wenn sie größer als 10 MB ist, weil der Speicher voll ist.",
        )
        val originalUnderstanding = LanguageUnderstandingEngine().understand(source.content)
        val target = GoalPhotonFactory().create(
            result = originalUnderstanding,
            sourcePhotonId = source.id,
            createdAt = source.provenance.createdAt,
        ).photon
        val requestSource = source("continue-structured", "Weiter", now)

        val result = assertIs<GoalResumeResult.Resumed>(
            engine.resume(
                request = continueGoal(target.id),
                requestSource = requestSource,
                requestGoalPhotonId = PhotonId("continue-goal-structured"),
                photons = listOf(source, target, requestSource),
                createdAt = now,
            )
        )

        assertEquals(originalUnderstanding.goal.semanticGraph.fingerprint, result.frame.semanticGraph.fingerprint)
        assertTrue(result.frame.semanticGraph.links.any { it.type == SemanticLinkType.CONDITION })
        assertTrue(result.frame.semanticGraph.links.any { it.type == SemanticLinkType.CAUSE })
        assertTrue(
            result.frame.semanticGraph.clauses
                .flatMap { it.quantities }
                .any { it.value == "10" && it.unit == "mb" }
        )
        assertEquals(originalUnderstanding.goal.semanticActionGraph, result.frame.semanticActionGraph)
        assertEquals(originalUnderstanding.goal.semanticEntitiesV2, result.frame.semanticEntitiesV2)
        assertEquals(originalUnderstanding.goal.quantityTemporal, result.frame.quantityTemporal)
        assertEquals(originalUnderstanding.goal.domainSemanticGraph, result.frame.domainSemanticGraph)
        assertEquals(originalUnderstanding.goal.interpretationQuality, result.frame.interpretationQuality)
        assertEquals(
            originalUnderstanding.goal.references.map { it.targetPhotonRef },
            result.frame.references.map { it.targetPhotonRef },
        )
    }

    @Test
    fun `legacy v2 goal remains readable after v4 rollout`() {
        val source = source("source-legacy", "Was weißt du über Balkonbank?")
        val current = goalPhoton(source)
        val legacy = current.copy(
            id = PhotonId("goal-legacy-v2"),
            content = current.content
                .lines()
                .filterNot { it.startsWith("semantic.") || it.startsWith("action.") }
                .joinToString("\n")
                .replaceFirst("goal/v4", "goal/v2"),
        )
        val requestSource = source("continue-legacy", "Weiter", now)

        val result = assertIs<GoalResumeResult.Resumed>(
            engine.resume(
                request = continueGoal(legacy.id),
                requestSource = requestSource,
                requestGoalPhotonId = PhotonId("continue-goal-legacy"),
                photons = listOf(source, legacy, requestSource),
                createdAt = now,
            )
        )

        assertEquals(legacy.id, result.targetGoal.id)
        assertTrue(result.frame.semanticGraph.clauses.isEmpty())
        assertEquals(LanguageCode.DE, result.frame.semanticGraph.language)
    }

    @Test
    fun `archived target is fail closed`() {
        val source = source("source-1", "Was weißt du über Balkonbank?")
        val target = goalPhoton(source).copy(phase = PhotonPhase.ARCHIVED)

        val result = assertIs<GoalResumeResult.Blocked>(
            engine.resume(
                request = continueGoal(target.id),
                requestSource = source("continue-source", "Weiter", now),
                requestGoalPhotonId = PhotonId("continue-goal"),
                photons = listOf(source, target),
                createdAt = now,
            )
        )

        assertEquals(GoalResumeBlockReason.TARGET_ARCHIVED, result.reason)
    }

    @Test
    fun `archived resumed node cannot reactivate its original goal`() {
        val source = source("source-1", "Was weißt du über Balkonbank?")
        val original = goalPhoton(source)
        val archivedResume = Photon(
            id = PhotonId("archived-resume"),
            content = original.content,
            mimeType = original.mimeType,
            phase = PhotonPhase.ARCHIVED,
            provenance = Provenance("goal-resume", "GoalResumeEngine", now.minusSeconds(30), setOf(original.id)),
            relations = setOf(PhotonRelation(original.id, RelationType.DERIVED_FROM)),
            tags = original.tags + "goal-resumed",
        )

        val result = assertIs<GoalResumeResult.Blocked>(
            engine.resume(
                request = continueGoal(archivedResume.id),
                requestSource = source("continue-source", "Weiter", now),
                requestGoalPhotonId = PhotonId("continue-goal"),
                photons = listOf(source, original, archivedResume),
                createdAt = now,
            )
        )

        assertEquals(GoalResumeBlockReason.TARGET_ARCHIVED, result.reason)
    }

    @Test
    fun `malformed persisted goal is fail closed`() {
        val source = source("source-1", "alte Aufgabe")
        val target = Photon(
            id = PhotonId("goal-bad"),
            content = "goal/v2\nintent=QUERY",
            mimeType = "application/vnd.lifeos.goal+text",
            provenance = Provenance("test", "test", now.minusSeconds(60), setOf(source.id)),
            tags = setOf("goal", "intent:query"),
        )

        val result = assertIs<GoalResumeResult.Blocked>(
            engine.resume(
                request = continueGoal(target.id),
                requestSource = source("continue-source", "Weiter", now),
                requestGoalPhotonId = PhotonId("continue-goal"),
                photons = listOf(source, target),
                createdAt = now,
            )
        )

        assertEquals(GoalResumeBlockReason.TARGET_UNDECODABLE, result.reason)
    }

    @Test
    fun `duplicate persisted goal header is fail closed`() {
        val source = source("source-1", "Was weißt du über Balkonbank?")
        val valid = goalPhoton(source)
        val target = valid.copy(
            id = PhotonId("goal-duplicate-header"),
            content = valid.content + "\nintent=CREATE_IMAGE",
        )

        val result = assertIs<GoalResumeResult.Blocked>(
            engine.resume(
                request = continueGoal(target.id),
                requestSource = source("continue-source", "Weiter", now),
                requestGoalPhotonId = PhotonId("continue-goal"),
                photons = listOf(source, target),
                createdAt = now,
            )
        )

        assertEquals(GoalResumeBlockReason.TARGET_UNDECODABLE, result.reason)
    }

    @Test
    fun `partial persisted action revision binding is fail closed`() {
        val source = source("source-partial-ref", "Erstelle ein Bild.")
        val valid = goalPhoton(source)
        val roleLine = requireNotNull(valid.content.lines().firstOrNull { it.startsWith("action.role.") })
        val assignment = roleLine.substringAfter('=').split('|').toMutableList()
        require(assignment.size >= 7)
        assignment[5] = ""
        assignment[6] = "5"
        val corruptRole = roleLine.substringBefore('=') + "=" + assignment.joinToString("|")
        val target = valid.copy(
            id = PhotonId("goal-partial-ref"),
            content = valid.content.replace(roleLine, corruptRole),
        )

        val result = assertIs<GoalResumeResult.Blocked>(
            engine.resume(
                request = continueGoal(target.id),
                requestSource = source("continue-partial-ref", "Weiter", now),
                requestGoalPhotonId = PhotonId("continue-goal-partial-ref"),
                photons = listOf(source, target),
                createdAt = now,
            )
        )

        assertEquals(GoalResumeBlockReason.TARGET_UNDECODABLE, result.reason)
    }

    @Test
    fun `duplicate persisted action role is fail closed`() {
        val source = source("source-duplicate-role", "Erstelle ein Bild.")
        val valid = goalPhoton(source)
        val roleLine = requireNotNull(valid.content.lines().firstOrNull { it.startsWith("action.role.") })
        val target = valid.copy(
            id = PhotonId("goal-duplicate-role"),
            content = valid.content + "\n" + roleLine,
        )

        val result = assertIs<GoalResumeResult.Blocked>(
            engine.resume(
                request = continueGoal(target.id),
                requestSource = source("continue-duplicate-role", "Weiter", now),
                requestGoalPhotonId = PhotonId("continue-goal-duplicate-role"),
                photons = listOf(source, target),
                createdAt = now,
            )
        )

        assertEquals(GoalResumeBlockReason.TARGET_UNDECODABLE, result.reason)
    }

    @Test
    fun `dangling persisted scope target is fail closed`() {
        val source = source("source-dangling-scope", "Sende diese Mail nicht.")
        val valid = goalPhoton(source)
        val scopeLine = requireNotNull(valid.content.lines().firstOrNull { it.startsWith("action.scope.") })
        val fields = scopeLine.substringAfter('=').split('|').toMutableList()
        fields[1] = "semantic-node:" + "0".repeat(64)
        val corruptScope = scopeLine.substringBefore('=') + "=" + fields.joinToString("|")
        val target = valid.copy(
            id = PhotonId("goal-dangling-scope"),
            content = valid.content.replace(scopeLine, corruptScope),
        )

        val result = assertIs<GoalResumeResult.Blocked>(
            engine.resume(
                request = continueGoal(target.id),
                requestSource = source("continue-dangling-scope", "Weiter", now),
                requestGoalPhotonId = PhotonId("continue-goal-dangling-scope"),
                photons = listOf(source, target),
                createdAt = now,
            )
        )

        assertEquals(GoalResumeBlockReason.TARGET_UNDECODABLE, result.reason)
    }

    @Test
    fun `resumed goal chain unwraps to original persisted goal`() {
        val source = source("source-1", "Was weißt du über Balkonbank?")
        val original = goalPhoton(source)
        val firstRequestSource = source("continue-1", "Weiter", now.minusSeconds(10))
        val first = assertIs<GoalResumeResult.Resumed>(
            engine.resume(
                request = continueGoal(original.id),
                requestSource = firstRequestSource,
                requestGoalPhotonId = PhotonId("continue-goal-1"),
                photons = listOf(source, original, firstRequestSource),
                createdAt = now.minusSeconds(10),
            )
        )
        val secondRequestSource = source("continue-2", "Weiter", now)

        val second = assertIs<GoalResumeResult.Resumed>(
            engine.resume(
                request = continueGoal(first.resumedPhoton.id),
                requestSource = secondRequestSource,
                requestGoalPhotonId = PhotonId("continue-goal-2"),
                photons = listOf(source, original, firstRequestSource, first.resumedPhoton, secondRequestSource),
                createdAt = now,
            )
        )

        assertEquals(original.id, second.targetGoal.id)
        assertEquals(source.id, second.sourcePhoton.id)
        assertEquals(original.content, second.resumedPhoton.content)
    }

    @Test
    fun `non goal continuation target is rejected`() {
        val target = source("not-goal", "nur text")

        val result = assertIs<GoalResumeResult.Blocked>(
            engine.resume(
                request = continueGoal(target.id),
                requestSource = source("continue-source", "Weiter", now),
                requestGoalPhotonId = PhotonId("continue-goal"),
                photons = listOf(target),
                createdAt = now,
            )
        )

        assertEquals(GoalResumeBlockReason.TARGET_NOT_GOAL, result.reason)
    }

    private fun goalPhoton(source: Photon): Photon {
        val understanding = LanguageUnderstandingEngine().understand(source.content)
        return GoalPhotonFactory().create(
            result = understanding,
            sourcePhotonId = source.id,
            createdAt = source.provenance.createdAt,
        ).photon
    }

    private fun continueGoal(target: PhotonId) = GoalFrame(
        intent = IntentType.CONTINUE,
        objective = "continue: Weiter",
        entities = emptyList(),
        references = listOf(
            ResolvedReference(
                expression = ReferenceExpression(
                    kind = ReferenceKind.PREVIOUS,
                    rawText = "Weiter",
                    preferredKinds = setOf("goal"),
                    confidence = 0.98,
                ),
                targetPhotonId = target,
                score = 1.0,
            )
        ),
        constraints = emptyList(),
        ambiguities = emptyList(),
        confidence = 0.95,
        language = LanguageCode.DE,
    )

    private fun source(
        id: String,
        content: String,
        createdAt: Instant = now.minusSeconds(120),
    ) = Photon(
        id = PhotonId(id),
        content = content,
        provenance = Provenance("test", "user", createdAt),
        tags = setOf("chat"),
    )
}
