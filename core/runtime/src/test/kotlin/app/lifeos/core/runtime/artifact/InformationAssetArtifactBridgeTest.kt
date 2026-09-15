package app.lifeos.core.runtime.artifact

import app.lifeos.core.runtime.informationasset.InformationAssetAssembler
import app.lifeos.core.runtime.informationasset.InformationAssetAssemblyRequest
import app.lifeos.core.runtime.informationasset.InformationAssetKind
import app.lifeos.core.runtime.informationasset.InformationAssetPhotonFactory
import app.lifeos.core.runtime.informationasset.InformationAssetRequest
import app.lifeos.core.runtime.informationasset.InformationAssetResolutionState
import app.lifeos.core.runtime.informationasset.InformationClaim
import app.lifeos.core.runtime.informationasset.InformationClaimState
import app.lifeos.core.runtime.informationasset.InformationConflict
import app.lifeos.core.runtime.informationasset.InformationConflictResolutionState
import app.lifeos.core.runtime.informationasset.StandardInformationDomains
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class InformationAssetArtifactBridgeTest {
    private val createdAt = Instant.parse("2026-09-15T18:00:00Z")

    @Test
    fun `bridge retains exact InformationAsset revision state and Photon revision`() {
        val revision = revision("Explicit assumption")
        val photon = InformationAssetPhotonFactory().create(revision, createdAt)

        val batch = InformationAssetArtifactContributionFactory().create(
            InformationAssetArtifactInput(revision, photon)
        )

        assertEquals(revision.request.id, batch.source.assetId)
        assertEquals(revision.manifest.id, batch.source.revisionId)
        assertEquals(revision.manifest.stateHash, batch.source.stateHash)
        assertEquals(photon.id, batch.source.photonId)
        assertEquals(photon.revision, batch.source.photonRevision)
        assertEquals(revision.manifest.domainIds, batch.source.domainIds)
        assertEquals(revision.manifest.resolution, batch.source.resolution)
        assertEquals(1, batch.contributions.size)
        assertEquals(setOf(photon.id), batch.contributions.single().provenance.parentIds)
        assertEquals(batch.source.sourceDescriptor(), batch.contributions.single().source)
        assertTrue(batch.contributions.single().source.contains(revision.manifest.id.value))
        assertTrue(batch.contributions.single().source.contains(revision.manifest.stateHash.value))
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

    @Test
    fun `different semantic revisions produce different artifact contribution fingerprints`() {
        val revisionA = revision("Assumption A")
        val revisionB = revision("Assumption B")
        val batchA = InformationAssetArtifactContributionFactory().create(
            InformationAssetArtifactInput(revisionA, InformationAssetPhotonFactory().create(revisionA, createdAt))
        )
        val batchB = InformationAssetArtifactContributionFactory().create(
            InformationAssetArtifactInput(revisionB, InformationAssetPhotonFactory().create(revisionB, createdAt))
        )

        assertNotEquals(batchA.source.revisionId, batchB.source.revisionId)
        assertNotEquals(batchA.source.stateHash, batchB.source.stateHash)
        assertNotEquals(
            batchA.contributions.single().contentFingerprint(),
            batchB.contributions.single().contentFingerprint(),
        )
    }

    @Test
    fun `unresolved InformationAsset remains explicitly unresolved in artifact source binding`() {
        val revision = conflictedRevision()
        val photon = InformationAssetPhotonFactory().create(revision, createdAt)

        val batch = InformationAssetArtifactContributionFactory().create(
            InformationAssetArtifactInput(revision, photon)
        )

        assertEquals(InformationAssetResolutionState.UNRESOLVED, batch.source.resolution)
        assertTrue(batch.source.sourceDescriptor().contains("resolution=UNRESOLVED"))
        assertEquals(2, batch.contributions.size)
    }

    private fun revision(statement: String) = run {
        val request = request()
        val claim = assumption(statement)
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

    private fun conflictedRevision() = run {
        val request = request()
        val first = assumption("Position A")
        val second = assumption("Position B")
        val conflict = InformationConflict.create(
            domainId = StandardInformationDomains.GENERAL,
            claimIds = setOf(first.id, second.id),
            severity = 0.8,
            state = InformationConflictResolutionState.OPEN,
            explanation = "Both positions remain intentionally unresolved",
        )
        InformationAssetAssembler().assemble(
            InformationAssetAssemblyRequest(
                request = request,
                sourcePhotons = emptyList(),
                evidenceBindings = emptyList(),
                claims = listOf(first, second),
                conflicts = listOf(conflict),
                participatingModules = setOf("bridge-test"),
            )
        ).revision
    }

    private fun request() = InformationAssetRequest.create(
        namespace = "bridge-test",
        stableKey = "knowledge-1",
        kind = InformationAssetKind.KNOWLEDGE,
        title = "Knowledge",
        primaryDomainId = StandardInformationDomains.GENERAL,
        requiredSemanticKeys = setOf("knowledge.answer"),
    )

    private fun assumption(statement: String) = InformationClaim.create(
        domainId = StandardInformationDomains.GENERAL,
        semanticKey = "knowledge.answer",
        statement = statement,
        state = InformationClaimState.ASSUMPTION,
        confidence = 0.5,
        evidenceBindingIds = emptySet(),
        explanation = "Explicit bridge test assumption",
    )
}
