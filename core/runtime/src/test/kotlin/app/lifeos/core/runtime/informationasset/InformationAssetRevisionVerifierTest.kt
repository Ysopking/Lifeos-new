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
import kotlinx.coroutines.test.runTest

class InformationAssetRevisionVerifierTest {
    private val domain = StableFieldIds.domain("information-asset-verifier-test")
    private val observedAt = Instant.parse("2026-09-15T12:30:00Z")

    @Test
    fun `exact source revision verifies`() = runTest {
        val source = sourcePhoton(revision = 3L, content = "exact")
        val revision = revision(source)
        val verifier = InformationAssetRevisionVerifier { id, rev ->
            source.takeIf { it.id == id && it.revision == rev }
        }

        val result = verifier.verify(revision)

        assertEquals(InformationAssetRevisionVerificationStatus.VERIFIED, result.status)
        assertEquals(
            InformationAssetSourceVerificationStatus.VERIFIED,
            result.sources.single().status,
        )
    }

    @Test
    fun `missing historical source is unavailable and never replaced by newer revision`() = runTest {
        val historical = sourcePhoton(revision = 3L, content = "historical")
        val newer = historical.copy(revision = 4L, content = "newer")
        val revision = revision(historical)
        val verifier = InformationAssetRevisionVerifier { id, requestedRevision ->
            if (id == newer.id && requestedRevision == newer.revision) newer else null
        }

        val result = verifier.verify(revision)

        assertEquals(
            InformationAssetRevisionVerificationStatus.SOURCE_REVISION_UNAVAILABLE,
            result.status,
        )
        assertEquals(
            InformationAssetSourceVerificationStatus.SOURCE_REVISION_UNAVAILABLE,
            result.sources.single().status,
        )
    }

    @Test
    fun `same Photon identity and revision with changed content reports state mismatch`() = runTest {
        val source = sourcePhoton(revision = 2L, content = "original")
        val revision = revision(source)
        val changed = source.copy(content = "changed")
        val verifier = InformationAssetRevisionVerifier { _, _ -> changed }

        val result = verifier.verify(revision)

        assertEquals(InformationAssetRevisionVerificationStatus.SOURCE_STATE_MISMATCH, result.status)
        assertEquals(
            InformationAssetSourceVerificationStatus.INPUT_STATE_HASH_MISMATCH,
            result.sources.single().status,
        )
    }

    @Test
    fun `resolver returning a different revision is rejected`() = runTest {
        val source = sourcePhoton(revision = 2L, content = "original")
        val revision = revision(source)
        val wrongRevision = source.copy(revision = 3L)
        val verifier = InformationAssetRevisionVerifier { _, _ -> wrongRevision }

        assertFailsWith<IllegalArgumentException> {
            verifier.verify(revision)
        }
    }

    private fun revision(source: Photon): InformationAssetRevision {
        val binding = InformationEvidenceBinding.create(
            source = PhotonRevisionReference.from(source),
            fieldEvidenceId = null,
            domainId = domain,
            authority = SourceAuthority.DOCUMENTED,
            confidence = 0.9,
            reliability = EvidenceReliability(0.9, "verifier-test"),
            validity = TemporalValidity.UNBOUNDED,
            observedAt = observedAt,
            payloadFingerprint = StableFieldIds.fingerprint("verifier-payload"),
        )
        val claim = InformationClaim.create(
            domainId = domain,
            semanticKey = "fact",
            statement = "Verified fact",
            state = InformationClaimState.SUPPORTED,
            confidence = 0.9,
            evidenceBindingIds = setOf(binding.id),
            explanation = "verifier-test",
        )
        return InformationAssetAssembler().assemble(
            InformationAssetAssemblyRequest(
                request = InformationAssetRequest.create(
                    namespace = "verifier-test",
                    stableKey = "asset",
                    kind = InformationAssetKind.KNOWLEDGE,
                    title = "Verifier asset",
                    primaryDomainId = domain,
                    requiredSemanticKeys = setOf("fact"),
                ),
                sourcePhotons = listOf(source),
                evidenceBindings = listOf(binding),
                claims = listOf(claim),
                participatingModules = setOf("verifier-module"),
            ),
        ).revision
    }

    private fun sourcePhoton(revision: Long, content: String): Photon = Photon(
        id = PhotonId("verifier-source"),
        revision = revision,
        content = content,
        provenance = Provenance(
            source = "verifier-test",
            actor = "verifier-test",
            createdAt = observedAt,
        ),
    )
}
