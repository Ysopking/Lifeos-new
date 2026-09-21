package app.lifeos.core.runtime.reasoning

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.model.PhotonId
import app.lifeos.core.reasoning.ProblemStateGraphId
import app.lifeos.core.runtime.goal.GoalPlanDefinition
import app.lifeos.core.runtime.goal.GoalPlanTransition
import app.lifeos.core.runtime.goal.GoalStepSpec
import app.lifeos.core.runtime.goal.GoalStepState
import app.lifeos.core.runtime.level7.StructuralSignature
import app.lifeos.core.runtime.level7.StructuralSimilarityEngine
import app.lifeos.core.runtime.level7.StructuralTransferCandidate
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class SkillGeneralizationEngineTest {
    private val at = Instant.parse("2026-09-21T22:00:00Z")

    @Test
    fun `validated structural transfer generalizes skill without semantic or activation authority`() {
        val skill = sourceSkill()
        val transfer = transfer(skill, targetDomain = "domain-y")
        val candidate = SkillGeneralizationEngine().generalize(
            listOf(
                request(
                    skill = skill,
                    transfer = transfer,
                    inspectObjective = "Inspect the target workspace.",
                    reportObjective = "Write the target-domain report.",
                )
            )
        ).single()

        assertEquals(skill.id, candidate.sourceSkillId)
        assertEquals("domain-x", candidate.sourceDomainId)
        assertEquals("domain-y", candidate.targetDomainId)
        assertEquals(transfer.fingerprint(), candidate.transferFingerprint)
        assertEquals(skill.steps.map { it.key }, candidate.steps.map { it.sourceStepKey })
        assertFalse(candidate.semanticIdentityEstablished)
        assertFalse(candidate.executionAuthority)
        assertFalse(candidate.activationAllowed)
        assertFalse(candidate.promotionAllowed)
    }

    @Test
    fun `source topology must match exact B376 skill shape`() {
        val skill = sourceSkill()
        val wrongSource = StructuralSignature(
            domainId = "domain-x",
            topologyFingerprint = "wrong-topology",
            relationFingerprint = "workflow-relations",
            dimensionFingerprint = "workflow-dimensions",
        )
        val target = StructuralSignature(
            domainId = "domain-y",
            topologyFingerprint = skill.shapeFingerprint,
            relationFingerprint = "workflow-relations",
            dimensionFingerprint = "workflow-dimensions",
        )
        val transfer = StructuralSimilarityEngine().candidate(
            source = wrongSource,
            target = target,
            validationFingerprint = "validated-wrong-source",
        )

        assertFailsWith<IllegalArgumentException> {
            SkillGeneralizationEngine().generalize(
                listOf(request(skill, transfer))
            )
        }
    }

    @Test
    fun `target objectives must cover every source step exactly`() {
        val skill = sourceSkill()
        val transfer = transfer(skill, targetDomain = "domain-y")

        assertFailsWith<IllegalArgumentException> {
            SkillGeneralizationEngine().generalize(
                listOf(
                    SkillGeneralizationRequest(
                        sourceSkill = skill,
                        transfer = transfer,
                        targetObjectivesByStepKey = mapOf(
                            "inspect" to "Inspect target.",
                        ),
                    )
                )
            )
        }

        assertFailsWith<IllegalArgumentException> {
            SkillGeneralizationEngine().generalize(
                listOf(
                    SkillGeneralizationRequest(
                        sourceSkill = skill,
                        transfer = transfer,
                        targetObjectivesByStepKey = mapOf(
                            "inspect" to "Inspect target.",
                            "report" to "Report target.",
                            "unknown" to "Unknown step.",
                        ),
                    )
                )
            )
        }
    }

    @Test
    fun `dependency topology and priority are preserved while objectives may adapt`() {
        val skill = sourceSkill()
        val transfer = transfer(skill, targetDomain = "domain-y")
        val candidate = SkillGeneralizationEngine().generalize(
            listOf(
                request(
                    skill,
                    transfer,
                    inspectObjective = "Inspect domain Y evidence.",
                    reportObjective = "Summarize domain Y evidence.",
                )
            )
        ).single()

        val sourceByKey = skill.steps.associateBy { it.key }
        candidate.steps.forEach { generalized ->
            val source = sourceByKey.getValue(generalized.sourceStepKey)
            assertEquals(source.dependencyKeys, generalized.dependencyKeys)
            assertEquals(source.priority, generalized.priority)
        }
        assertNotEquals(
            sourceByKey.getValue("report").objective,
            candidate.steps.single { it.sourceStepKey == "report" }.targetObjective,
        )
    }

    @Test
    fun `structural similarity below threshold fails closed`() {
        val skill = sourceSkill()
        val source = signature(
            domain = "domain-x",
            topology = skill.shapeFingerprint,
            relation = "source-relations",
            dimensions = "source-dimensions",
        )
        val target = signature(
            domain = "domain-y",
            topology = "different-topology",
            relation = "different-relations",
            dimensions = "source-dimensions",
        )
        val transfer = StructuralSimilarityEngine().candidate(
            source,
            target,
            validationFingerprint = "low-similarity-validation",
        )
        assertEquals(1.0 / 3.0, transfer.structuralSimilarity)

        assertFailsWith<IllegalArgumentException> {
            SkillGeneralizationEngine().generalize(
                listOf(request(skill, transfer))
            )
        }
    }

    @Test
    fun `request ordering cannot change generalized candidate order or identity`() {
        val skill = sourceSkill()
        val first = request(skill, transfer(skill, "domain-y"))
        val second = request(
            skill,
            transfer(skill, "domain-z"),
            inspectObjective = "Inspect domain Z.",
            reportObjective = "Report domain Z.",
        )
        val engine = SkillGeneralizationEngine()

        assertEquals(
            engine.generalize(listOf(first, second)),
            engine.generalize(listOf(second, first)),
        )
    }

    @Test
    fun `transfer validation fingerprint participates in generalized identity`() {
        val skill = sourceSkill()
        val firstTransfer = transfer(skill, "domain-y", "validation-a")
        val secondTransfer = transfer(skill, "domain-y", "validation-b")
        val engine = SkillGeneralizationEngine()

        val first = engine.generalize(listOf(request(skill, firstTransfer))).single()
        val second = engine.generalize(listOf(request(skill, secondTransfer))).single()

        assertNotEquals(first.id, second.id)
        assertNotEquals(first.fingerprint, second.fingerprint)
    }

    @Test
    fun `duplicate generalization requests fail closed`() {
        val skill = sourceSkill()
        val request = request(skill, transfer(skill, "domain-y"))

        assertFailsWith<IllegalArgumentException> {
            SkillGeneralizationEngine().generalize(listOf(request, request))
        }
    }

    private fun request(
        skill: ProceduralSkillCandidate,
        transfer: StructuralTransferCandidate,
        inspectObjective: String = "Inspect the target domain.",
        reportObjective: String = "Write the target report.",
    ): SkillGeneralizationRequest = SkillGeneralizationRequest(
        sourceSkill = skill,
        transfer = transfer,
        targetObjectivesByStepKey = mapOf(
            "inspect" to inspectObjective,
            "report" to reportObjective,
        ),
    )

    private fun transfer(
        skill: ProceduralSkillCandidate,
        targetDomain: String,
        validationFingerprint: String = "validated-transfer",
    ): StructuralTransferCandidate = StructuralSimilarityEngine().candidate(
        source = signature(
            domain = "domain-x",
            topology = skill.shapeFingerprint,
            relation = "workflow-relations",
            dimensions = "workflow-dimensions",
        ),
        target = signature(
            domain = targetDomain,
            topology = skill.shapeFingerprint,
            relation = "workflow-relations",
            dimensions = "workflow-dimensions",
        ),
        validationFingerprint = validationFingerprint,
    )

    private fun signature(
        domain: String,
        topology: String,
        relation: String,
        dimensions: String,
    ): StructuralSignature = StructuralSignature(
        domainId = domain,
        topologyFingerprint = topology,
        relationFingerprint = relation,
        dimensionFingerprint = dimensions,
    )

    private fun sourceSkill(): ProceduralSkillCandidate {
        val a = trace("cycle-a")
        val b = trace("cycle-b")
        return ProceduralSkillInductionEngine().induce(
            traces = listOf(a, b),
            semanticKeysByShape = mapOf(a.shapeFingerprint to "skill:repo-audit"),
        ).single()
    }

    private fun trace(sourceCycleId: String): ProceduralSkillTrace {
        val plan = GoalPlanDefinition.create(
            sourceGoalPhotonId = PhotonId("goal-" + sourceCycleId),
            sourceGoalPhotonRevision = 1L,
            stepSpecs = listOf(
                GoalStepSpec(
                    key = "inspect",
                    objective = "Inspect the source workspace.",
                    priority = 10,
                ),
                GoalStepSpec(
                    key = "report",
                    objective = "Write the source report.",
                    dependencyKeys = setOf("inspect"),
                    priority = 5,
                ),
            ),
            createdAt = at,
        )
        val transitions = plan.steps.mapIndexed { index, step ->
            GoalPlanTransition.create(
                planId = plan.id,
                predecessorId = null,
                stepId = step.id,
                fromState = GoalStepState.RUNNING,
                toState = GoalStepState.COMPLETED,
                reason = "verified completion",
                sourceFingerprint = StableFieldIds.fingerprint(
                    "transition-source",
                    sourceCycleId,
                    step.key,
                ),
                actionId = "action-" + sourceCycleId + "-" + step.key,
                actionIdempotencyKey = "idempotency-" + sourceCycleId + "-" + step.key,
                outcomePhotonId = PhotonId("outcome-" + sourceCycleId + "-" + step.key),
                createdAt = at.plusSeconds(index.toLong()),
            )
        }
        return ProceduralSkillTrace.from(
            episode = episode(sourceCycleId),
            plan = plan,
            transitions = transitions,
        )
    }

    private fun episode(sourceCycleId: String): LearningEpisode {
        val status = LearningEpisodeStatus.VERIFIED_OUTCOME
        val summary = LearningEpisodeSummary(
            expectedActions = 1,
            missingObservations = 0,
            incompleteObservations = 0,
            unverifiedObservations = 0,
            withinExpectedBand = 1,
            outsideExpectedBand = 0,
            causalAssignments = 0,
        )
        val problemId = ProblemStateGraphId(
            ProblemStateGraphId.PREFIX + StableFieldIds.fingerprint("problem", sourceCycleId)
        )
        val hypothesis = StableFieldIds.fingerprint("hypothesis", sourceCycleId)
        val search = StableFieldIds.fingerprint("search", sourceCycleId)
        val counterfactual = StableFieldIds.fingerprint("counterfactual", sourceCycleId)
        val plan = StableFieldIds.fingerprint("plan", sourceCycleId)
        val expectation = StableFieldIds.fingerprint("expectation", sourceCycleId)
        val error = StableFieldIds.fingerprint("error", sourceCycleId)
        val fingerprint = StableFieldIds.fingerprint(
            "learning-episode/v1",
            sourceCycleId,
            "1",
            "",
            problemId.value,
            hypothesis,
            search,
            counterfactual,
            plan,
            expectation,
            error,
            "",
            status.name,
            summary.fingerprint(),
            at.toString(),
        )
        return LearningEpisode(
            id = LearningEpisodeId(LearningEpisodeId.PREFIX + fingerprint),
            sourceCycleId = sourceCycleId,
            cycleRevision = 1L,
            predecessorId = null,
            problemGraphId = problemId,
            hypothesisSeedFingerprint = hypothesis,
            reasoningSearchFingerprint = search,
            counterfactualBatchFingerprint = counterfactual,
            experimentPlanFingerprint = plan,
            expectationModelFingerprint = expectation,
            predictionErrorReportFingerprint = error,
            causalCreditReportFingerprint = null,
            status = status,
            summary = summary,
            createdAt = at,
        )
    }
}
