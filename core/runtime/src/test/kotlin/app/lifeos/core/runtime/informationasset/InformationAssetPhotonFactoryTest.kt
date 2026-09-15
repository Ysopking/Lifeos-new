package app.lifeos.core.runtime.informationasset

import app.lifeos.core.field.EvidenceReliability
import app.lifeos.core.field.SourceAuthority
import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.field.TemporalValidity
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonPhase
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.RelationType
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InformationAssetPhotonFactoryTest {
    private val domain = StableFieldIds.domain("information-asset-photon-test")
    private val instant = Instant.parse("2026-09-15T11:00:00Z")
    private val assembler = InformationAssetAssembler()
    private val factory = InformationAssetPhotonFactory()

    @Test
    fun `factory projects exact source lineage and deterministic revision identity`() {
        val source = sourcePhoton("source", "fact")
        val revision = revision(source, statement = "Supported fact")

        val photon = factory.create(revision, instant)

        assertEquals(InformationAssetPhotonContract.MIME_TYPE, photon.mimeType)
        assertEquals(PhotonPhase.CONVERGED, photon.phase)
        assertEquals(setOf(source.id), photon.provenance.parentIds)
        assertTrue(photon.relations.any { it.target == source.id && it.type == RelationType.DERIVED_FROM })
        assertTrue("information-asset-revision:${revision.manifest.id.value}" in photon.tags)
        assertTrue(photon.content.contains("\"sourceRevision\":1"))
        assertTrue(photon.content.contains(revision.manifest.stateHash.value))
    }

    @Test
    fun `new asset revision transforms the exact parent asset Photon`() {
        val source = sourcePhoton("source", "fact")
        val firstRevision = revision(source, statement = "Version one")
        val firstPhoton = factory.create(firstRevision, instant)
        val parent = InformationAssetRevisionRef(
            assetId = firstRevision.request.id,
            revisionId = firstRevision.manifest.id,
            photonId = firstPhoton.id,
        )
        val evidence = binding(source)
        val secondClaim = InformationClaim.create(
            domainId = domain,
            semanticKey = "fact",
            statement = "Version two",
            state = InformationClaimState.SUPPORTED,
            confidence = 0.8,
            evidenceBindingIds = setOf(evidence.id),
            explanation = "Updated interpretation",
        )
        val secondRevision = assembler.assemble(
            InformationAssetAssemblyRequest(
                request = firstRevision.request,
                sourcePhotons = listOf(source),
                evidenceBindings = listOf(evidence),
                claims = listOf(secondClaim),
                participatingModules = setOf("test-module"),
                parent = parent,
            )
        ).revision

        val secondPhoton = factory.create(secondRevision, instant.plusSeconds(1))

        assertTrue(firstPhoton.id in secondPhoton.provenance.parentIds)
        assertTrue(secondPhoton.relations.any {
            it.target == firstPhoton.id && it.type == RelationType.TRANSFORMS
        })
        assertTrue("information-parent-revision:${firstRevision.manifest.id.value}" in secondPhoton.tags)
    }

    @Test
    fun `open conflict projects a reflecting Photon instead of false convergence`() {
        val source = sourcePhoton("source", "evidence")
        val evidence = binding(source)
        val first = claim("a", "Position A", evidence)
        val second = claim("b", "Position B", evidence)
        val conflict = InformationConflict.create(
            domainId = domain,
            claimIds = setOf(first.id, second.id),
            severity = 0.9,
            state = InformationConflictResolutionState.OPEN,
            explanation = "Conflict remains open",
        )
        val revision = assembler.assemble(
            InformationAssetAssemblyRequest(
                request = request(required = emptySet()),
                sourcePhotons = listOf(source),
                evidenceBindings = listOf(evidence),
                claims = listOf(first, second),
                conflicts = listOf(conflict),
                participatingModules = setOf("test-module"),
            )
        ).revision

        val photon = factory.create(revision, instant)

        assertEquals(InformationAssetResolutionState.UNRESOLVED, revision.manifest.resolution)
        assertEquals(PhotonPhase.REFLECTING, photon.phase)
        assertTrue("information-asset-resolution:unresolved" in photon.tags)
    }

    private fun revision(source: Photon, statement: String): InformationAssetRevision {
        val evidence = binding(source)
        return assembler.assemble(
            InformationAssetAssemblyRequest(
                request = request(),
                sourcePhotons = listOf(source),
                evidenceBindings = listOf(evidence),
                claims = listOf(claim("fact", statement, evidence)),
                participatingModules = setOf("test-module"),
            )
        ).revision
    }

    private fun request(required: Set<String> = setOf("fact")): InformationAssetRequest =
        InformationAssetRequest.create(
            namespace = "test",
            stableKey = "photon-asset",
            kind = InformationAssetKind.KNOWLEDGE,
            title = "Photon asset",
            primaryDomainId = domain,
            requiredSemanticKeys = required,
        )

    private fun sourcePhoton(id: String, content: String): Photon = Photon(
        id = PhotonId(id),
        revision = 1,
        content = content,
        provenance = Provenance("test", "test", instant),
    )

    private fun binding(source: Photon): InformationEvidenceBinding =
        InformationEvidenceBinding.create(
            source = PhotonRevisionReference.from(source),
            fieldEvidenceId = null,
            domainId = domain,
            authority = SourceAuthority.DOCUMENTED,
            confidence = 0.8,
            reliability = EvidenceReliability(0.9, "test"),
            validity = TemporalValidity.UNBOUNDED,
            observedAt = instant,
            payloadFingerprint = StableFieldIds.fingerprint("payload"),
        )

    private fun claim(
        key: String,
        statement: String,
        evidence: InformationEvidenceBinding,
    ): InformationClaim = InformationClaim.create(
        domainId = domain,
        semanticKey = key,
        statement = statement,
        state = InformationClaimState.SUPPORTED,
        confidence = 0.8,
        evidenceBindingIds = setOf(evidence.id),
        explanation = "Supported",
    )
}
