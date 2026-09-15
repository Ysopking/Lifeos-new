package app.lifeos.core.runtime.artifact

import app.lifeos.core.runtime.informationasset.InformationAssetAssembler
import app.lifeos.core.runtime.informationasset.InformationAssetAssemblyRequest
import app.lifeos.core.runtime.informationasset.InformationAssetKind
import app.lifeos.core.runtime.informationasset.InformationAssetPhotonFactory
import app.lifeos.core.runtime.informationasset.InformationAssetRequest
import app.lifeos.core.runtime.informationasset.InformationAssetRevisionVerifier
import app.lifeos.core.runtime.informationasset.InformationAssetSourceRevisionResolver
import app.lifeos.core.runtime.informationasset.InformationClaim
import app.lifeos.core.runtime.informationasset.InformationClaimState
import app.lifeos.core.runtime.informationasset.StandardInformationDomains
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class InformationAssetBackedArtifactPlanTest {
    private val now = Instant.parse("2026-09-15T16:00:00Z")

    @Test
    fun `plan retains exact refs and covers required fields`() = runBlocking {
        val answer = validated("answer", "knowledge.answer")
        val context = validated("context", "knowledge.context")
        val request = artifactRequest(setOf("knowledge.answer", "knowledge.context"))

        val plan = InformationAssetBackedArtifactPlan(request, listOf(context, answer))

        assertEquals(2, plan.revisionRefs.size)
        assertEquals(setOf("knowledge.answer", "knowledge.context"), plan.contributions.map { it.field }.toSet())
        assertEquals(plan.revisionRefs.sortedBy { it.assetId.value }, plan.revisionRefs)
    }

    @Test
    fun `plan rejects duplicate logical InformationAsset revisions`() = runBlocking {
        val input = validated("answer", "knowledge.answer")
        assertFailsWith<IllegalArgumentException> {
            InformationAssetBackedArtifactPlan(
                artifactRequest(setOf("knowledge.answer")),
                listOf(input, input),
            )
        }
    }

    @Test
    fun `plan fails when required artifact field is not represented`() = runBlocking {
        val input = validated("answer", "knowledge.answer")
        assertFailsWith<IllegalArgumentException> {
            InformationAssetBackedArtifactPlan(
                artifactRequest(setOf("knowledge.missing")),
                listOf(input),
            )
        }
    }

    private suspend fun validated(
        stableKey: String,
        semanticKey: String,
    ): ValidatedInformationAssetArtifactBatch {
        val request = InformationAssetRequest.create(
            namespace = "artifact-plan-test",
            stableKey = stableKey,
            kind = InformationAssetKind.KNOWLEDGE,
            title = stableKey,
            primaryDomainId = StandardInformationDomains.GENERAL,
            requiredSemanticKeys = setOf(semanticKey),
        )
        val claim = InformationClaim.create(
            domainId = StandardInformationDomains.GENERAL,
            semanticKey = semanticKey,
            statement = "Content for $semanticKey",
            state = InformationClaimState.ASSUMPTION,
            confidence = 0.5,
            evidenceBindingIds = emptySet(),
            explanation = "Explicit plan test assumption",
        )
        val revision = InformationAssetAssembler().assemble(
            InformationAssetAssemblyRequest(
                request = request,
                sourcePhotons = emptyList(),
                evidenceBindings = emptyList(),
                claims = listOf(claim),
                participatingModules = setOf("artifact-plan-test"),
            )
        ).revision
        val photon = InformationAssetPhotonFactory().create(revision, now)
        val verifier = InformationAssetRevisionVerifier(
            InformationAssetSourceRevisionResolver { _, _ -> null }
        )
        return ValidatedInformationAssetArtifactBridge(verifier).create(
            InformationAssetArtifactInput(revision, photon),
            now,
        )
    }

    private fun artifactRequest(requiredFields: Set<String>) = CollaborativeArtifactRequest(
        id = ArtifactId("artifact-plan-test"),
        kind = ArtifactKind.DOCUMENT,
        title = "Artifact plan test",
        targetMimeType = "text/plain",
        requestedAt = now,
        requiredFields = requiredFields,
    )
}
