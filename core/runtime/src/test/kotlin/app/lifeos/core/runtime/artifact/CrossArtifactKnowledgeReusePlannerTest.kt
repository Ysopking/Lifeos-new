package app.lifeos.core.runtime.artifact

import app.lifeos.core.model.DocumentDepth
import app.lifeos.core.model.DocumentGoal
import app.lifeos.core.model.DocumentOutputFormat
import app.lifeos.core.model.DocumentPurpose
import app.lifeos.core.model.DocumentStructurePlanner
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.SemanticArtifactClaim
import app.lifeos.core.model.SemanticArtifactKind
import app.lifeos.core.model.SemanticArtifactPlan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CrossArtifactKnowledgeReusePlannerTest {
    private val planner = CrossArtifactKnowledgeReusePlanner()

    @Test
    fun exact_claims_reuse_across_artifacts_with_exact_source_revision_lineage() {
        val source = plan(
            worldRevision = 100L,
            contents = listOf("stable one", "stable two"),
        )
        val target = plan(
            worldRevision = 101L,
            contents = listOf("stable one", "stable two"),
        )
        val revision = revisionRef()
        val report = planner.plan(
            sourceRevision = revision,
            sourceManifest = manifest(revision, source),
            sourcePlan = source,
            targetPlan = target,
        )

        assertEquals(listOf("claim-1", "claim-2"), report.bindings.map { it.claimId })
        assertTrue(report.unreusableClaimIds.isEmpty())
        assertEquals(revision, report.sourceRevision)
        assertTrue(report.bindings.all { it.sourceRevision == revision })
        assertTrue(report.bindings.all { it.evidenceStableKeys.isNotEmpty() })
        assertFalse(report.automaticCopyAllowed)
        assertFalse(report.generatedTextEvidenceAuthority)
        assertFalse(report.finalizationAuthority)
    }

    @Test
    fun changed_target_claim_is_not_reused_and_is_explicitly_reported() {
        val source = plan(200L, listOf("same", "old"))
        val target = plan(201L, listOf("same", "new"))
        val revision = revisionRef()

        val report = planner.plan(
            revision,
            manifest(revision, source),
            source,
            target,
        )

        assertEquals(listOf("claim-1"), report.bindings.map { it.claimId })
        assertEquals(listOf("claim-2"), report.unreusableClaimIds)
        assertNull(report.structureHint)
    }

    @Test
    fun exact_source_structure_can_be_reused_only_as_non_authoritative_hint() {
        val source = plan(300L, listOf("first", "second"))
        val target = plan(301L, listOf("first", "second"))
        val goal = DocumentGoal.create(
            semanticPlan = source,
            purpose = DocumentPurpose.EXPLAIN,
            audience = "reader",
            languageTag = "en",
            outputFormat = DocumentOutputFormat.MARKDOWN,
            depth = DocumentDepth.EXHAUSTIVE,
        )
        val structure = DocumentStructurePlanner().plan(goal, source)
        val revision = revisionRef()

        val report = planner.plan(
            sourceRevision = revision,
            sourceManifest = manifest(revision, source),
            sourcePlan = source,
            targetPlan = target,
            sourceStructure = structure,
        )

        val hint = assertNotNull(report.structureHint)
        assertEquals(structure.fingerprint, hint.sourceStructureFingerprint)
        assertEquals(structure.coveredClaimIds, hint.coveredClaimIds)
        assertFalse(hint.targetStructureAuthority)
        assertFalse(hint.proseAuthority)
        assertFalse(hint.finalizationAuthority)
    }

    @Test
    fun partial_claim_request_does_not_smuggle_full_source_structure() {
        val source = plan(400L, listOf("first", "second"))
        val target = plan(401L, listOf("first", "second"))
        val goal = DocumentGoal.create(
            semanticPlan = source,
            purpose = DocumentPurpose.EXPLAIN,
            audience = "reader",
            languageTag = "en",
            outputFormat = DocumentOutputFormat.MARKDOWN,
            depth = DocumentDepth.EXHAUSTIVE,
        )
        val structure = DocumentStructurePlanner().plan(goal, source)
        val revision = revisionRef()

        val report = planner.plan(
            sourceRevision = revision,
            sourceManifest = manifest(revision, source),
            sourcePlan = source,
            targetPlan = target,
            requestedClaimIds = listOf("claim-1"),
            sourceStructure = structure,
        )

        assertEquals(listOf("claim-1"), report.bindings.map { it.claimId })
        assertNull(report.structureHint)
    }

    @Test
    fun source_revision_and_semantic_lineage_must_match_exactly() {
        val source = plan(500L, listOf("one"))
        val target = plan(501L, listOf("one"))
        val revision = revisionRef()
        val wrongRevision = revision.copy(
            revisionId = ArtifactRevisionId("9".repeat(64))
        )

        assertFailsWith<IllegalArgumentException> {
            planner.plan(
                sourceRevision = revision,
                sourceManifest = manifest(wrongRevision, source),
                sourcePlan = source,
                targetPlan = target,
            )
        }

        val wrongPlan = plan(500L, listOf("other"))
        assertFailsWith<IllegalArgumentException> {
            planner.plan(
                sourceRevision = revision,
                sourceManifest = manifest(revision, wrongPlan),
                sourcePlan = source,
                targetPlan = target,
            )
        }
    }

    private fun plan(
        worldRevision: Long,
        contents: List<String>,
    ): SemanticArtifactPlan =
        SemanticArtifactPlan(
            kind = SemanticArtifactKind.TEXT,
            claims = contents.mapIndexed { index, content ->
                SemanticArtifactClaim(
                    claimId = "claim-" + (index + 1),
                    evidence = setOf(
                        PhotonRevisionRef(
                            PhotonId("source-" + (index + 1)),
                            1L,
                        )
                    ),
                    confidenceMicros = 900_000L,
                    canonicalContent = content,
                )
            },
            sourceWorldRevision = worldRevision,
        )

    private fun revisionRef(): ArtifactRevisionRef =
        ArtifactRevisionRef(
            artifactId = ArtifactId("source-artifact"),
            revisionId = ArtifactRevisionId("7".repeat(64)),
            photonId = PhotonId("source_artifact_photon"),
        )

    private fun manifest(
        revision: ArtifactRevisionRef,
        plan: SemanticArtifactPlan,
    ): ArtifactRevisionManifest =
        ArtifactRevisionManifest(
            id = revision.revisionId,
            inputPhotonIds = setOf(PhotonId("input-photon")),
            participatingModules = setOf("creative"),
            stateHash = "8".repeat(64),
            validation = ArtifactValidationEvidence(
                profile = ArtifactValidationProfile(1),
                result = ArtifactValidationResult(emptyList()),
            ),
            semanticPlanFingerprint = plan.fingerprint,
        )
}
