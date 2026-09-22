package app.lifeos.core.runtime.research

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.model.PhotonId
import app.lifeos.core.reasoning.ProblemStateGraphId
import app.lifeos.core.runtime.goal.GoalPlanDefinition
import app.lifeos.core.runtime.goal.GoalPlanTransition
import app.lifeos.core.runtime.goal.GoalStepSpec
import app.lifeos.core.runtime.goal.GoalStepState
import app.lifeos.core.runtime.level7.StructuralSignature
import app.lifeos.core.runtime.level7.StructuralSimilarityEngine
import app.lifeos.core.runtime.reasoning.LearningEpisode
import app.lifeos.core.runtime.reasoning.LearningEpisodeId
import app.lifeos.core.runtime.reasoning.LearningEpisodeStatus
import app.lifeos.core.runtime.reasoning.LearningEpisodeSummary
import app.lifeos.core.runtime.reasoning.ProceduralSkillCandidate
import app.lifeos.core.runtime.reasoning.ProceduralSkillInductionEngine
import app.lifeos.core.runtime.reasoning.ProceduralSkillTrace
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CrossSurfaceWorkflowTransferEngineTest {
    @Test
    fun web_to_app_transfer_reuses_b377_and_remains_non_authoritative() {
        val sourceSkill = sourceSkill()
        val report = inductionReport(sourceSkill)
        val source = WorkflowSurfaceIdentity(WorkflowSurfaceKind.WEB_SITE, "example.com")
        val target = WorkflowSurfaceIdentity(WorkflowSurfaceKind.ANDROID_APP, "com.example.target")
        val transfer = StructuralSimilarityEngine().candidate(
            source = signature(source.domainId, sourceSkill.shapeFingerprint),
            target = signature(target.domainId, sourceSkill.shapeFingerprint),
            validationFingerprint = "validated-cross-surface",
        )

        val candidate = CrossSurfaceWorkflowTransferEngine().propose(
            CrossSurfaceWorkflowTransferRequest(
                inductionReport = report,
                sourceSkill = sourceSkill,
                sourceSurface = source,
                targetSurface = target,
                transfer = transfer,
                targetObjectivesByStepKey = mapOf(
                    "inspect" to "Inspect target app state.",
                    "report" to "Report target app result.",
                ),
            )
        )

        assertEquals(source.domainId, candidate.generalizedSkill.sourceDomainId)
        assertEquals(target.domainId, candidate.generalizedSkill.targetDomainId)
        assertTrue(candidate.requiresShadowValidation)
        assertFalse(candidate.semanticIdentityEstablished)
        assertFalse(candidate.executionAuthority)
        assertFalse(candidate.activationAuthority)
        assertFalse(candidate.promotionAuthority)
        assertFalse(candidate.ownerPolicyAuthority)
    }

    @Test
    fun source_skill_must_belong_to_exact_b418_report() {
        val first = sourceSkill("a")
        val other = sourceSkill("b")
        val report = inductionReport(first)
        val source = WorkflowSurfaceIdentity(WorkflowSurfaceKind.WEB_SITE, "example.com")
        val target = WorkflowSurfaceIdentity(WorkflowSurfaceKind.WEB_SITE, "other.example")
        val transfer = StructuralSimilarityEngine().candidate(
            source = signature(source.domainId, other.shapeFingerprint),
            target = signature(target.domainId, other.shapeFingerprint),
            validationFingerprint = "validated-other",
        )

        assertFailsWith<IllegalArgumentException> {
            CrossSurfaceWorkflowTransferRequest(
                inductionReport = report,
                sourceSkill = other,
                sourceSurface = source,
                targetSurface = target,
                transfer = transfer,
                targetObjectivesByStepKey = objectives(),
            )
        }
    }

    @Test
    fun structural_domains_must_match_exact_surface_identities() {
        val sourceSkill = sourceSkill()
        val report = inductionReport(sourceSkill)
        val source = WorkflowSurfaceIdentity(WorkflowSurfaceKind.WEB_SITE, "example.com")
        val target = WorkflowSurfaceIdentity(WorkflowSurfaceKind.ANDROID_APP, "com.example.target")
        val transfer = StructuralSimilarityEngine().candidate(
            source = signature("web_site:wrong", sourceSkill.shapeFingerprint),
            target = signature(target.domainId, sourceSkill.shapeFingerprint),
            validationFingerprint = "validated-wrong-source",
        )

        assertFailsWith<IllegalArgumentException> {
            CrossSurfaceWorkflowTransferRequest(
                inductionReport = report,
                sourceSkill = sourceSkill,
                sourceSurface = source,
                targetSurface = target,
                transfer = transfer,
                targetObjectivesByStepKey = objectives(),
            )
        }
    }

    @Test
    fun exact_inputs_are_deterministic() {
        val sourceSkill = sourceSkill()
        val report = inductionReport(sourceSkill)
        val source = WorkflowSurfaceIdentity(WorkflowSurfaceKind.WEB_SITE, "example.com")
        val target = WorkflowSurfaceIdentity(WorkflowSurfaceKind.WEB_SITE, "other.example")
        val transfer = StructuralSimilarityEngine().candidate(
            source = signature(source.domainId, sourceSkill.shapeFingerprint),
            target = signature(target.domainId, sourceSkill.shapeFingerprint),
            validationFingerprint = "validated-repeat",
        )
        val request = CrossSurfaceWorkflowTransferRequest(
            inductionReport = report,
            sourceSkill = sourceSkill,
            sourceSurface = source,
            targetSurface = target,
            transfer = transfer,
            targetObjectivesByStepKey = objectives(),
        )
        val engine = CrossSurfaceWorkflowTransferEngine()

        assertEquals(engine.propose(request), engine.propose(request))
    }

    private fun objectives() = mapOf(
        "inspect" to "Inspect target.",
        "report" to "Report target.",
    )

    private fun signature(domain: String, topology: String) = StructuralSignature(
        domainId = domain,
        topologyFingerprint = topology,
        relationFingerprint = "workflow-relations",
        dimensionFingerprint = "workflow-dimensions",
    )

    private fun inductionReport(sourceSkill: ProceduralSkillCandidate): WorkflowSkillInductionReport {
        val supports = listOf("a".repeat(64), "b".repeat(64)).sorted()
        val candidates = listOf(sourceSkill)
        return WorkflowSkillInductionReport(
            supportFingerprints = supports,
            candidates = candidates,
            fingerprint = b418ReportFingerprint(
                supportFingerprints = supports,
                candidateFingerprints = candidates.map { it.fingerprint() }.sorted(),
            ),
        )
    }

    private fun b418ReportFingerprint(
        supportFingerprints: List<String>,
        candidateFingerprints: List<String>,
    ): String = b418Fingerprint(
        "workflow-skill-induction-report/v1",
        *supportFingerprints.sorted().map { "support:" + it }.toTypedArray(),
        *candidateFingerprints.sorted().map { "candidate:" + it }.toTypedArray(),
    )

    private fun b418Fingerprint(domain: String, vararg parts: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun update(value: String) {
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            digest.update(
                byteArrayOf(
                    (bytes.size ushr 24).toByte(),
                    (bytes.size ushr 16).toByte(),
                    (bytes.size ushr 8).toByte(),
                    bytes.size.toByte(),
                )
            )
            digest.update(bytes)
        }
        update(domain)
        parts.forEach(::update)
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun sourceSkill(seed: String = "a"): ProceduralSkillCandidate {
        val first = trace("cycle-" + seed + "-1")
        val second = trace("cycle-" + seed + "-2")
        return ProceduralSkillInductionEngine().induce(
            traces = listOf(first, second),
            semanticKeysByShape = mapOf(first.shapeFingerprint to "skill:cross-surface-" + seed),
        ).single()
    }

    private fun trace(sourceCycleId: String): ProceduralSkillTrace {
        val plan = GoalPlanDefinition.create(
            sourceGoalPhotonId = PhotonId("goal-" + sourceCycleId),
            sourceGoalPhotonRevision = 1L,
            stepSpecs = listOf(
                GoalStepSpec("inspect", "Inspect source.", priority = 10),
                GoalStepSpec(
                    "report",
                    "Report source.",
                    dependencyKeys = setOf("inspect"),
                    priority = 5,
                ),
            ),
            createdAt = NOW,
        )
        val transitions = plan.steps.mapIndexed { index, step ->
            GoalPlanTransition.create(
                planId = plan.id,
                predecessorId = null,
                stepId = step.id,
                fromState = GoalStepState.RUNNING,
                toState = GoalStepState.COMPLETED,
                reason = "verified completion",
                sourceFingerprint = StableFieldIds.fingerprint("source", sourceCycleId, step.key),
                actionId = "action-" + sourceCycleId + "-" + step.key,
                actionIdempotencyKey = "idem-" + sourceCycleId + "-" + step.key,
                outcomePhotonId = PhotonId("outcome-" + sourceCycleId + "-" + step.key),
                createdAt = NOW.plusSeconds(index.toLong()),
            )
        }
        return ProceduralSkillTrace.from(
            episode = learningEpisode(sourceCycleId),
            plan = plan,
            transitions = transitions,
        )
    }

    private fun learningEpisode(cycle: String): LearningEpisode {
        val problemId = ProblemStateGraphId(
            ProblemStateGraphId.PREFIX + StableFieldIds.fingerprint("problem", cycle)
        )
        val summary = LearningEpisodeSummary(
            expectedActions = 1,
            missingObservations = 0,
            incompleteObservations = 0,
            unverifiedObservations = 0,
            withinExpectedBand = 1,
            outsideExpectedBand = 0,
            causalAssignments = 0,
        )
        val parts = listOf(
            StableFieldIds.fingerprint("hypothesis", cycle),
            StableFieldIds.fingerprint("search", cycle),
            StableFieldIds.fingerprint("counterfactual", cycle),
            StableFieldIds.fingerprint("plan", cycle),
            StableFieldIds.fingerprint("expectation", cycle),
            StableFieldIds.fingerprint("error", cycle),
        )
        val fp = StableFieldIds.fingerprint(
            "learning-episode/v1",
            cycle,
            "1",
            "",
            problemId.value,
            parts[0],
            parts[1],
            parts[2],
            parts[3],
            parts[4],
            parts[5],
            "",
            LearningEpisodeStatus.VERIFIED_OUTCOME.name,
            summary.fingerprint(),
            NOW.toString(),
        )
        return LearningEpisode(
            id = LearningEpisodeId(LearningEpisodeId.PREFIX + fp),
            sourceCycleId = cycle,
            cycleRevision = 1L,
            predecessorId = null,
            problemGraphId = problemId,
            hypothesisSeedFingerprint = parts[0],
            reasoningSearchFingerprint = parts[1],
            counterfactualBatchFingerprint = parts[2],
            experimentPlanFingerprint = parts[3],
            expectationModelFingerprint = parts[4],
            predictionErrorReportFingerprint = parts[5],
            causalCreditReportFingerprint = null,
            status = LearningEpisodeStatus.VERIFIED_OUTCOME,
            summary = summary,
            createdAt = NOW,
        )
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-22T00:00:00Z")
    }
}
