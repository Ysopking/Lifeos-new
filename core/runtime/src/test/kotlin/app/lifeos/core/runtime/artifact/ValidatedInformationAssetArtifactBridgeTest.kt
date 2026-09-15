package app.lifeos.core.runtime.artifact

import app.lifeos.core.field.EvidenceReliability
import app.lifeos.core.field.SourceAuthority
import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.field.TemporalValidity
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.Provenance
import app.lifeos.core.runtime.informationasset.InformationAssetAssembler
import app.lifeos.core.runtime.informationasset.InformationAssetAssemblyRequest
import app.lifeos.core.runtime.informationasset.InformationAssetKind
import app.lifeos.core.runtime.informationasset.InformationAssetPhotonFactory
import app.lifeos.core.runtime.informationasset.InformationAssetRequest
import app.lifeos.core.runtime.informationasset.InformationAssetRevisionVerifier
import app.lifeos.core.runtime.informationasset.InformationAssetSourceRevisionResolver
import app.lifeos.core.runtime.informationasset.InformationClaim
import app.lifeos.core.runtime.informationasset.InformationClaimState
import app.lifeos.core.runtime.informationasset.InformationEvidenceBinding
import app.lifeos.core.runtime.informationasset.PhotonRevisionReference
import app.lifeos.core.runtime.informationasset.StandardInformationDomains
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ValidatedInformationAssetArtifactBridgeTest {
    private val now = Instant.parse("2026-09-15T16:00:00Z")

    @Test
    fun `converged valid revision with no historical sources can cross guarded bridge`() = runBlocking {
        val revision = assumptionRevision()
        val photon = InformationAssetPhotonFactory().create(revision, now)
        val verifier = InformationAssetRevisionVerifier(
            InformationAssetSourceRevisionResolver { _, _ -> null }
        )

        val result = ValidatedInformationAssetArtifactBridge(verifier).create(
            InformationAssetArtifactInput(revision, photon),
            now,
        )

        assertTrue(result.validation.isValid)
        assertTrue(result.batch.contributions.isNotEmpty())
    }

    @Test
    fun `unresolved revision is blocked before artifact materialization`() = runBlocking {
        val revision = unresolvedRevision()
        val photon = InformationAssetPhotonFactory().create(revision, now)
        val verifier = InformationAssetRevisionVerifier(
            InformationAssetSourceRevisionResolver { _, _ -> error("verifier must not run") }
        )

        assertFailsWith<IllegalArgumentException> {
            ValidatedInformationAssetArtifactBridge(verifier).create(
                InformationAssetArtifactInput(revision, photon),
                now,
            )
        }
    }

    private fun assumptionRevision() = run {
        val request = request("converged")
        val claim = InformationClaim.create(
            domainId = StandardInformationDomains.GENERAL,
            semanticKey = "knowledge.answer",
            statement = "Explicit assumption",
            state = InformationClaimState.ASSUMPTION,
            confidence = 0.5,
            evidenceBindingIds = emptySet(),
            explanation = "Explicit assumption for guard test",
        )
        InformationAssetAssembler().assemble(
            InformationAssetAssemblyRequest(
                request = request,
                sourcePhotons = emptyList(),
                evidenceBindings = emptyList(),
                claims = listOf(claim),
                participatingModules = setOf("guard-test"),
            )
        ).revision
    }

    private fun unresolvedRevision() = run {
        val source = Photon(
            id = PhotonId("guard-unresolved-source"),
            revision = 1,
            content = "Unresolved evidence",
            provenance = Provenance(source = "test", actor = "test", createdAt = now),
        )
        val binding = InformationEvidenceBinding.create(
            source = PhotonRevisionReference.from(source),
            fieldEvidenceId = null,
            domainId = StandardInformationDomains.GENERAL,
            authority = SourceAuthority.USER_PROVIDED,
            confidence = 0.6,
            reliability = EvidenceReliability(0.6, "guard unresolved evidence"),
            validity = TemporalValidity.UNBOUNDED,
            observedAt = now,
            payloadFingerprint = StableFieldIds.fingerprint("guard-unresolved"),
        )
        val claim = InformationClaim.create(
            domainId = StandardInformationDomains.GENERAL,
            semanticKey = "knowledge.answer",
            statement = "Not yet resolved",
            state = InformationClaimState.UNRESOLVED,
            confidence = 0.6,
            evidenceBindingIds = setOf(binding.id),
            explanation = "Deliberately unresolved",
        )
        InformationAssetAssembler().assemble(
            InformationAssetAssemblyRequest(
                request = request("unresolved"),
                sourcePhotons = listOf(source),
                evidenceBindings = listOf(binding),
                claims = listOf(claim),
                participatingModules = setOf("guard-test"),
            )
        ).revision
    }

    private fun request(stableKey: String) = InformationAssetRequest.create(
        namespace = "guard-test",
        stableKey = stableKey,
        kind = InformationAssetKind.KNOWLEDGE,
        title = "Guard test",
        primaryDomainId = StandardInformationDomains.GENERAL,
        requiredSemanticKeys = setOf("knowledge.answer"),
    )
}
