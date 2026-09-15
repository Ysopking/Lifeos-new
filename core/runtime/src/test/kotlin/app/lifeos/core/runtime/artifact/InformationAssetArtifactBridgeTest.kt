package app.lifeos.core.runtime.artifact

import app.lifeos.core.runtime.informationasset.InformationAssetAssembler
import app.lifeos.core.runtime.informationasset.InformationAssetAssemblyRequest
import app.lifeos.core.runtime.informationasset.InformationAssetKind
import app.lifeos.core.runtime.informationasset.InformationAssetPhotonFactory
import app.lifeos.core.runtime.informationasset.InformationAssetRequest
import app.lifeos.core.runtime.informationasset.InformationClaim
import app.lifeos.core.runtime.informationasset.InformationClaimState
import app.lifeos.core.runtime.informationasset.StandardInformationDomains
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class InformationAssetArtifactBridgeTest {
    private val createdAt = Instant.parse("2026-09-15T16:00:00Z")

    @Test
    fun `bridge contribution retains exact InformationAsset revision Photon`() {
        val revision = revision("Explicit assumption")
        val photon = InformationAssetPhotonFactory().create(revision, createdAt)

        val batch = InformationAssetArtifactContributionFactory().create(
            InformationAssetArtifactInput(revision, photon)
        )

        assertEquals(revision.manifest.id, batch.sourceRevision.revisionId)
        assertEquals(photon.id, batch.sourceRevision.photonId)
        assertEquals(1, batch.contributions.size)
        assertEquals(setOf(photon.id), batch.contributions.single().provenance.parentIds)
        assertTrue(batch.contributions.single().source.endsWith(revision.manifest.id.value))
    }

    @Test
    fun `bridge rejects a Photon from a different InformationAsset revision`() {
        val revisionA = revision("Assumption A")
        val revisionB = revision("Assumption B")
        val photonB = InformationAssetPhotonFactory().create(revisionB, createdAt)

        assertFailsWith<IllegalArgumentException> {
            InformationAssetArtifactInput(revisionA, photonB)
        }
    }

    private fun revision(statement: String) = run {
        val request = InformationAssetRequest.create(
            namespace = "bridge-test",
            stableKey = "knowledge-1",
            kind = InformationAssetKind.KNOWLEDGE,
            title = "Knowledge",
            primaryDomainId = StandardInformationDomains.GENERAL,
            requiredSemanticKeys = setOf("knowledge.answer"),
        )
        val claim = InformationClaim.create(
            domainId = StandardInformationDomains.GENERAL,
            semanticKey = "knowledge.answer",
            statement = statement,
            state = InformationClaimState.ASSUMPTION,
            confidence = 0.5,
            evidenceBindingIds = emptySet(),
            explanation = "Explicit bridge test assumption",
        )
        InformationAssetAssembler().assemble(
            InformationAssetAssemblyRequest(
                request = request,
                sourcePhotons = emptyList(),
                evidenceBindings = emptyList(),
                claims = listOf(claim),
                participatingModules = setOf("bridge-test"),
            )
        ).revision
    }
}
