package app.lifeos.core.runtime.informationasset

import app.lifeos.core.field.EvidenceReliability
import app.lifeos.core.field.SourceAuthority
import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.field.TemporalValidity
import app.lifeos.core.model.CognitiveStateHash
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.Provenance
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class InformationAssetCodecTest {
    private val domain = StableFieldIds.domain("information-asset-codec-test")
    private val observedAt = Instant.parse("2026-09-15T12:00:00Z")

    @Test
    fun `codec round trip retains exact source revision and hashes`() {
        val revision = revision(sourceRevision = 7L)

        val encoded = InformationAssetCodec.encode(revision)
        val decoded = InformationAssetCodec.decode(encoded)

        assertEquals(revision, decoded)
        assertEquals(7L, decoded.manifest.sourcePhotons.single().revision)
        assertEquals(
            revision.manifest.sourcePhotons.single().inputStateHash,
            decoded.manifest.sourcePhotons.single().inputStateHash,
        )
        assertEquals(
            revision.manifest.sourcePhotons.single().semanticStateHash,
            decoded.manifest.sourcePhotons.single().semanticStateHash,
        )
        assertContentEquals(encoded, InformationAssetCodec.encode(decoded))
    }

    @Test
    fun `canonical codec output is independent of caller collection order`() {
        val first = twoClaimRevision(reverse = false)
        val second = twoClaimRevision(reverse = true)

        assertEquals(first.manifest.id, second.manifest.id)
        assertContentEquals(
            InformationAssetCodec.encode(first),
            InformationAssetCodec.encode(second),
        )
    }

    @Test
    fun `codec rejects a revision with a forged manifest state hash`() {
        val valid = revision()
        val forged = valid.copy(
            manifest = valid.manifest.copy(
                stateHash = CognitiveStateHash("f".repeat(64)),
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            InformationAssetCodec.encode(forged)
        }
    }

    @Test
    fun `codec rejects a revision with a forged revision id`() {
        val valid = revision()
        val forged = valid.copy(
            manifest = valid.manifest.copy(
                id = InformationAssetRevisionId("e".repeat(64)),
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            InformationAssetCodec.encode(forged)
        }
    }

    private fun revision(sourceRevision: Long = 1L): InformationAssetRevision {
        val source = sourcePhoton("codec-source", "payload", sourceRevision)
        val binding = binding(source, "payload")
        val claim = claim("fact", "Codec fact", binding)
        return InformationAssetAssembler().assemble(
            InformationAssetAssemblyRequest(
                request = request(setOf("fact")),
                sourcePhotons = listOf(source),
                evidenceBindings = listOf(binding),
                claims = listOf(claim),
                participatingModules = setOf("codec-module"),
            ),
        ).revision
    }

    private fun twoClaimRevision(reverse: Boolean): InformationAssetRevision {
        val sourceA = sourcePhoton("codec-a", "alpha", 2L)
        val sourceB = sourcePhoton("codec-b", "beta", 4L)
        val bindingA = binding(sourceA, "alpha")
        val bindingB = binding(sourceB, "beta")
        val claimA = claim("alpha", "Alpha", bindingA)
        val claimB = claim("beta", "Beta", bindingB)
        val sources = if (reverse) listOf(sourceB, sourceA) else listOf(sourceA, sourceB)
        val bindings = if (reverse) listOf(bindingB, bindingA) else listOf(bindingA, bindingB)
        val claims = if (reverse) listOf(claimB, claimA) else listOf(claimA, claimB)
        return InformationAssetAssembler().assemble(
            InformationAssetAssemblyRequest(
                request = request(setOf("alpha", "beta")),
                sourcePhotons = sources,
                evidenceBindings = bindings,
                claims = claims,
                participatingModules = if (reverse) setOf("module-b", "module-a") else setOf("module-a", "module-b"),
            ),
        ).revision
    }

    private fun request(required: Set<String>): InformationAssetRequest = InformationAssetRequest.create(
        namespace = "codec-test",
        stableKey = "asset",
        kind = InformationAssetKind.KNOWLEDGE,
        title = "Codec test asset",
        primaryDomainId = domain,
        requiredSemanticKeys = required,
    )

    private fun sourcePhoton(id: String, content: String, revision: Long): Photon = Photon(
        id = PhotonId(id),
        revision = revision,
        content = content,
        provenance = Provenance(
            source = "codec-test",
            actor = "codec-test",
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
            reliability = EvidenceReliability(0.9, "codec-test"),
            validity = TemporalValidity.UNBOUNDED,
            observedAt = observedAt,
            payloadFingerprint = StableFieldIds.fingerprint(payload),
        )

    private fun claim(
        key: String,
        statement: String,
        binding: InformationEvidenceBinding,
    ): InformationClaim = InformationClaim.create(
        domainId = domain,
        semanticKey = key,
        statement = statement,
        state = InformationClaimState.SUPPORTED,
        confidence = 0.8,
        evidenceBindingIds = setOf(binding.id),
        explanation = "codec-test",
    )
}
