package app.lifeos.core.runtime.informationasset.project

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

class ProjectInformationAssetTest {
    private val observedAt = Instant.parse("2026-09-15T16:00:00Z")

    @Test
    fun `factory creates project request with required keys`() {
        val request = ProjectInformationAssetFactory.request(
            namespace = "project-test",
            stableKey = "project-1",
            title = "Project state",
        )

        assertEquals(InformationAssetKind.PROJECT, request.kind)
        assertEquals(StandardInformationDomains.PROJECT, request.primaryDomainId)
        assertEquals(ProjectSemanticKeys.CORE, request.requiredSemanticKeys)
    }

    @Test
    fun `owner provided project state evidence passes profile`() {
        assertTrue(validator().validate(revision(SourceAuthority.USER_PROVIDED), observedAt.plusSeconds(60)).isValid)
    }

    @Test
    fun `unverified project operational evidence is rejected`() {
        val report = validator().validate(revision(SourceAuthority.UNVERIFIED), observedAt.plusSeconds(60))

        assertTrue(report.violations.any {
            it.code == InformationAssetValidationCode.DOMAIN_PROFILE_REQUIREMENT &&
                it.path.endsWith(".authority")
        })
    }

    private fun validator() = InformationAssetValidator(
        profiles = mapOf(
            InformationAssetKind.PROJECT to listOf(ProjectInformationAssetValidationProfile())
        )
    )

    private fun revision(authority: SourceAuthority) = run {
        val source = Photon(
            id = PhotonId("project-source-${authority.name.lowercase()}"),
            revision = 1,
            content = "Project source",
            provenance = Provenance(source = "test", actor = "test", createdAt = observedAt),
        )
        val evidence = InformationEvidenceBinding.create(
            source = PhotonRevisionReference.from(source),
            fieldEvidenceId = null,
            domainId = StandardInformationDomains.PROJECT,
            authority = authority,
            confidence = 0.9,
            reliability = EvidenceReliability(0.9, "project test evidence"),
            validity = TemporalValidity.UNBOUNDED,
            observedAt = observedAt,
            payloadFingerprint = StableFieldIds.fingerprint("project-payload", authority.name),
        )
        val claims = ProjectSemanticKeys.CORE.map { key ->
            InformationClaim.create(
                domainId = StandardInformationDomains.PROJECT,
                semanticKey = key,
                statement = "Statement for $key",
                state = InformationClaimState.SUPPORTED,
                confidence = 0.9,
                evidenceBindingIds = setOf(evidence.id),
                explanation = "Supported by project test evidence",
            )
        }
        InformationAssetAssembler().assemble(
            InformationAssetAssemblyRequest(
                request = ProjectInformationAssetFactory.request(
                    namespace = "project-test",
                    stableKey = "case-${authority.name.lowercase()}",
                    title = "Project case",
                ),
                sourcePhotons = listOf(source),
                evidenceBindings = listOf(evidence),
                claims = claims,
                participatingModules = setOf("project-v1"),
            )
        ).revision
    }
}
