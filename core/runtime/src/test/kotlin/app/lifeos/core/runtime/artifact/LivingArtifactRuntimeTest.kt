package app.lifeos.core.runtime.artifact

import app.lifeos.core.model.AssetId
import app.lifeos.core.model.AssetRef
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.SemanticArtifactClaim
import app.lifeos.core.model.SemanticArtifactKind
import app.lifeos.core.model.SemanticArtifactPlan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LivingArtifactRuntimeTest {
    private val runtime = LivingArtifactRuntime()

    @Test
    fun changed_claim_on_new_world_revision_becomes_exact_child_refresh_candidate() {
        val currentPlan = plan(worldRevision = 10L, content = "old fact")
        val nextPlan = plan(worldRevision = 11L, content = "new fact")
        val currentAsset = asset("current", "a".repeat(64))
        val nextAsset = asset("next", "b".repeat(64))
        val revision = revisionRef()
        val manifest = manifest(revision, currentPlan, currentAsset)

        val report = runtime.evaluate(
            currentRevision = revision,
            currentManifest = manifest,
            currentPlan = currentPlan,
            nextPlan = nextPlan,
            nextAsset = nextAsset,
        )

        assertEquals(LivingArtifactRefreshState.UPDATE_CANDIDATE, report.state)
        val candidate = assertNotNull(report.candidate)
        assertEquals(revision, candidate.parentRevision)
        assertEquals(listOf("claim-1"), candidate.changedClaimIds)
        assertEquals(emptyList(), candidate.addedClaimIds)
        assertEquals(emptyList(), candidate.removedClaimIds)
        assertEquals(10L, candidate.currentWorldRevision)
        assertEquals(11L, candidate.nextWorldRevision)
        assertFalse(candidate.finalizationAuthority)
        assertFalse(candidate.publicationAuthority)
        assertFalse(candidate.overwriteAuthority)
        assertFalse(candidate.factualAuthority)
        assertFalse(candidate.ownerPolicyAuthority)
        assertFalse(report.automaticFinalizationAllowed)
        assertFalse(report.automaticPublicationAllowed)
    }

    @Test
    fun evidence_only_change_still_refreshes_lineage_even_when_rendered_bytes_are_equal() {
        val currentPlan = plan(worldRevision = 20L, evidenceRevision = 1L)
        val nextPlan = plan(worldRevision = 21L, evidenceRevision = 2L)
        val currentAsset = asset("current", "c".repeat(64))
        val nextAsset = asset("next", "c".repeat(64))
        val revision = revisionRef()
        val manifest = manifest(revision, currentPlan, currentAsset)

        val report = runtime.evaluate(
            revision,
            manifest,
            currentPlan,
            nextPlan,
            nextAsset,
        )

        assertEquals(LivingArtifactRefreshState.UPDATE_CANDIDATE, report.state)
        assertEquals(listOf("claim-1"), assertNotNull(report.candidate).changedClaimIds)
        assertEquals(currentAsset.sha256, report.candidate?.nextAssetSha256)
    }

    @Test
    fun irrelevant_world_advance_with_identical_claims_and_bytes_stays_stable() {
        val currentPlan = plan(worldRevision = 30L)
        val nextPlan = plan(worldRevision = 31L)
        val currentAsset = asset("current", "d".repeat(64))
        val revision = revisionRef()
        val manifest = manifest(revision, currentPlan, currentAsset)

        val report = runtime.evaluate(
            revision,
            manifest,
            currentPlan,
            nextPlan,
            asset("next", currentAsset.sha256),
        )

        assertEquals(LivingArtifactRefreshState.STABLE, report.state)
        assertEquals("world-advanced-no-relevant-semantic-change", report.reasonCode)
        assertNull(report.candidate)
    }

    @Test
    fun render_drift_without_semantic_change_is_blocked() {
        val currentPlan = plan(worldRevision = 40L)
        val nextPlan = plan(worldRevision = 41L)
        val revision = revisionRef()
        val manifest = manifest(revision, currentPlan, asset("current", "e".repeat(64)))

        val report = runtime.evaluate(
            revision,
            manifest,
            currentPlan,
            nextPlan,
            asset("next", "f".repeat(64)),
        )

        assertEquals(LivingArtifactRefreshState.BLOCKED, report.state)
        assertEquals("render-drift-without-semantic-change", report.reasonCode)
        assertNull(report.candidate)
    }

    @Test
    fun unresolved_or_same_world_mutation_fails_closed() {
        val currentPlan = plan(worldRevision = 50L)
        val revision = revisionRef()
        val currentAsset = asset("current", "1".repeat(64))
        val manifest = manifest(revision, currentPlan, currentAsset)

        val unresolved = SemanticArtifactPlan(
            kind = SemanticArtifactKind.TEXT,
            claims = listOf(claim("changed", 1L)),
            unresolvedClaimIds = setOf("claim-1"),
            sourceWorldRevision = 51L,
        )
        val unresolvedReport = runtime.evaluate(
            revision,
            manifest,
            currentPlan,
            unresolved,
            asset("next", "2".repeat(64)),
        )
        assertEquals(LivingArtifactRefreshState.BLOCKED, unresolvedReport.state)
        assertEquals("next-plan-not-closed", unresolvedReport.reasonCode)

        val sameWorldChanged = plan(worldRevision = 50L, content = "changed")
        val sameWorldReport = runtime.evaluate(
            revision,
            manifest,
            currentPlan,
            sameWorldChanged,
            asset("next-2", "3".repeat(64)),
        )
        assertEquals(LivingArtifactRefreshState.BLOCKED, sameWorldReport.state)
        assertEquals("same-world-revision-mutation", sameWorldReport.reasonCode)
    }

    @Test
    fun exact_manifest_and_media_type_lineage_are_required() {
        val currentPlan = plan(worldRevision = 60L)
        val nextPlan = plan(worldRevision = 61L, content = "changed")
        val revision = revisionRef()
        val currentAsset = asset("current", "4".repeat(64))
        val wrongManifest = manifest(
            revision.copy(revisionId = ArtifactRevisionId("9".repeat(64))),
            currentPlan,
            currentAsset,
        )

        val mismatch = runtime.evaluate(
            revision,
            wrongManifest,
            currentPlan,
            nextPlan,
            asset("next", "5".repeat(64)),
        )
        assertEquals(LivingArtifactRefreshState.BLOCKED, mismatch.state)
        assertEquals("current-revision-manifest-mismatch", mismatch.reasonCode)

        val validManifest = manifest(revision, currentPlan, currentAsset)
        val mediaChange = runtime.evaluate(
            revision,
            validManifest,
            currentPlan,
            nextPlan,
            AssetRef(
                id = AssetId("pdf"),
                mediaType = "application/pdf",
                byteCount = 16,
                sha256 = "6".repeat(64),
            ),
        )
        assertEquals(LivingArtifactRefreshState.BLOCKED, mediaChange.state)
        assertEquals("living-refresh-media-type-change", mediaChange.reasonCode)
    }

    private fun plan(
        worldRevision: Long,
        content: String = "stable fact",
        evidenceRevision: Long = 1L,
    ): SemanticArtifactPlan =
        SemanticArtifactPlan(
            kind = SemanticArtifactKind.TEXT,
            claims = listOf(claim(content, evidenceRevision)),
            sourceWorldRevision = worldRevision,
        )

    private fun claim(content: String, evidenceRevision: Long): SemanticArtifactClaim =
        SemanticArtifactClaim(
            claimId = "claim-1",
            evidence = setOf(
                PhotonRevisionRef(
                    PhotonId("source-photon"),
                    evidenceRevision,
                )
            ),
            confidenceMicros = 900_000L,
            canonicalContent = content,
        )

    private fun revisionRef(): ArtifactRevisionRef =
        ArtifactRevisionRef(
            artifactId = ArtifactId("living-document"),
            revisionId = ArtifactRevisionId("7".repeat(64)),
            photonId = PhotonId("artifact_parent"),
        )

    private fun asset(id: String, sha256: String): AssetRef =
        AssetRef(
            id = AssetId(id),
            mediaType = "text/markdown",
            byteCount = 16,
            sha256 = sha256,
        )

    private fun manifest(
        revision: ArtifactRevisionRef,
        plan: SemanticArtifactPlan,
        materializedAsset: AssetRef,
    ): ArtifactRevisionManifest =
        ArtifactRevisionManifest(
            id = revision.revisionId,
            inputPhotonIds = setOf(PhotonId("source-photon")),
            participatingModules = setOf("creative", "validator"),
            stateHash = "8".repeat(64),
            materializedAsset = materializedAsset,
            validation = ArtifactValidationEvidence(
                profile = ArtifactValidationProfile(minimumDistinctModules = 1),
                result = ArtifactValidationResult(emptyList()),
            ),
            semanticPlanFingerprint = plan.fingerprint,
        )
}
