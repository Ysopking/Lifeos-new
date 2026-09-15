package app.lifeos.core.runtime.informationasset.organization

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
import app.lifeos.core.runtime.informationasset.InformationAssetValidationCode
import app.lifeos.core.runtime.informationasset.InformationAssetValidator
import app.lifeos.core.runtime.informationasset.InformationClaim
import app.lifeos.core.runtime.informationasset.InformationClaimState
import app.lifeos.core.runtime.informationasset.InformationEvidenceBinding
import app.lifeos.core.runtime.informationasset.PhotonRevisionReference
import app.lifeos.core.runtime.informationasset.StandardInformationDomains
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OrganizationInformationAssetTest {
    private val observedAt = Instant.parse("2026-09-15T16:00:00Z")

    @Test
    fun `factory creates organization request with required keys`() {
        val request = OrganizationInformationAssetFactory.request(
            namespace = "organization-test",
            stableKey = "decision-1",
            title = "Decision record",
        )

        assertEquals(InformationAssetKind.ORGANIZATION, request.kind)
        assertEquals(StandardInformationDomains.ORGANIZATION, request.primaryDomainId)
        assertEquals(OrganizationSemanticKeys.CORE, request.requiredSemanticKeys)
    }

    @Test
    fun `owner provided organization decision evidence passes profile`() {
        val report = validator().validate(revision(SourceAuthority.USER_PROVIDED), observedAt.plusSeconds(60))

        assertTrue(report.isValid)
    }

    @Test
    fun `unverified organization decision evidence is rejected`() {
        val report = validator().validate(revision(SourceAuthority.UNVERIFIED), observedAt.plusSeconds(60))

        assertTrue(report.violations.any {
            it.code == InformationAssetValidationCode.DOMAIN_PROFILE_REQUIREMENT &&
                it.path.endsWith(".authority")
        })
    }

    private fun validator() = InformationAssetValidator(
        profiles = mapOf(
            InformationAssetKind.ORGANIZATION to listOf(OrganizationInformationAssetValidationProfile())
        )
    )

    private fun revision(authority: SourceAuthority) = run {
        val source = Photon(
            id = PhotonId("organization-source-${authority.name.lowercase()}"),
            revision = 1,
            content = "Organization source",
            provenance = Provenance(
                source = "test",
                actor = "test",
                createdAt = observedAt,
            ),
        )
        val evidence = InformationEvidenceBinding.create(
            source = PhotonRevisionReference.from(source),
            fieldEvidenceId = null,
            domainId = StandardInformationDomains.ORGANIZATION,
            authority = authority,
            confidence = 0.9,
            reliability = EvidenceReliability(0.9, "organization test evidence"),
            validity = TemporalValidity.UNBOUNDED,
            observedAt = observedAt,
            payloadFingerprint = StableFieldIds.fingerprint("organization-payload", authority.name),
        )
        val claims = OrganizationSemanticKeys.CORE.map { key ->
            InformationClaim.create(
                domainId = StandardInformationDomains.ORGANIZATION,
                semanticKey = key,
                statement = "Statement for $key",
                state = InformationClaimState.SUPPORTED,
                confidence = 0.9,
                evidenceBindingIds = setOf(evidence.id),
                explanation = "Supported by organization test evidence",
            )
        }
        InformationAssetAssembler().assemble(
            InformationAssetAssemblyRequest(
                request = OrganizationInformationAssetFactory.request(
                    namespace = "organization-test",
                    stableKey = "case-${authority.name.lowercase()}",
                    title = "Organization case",
                ),
                sourcePhotons = listOf(source),
                evidenceBindings = listOf(evidence),
                claims = claims,
                participatingModules = setOf("organization-v1"),
            )
        ).revision
    }
}
