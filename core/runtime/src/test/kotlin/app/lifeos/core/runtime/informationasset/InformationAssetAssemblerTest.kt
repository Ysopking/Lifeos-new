package app.lifeos.core.runtime.informationasset

import app.lifeos.core.field.EvidenceReliability
import app.lifeos.core.field.SourceAuthority
import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.field.TemporalValidity
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.Provenance
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class InformationAssetAssemblerTest {
    private val assembler = InformationAssetAssembler()
    private val domain = StableFieldIds.domain("information-asset-test")
    private val observedAt = Instant.parse("2026-09-15T10:00:00Z")

    @Test
    fun `same canonical inputs produce the same revision independent of list ordering`() {
        val sourceA = sourcePhoton("source-a", "alpha")
        val sourceB = sourcePhoton("source-b", "beta")
        val bindingA = binding(sourceA, "payload-a")
        val bindingB = binding(sourceB, "payload-b")
        val claimA = claim("alpha", bindingA, "Alpha is supported")
        val claimB = claim("beta", bindingB, "Beta is supported")
        val request = request(required = setOf("alpha", "beta"))

        val first = assembler.assemble(
            InformationAssetAssemblyRequest(
                request = request,
                sourcePhotons = listOf(sourceA, sourceB),
                evidenceBindings = listOf(bindingA, bindingB),
                claims = listOf(claimA, claimB),
                participatingModules = setOf("module-b", "module-a"),
            )
        ).revision
        val second = assembler.assemble(
            InformationAssetAssemblyRequest(
                request = request,
                sourcePhotons = listOf(sourceB, sourceA),
                evidenceBindings = listOf(bindingB, bindingA),
                claims = listOf(claimB, claimA),
                participatingModules = setOf("module-a", "module-b"),
            )
        ).revision

        assertEquals(first.manifest.id, second.manifest.id)
        assertEquals(first.manifest.stateHash, second.manifest.stateHash)
        assertEquals(first, second)
    }

    @Test
    fun `changing exact Photon revision changes the asset revision`() {
        val firstSource = sourcePhoton("source", "same-content", revision = 1)
        val secondSource = firstSource.copy(revision = 2)
        val firstBinding = binding(firstSource, "payload")
        val secondBinding = binding(secondSource, "payload")
        val request = request()

        val first = assemble(request, firstSource, firstBinding, claim("fact", firstBinding, "Fact"))
        val second = assemble(request, secondSource, secondBinding, claim("fact", secondBinding, "Fact"))

        assertNotEquals(first.manifest.sourcePhotons.single().inputStateHash, second.manifest.sourcePhotons.single().inputStateHash)
        assertNotEquals(first.manifest.id, second.manifest.id)
    }

    @Test
    fun `changing source state at the same Photon id and revision changes the asset revision`() {
        val firstSource = sourcePhoton("source", "state-one")
        val secondSource = firstSource.copy(content = "state-two")
        val firstBinding = binding(firstSource, "payload")
        val secondBinding = binding(secondSource, "payload")
        val request = request()

        val first = assemble(request, firstSource, firstBinding, claim("fact", firstBinding, "Fact"))
        val second = assemble(request, secondSource, secondBinding, claim("fact", secondBinding, "Fact"))

        assertNotEquals(first.manifest.sourcePhotons.single().semanticStateHash, second.manifest.sourcePhotons.single().semanticStateHash)
        assertNotEquals(first.manifest.id, second.manifest.id)
    }

    @Test
    fun `binding must match the exact supplied Photon state`() {
        val original = sourcePhoton("source", "original")
        val mutated = original.copy(content = "different")
        val staleBinding = binding(original, "payload")

        assertFailsWith<IllegalArgumentException> {
            assemble(request(), mutated, staleBinding, claim("fact", staleBinding, "Fact"))
        }
    }

    @Test
    fun `only explicit assumptions may exist without evidence`() {
        val unsupported = InformationClaim.create(
            domainId = domain,
            semanticKey = "fact",
            statement = "Unsupported fact",
            state = InformationClaimState.SUPPORTED,
            confidence = 0.4,
            evidenceBindingIds = emptySet(),
            explanation = "No evidence",
        )
        assertFailsWith<IllegalArgumentException> {
            assembler.assemble(
                InformationAssetAssemblyRequest(
                    request = request(),
                    sourcePhotons = emptyList(),
                    evidenceBindings = emptyList(),
                    claims = listOf(unsupported),
                    participatingModules = setOf("test-module"),
                )
            )
        }

        val assumption = InformationClaim.create(
            domainId = domain,
            semanticKey = "fact",
            statement = "Explicit assumption",
            state = InformationClaimState.ASSUMPTION,
            confidence = 0.3,
            evidenceBindingIds = emptySet(),
            explanation = "Assumption is intentionally unverified",
        )
        val result = assembler.assemble(
            InformationAssetAssemblyRequest(
                request = request(),
                sourcePhotons = emptyList(),
                evidenceBindings = emptyList(),
                claims = listOf(assumption),
                participatingModules = setOf("test-module"),
            )
        )
        assertEquals(InformationAssetResolutionState.CONVERGED, result.revision.manifest.resolution)
    }

    @Test
    fun `open conflicts are preserved and force unresolved state`() {
        val source = sourcePhoton("source", "evidence")
        val evidence = binding(source, "payload")
        val firstClaim = claim("position-a", evidence, "Position A")
        val secondClaim = claim("position-b", evidence, "Position B")
        val conflict = InformationConflict.create(
            domainId = domain,
            claimIds = setOf(firstClaim.id, secondClaim.id),
            severity = 0.8,
            state = InformationConflictResolutionState.OPEN,
            explanation = "Both positions remain supported",
        )

        val revision = assembler.assemble(
            InformationAssetAssemblyRequest(
                request = request(required = emptySet()),
                sourcePhotons = listOf(source),
                evidenceBindings = listOf(evidence),
                claims = listOf(firstClaim, secondClaim),
                conflicts = listOf(conflict),
                participatingModules = setOf("test-module"),
            )
        ).revision

        assertEquals(InformationAssetResolutionState.UNRESOLVED, revision.manifest.resolution)
        assertEquals(listOf(conflict), revision.conflicts)
    }

    @Test
    fun `assembly never mutates source Photons`() {
        val source = sourcePhoton("source", "immutable")
        val before = source.copy()
        val evidence = binding(source, "payload")

        assemble(request(), source, evidence, claim("fact", evidence, "Fact"))

        assertEquals(before, source)
    }

    private fun assemble(
        request: InformationAssetRequest,
        source: Photon,
        evidence: InformationEvidenceBinding,
        claim: InformationClaim,
    ): InformationAssetRevision = assembler.assemble(
        InformationAssetAssemblyRequest(
            request = request,
            sourcePhotons = listOf(source),
            evidenceBindings = listOf(evidence),
            claims = listOf(claim),
            participatingModules = setOf("test-module"),
        )
    ).revision

    private fun request(required: Set<String> = setOf("fact")): InformationAssetRequest =
        InformationAssetRequest.create(
            namespace = "test",
            stableKey = "asset",
            kind = InformationAssetKind.KNOWLEDGE,
            title = "Test asset",
            primaryDomainId = domain,
            requiredSemanticKeys = required,
        )

    private fun sourcePhoton(id: String, content: String, revision: Long = 1): Photon = Photon(
        id = PhotonId(id),
        revision = revision,
        content = content,
        provenance = Provenance(
            source = "test",
            actor = "test",
            createdAt = observedAt,
        ),
    )

    private fun binding(source: Photon, payload: String): InformationEvidenceBinding =
        InformationEvidenceBinding.create(
            source = PhotonRevisionReference.from(source),
            fieldEvidenceId = null,
            domainId = domain,
            authority = SourceAuthority.DOCUMENTED,
            confidence = 0.8,
            reliability = EvidenceReliability(0.9, "test evidence"),
            validity = TemporalValidity.UNBOUNDED,
            observedAt = observedAt,
            payloadFingerprint = StableFieldIds.fingerprint(payload),
        )

    private fun claim(
        key: String,
        evidence: InformationEvidenceBinding,
        statement: String,
    ): InformationClaim = InformationClaim.create(
        domainId = domain,
        semanticKey = key,
        statement = statement,
        state = InformationClaimState.SUPPORTED,
        confidence = 0.8,
        evidenceBindingIds = setOf(evidence.id),
        explanation = "Supported by test evidence",
    )
}
