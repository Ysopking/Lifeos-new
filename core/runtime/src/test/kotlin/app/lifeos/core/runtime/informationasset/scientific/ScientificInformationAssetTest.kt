package app.lifeos.core.runtime.informationasset.scientific

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

class ScientificInformationAssetTest {
    private val observedAt = Instant.parse("2026-09-15T16:00:00Z")

    @Test
    fun `factory creates scientific request with required keys`() {
        val request = ScientificInformationAssetFactory.request(
            namespace = "science-test",
            stableKey = "study-1",
            title = "Study review",
        )

        assertEquals(InformationAssetKind.SCIENTIFIC, request.kind)
        assertEquals(StandardInformationDomains.SCIENCE, request.primaryDomainId)
        assertEquals(ScientificSemanticKeys.CORE, request.requiredSemanticKeys)
    }

    @Test
    fun `primary source scientific result evidence passes profile`() {
        assertTrue(validator().validate(revision(SourceAuthority.PRIMARY_SOURCE), observedAt.plusSeconds(60)).isValid)
    }

    @Test
    fun `owner provided evidence alone cannot establish scientific result`() {
        val report = validator().validate(revision(SourceAuthority.USER_PROVIDED), observedAt.plusSeconds(60))

        assertTrue(report.violations.any {
            it.code == InformationAssetValidationCode.DOMAIN_PROFILE_REQUIREMENT &&
                it.path.endsWith(".authority")
        })
    }

    private fun validator() = InformationAssetValidator(
        profiles = mapOf(
            InformationAssetKind.SCIENTIFIC to listOf(ScientificInformationAssetValidationProfile())
        )
    )

    private fun revision(authority: SourceAuthority) = run {
        val source = Photon(
            id = PhotonId("scientific-source-${authority.name.lowercase()}"),
            revision = 1,
            content = "Scientific source",
            provenance = Provenance(source = "test", actor = "test", createdAt = observedAt),
        )
        val evidence = InformationEvidenceBinding.create(
            source = PhotonRevisionReference.from(source),
            fieldEvidenceId = null,
            domainId = StandardInformationDomains.SCIENCE,
            authority = authority,
            confidence = 0.9,
            reliability = EvidenceReliability(0.9, "scientific test evidence"),
            validity = TemporalValidity.UNBOUNDED,
            observedAt = observedAt,
            payloadFingerprint = StableFieldIds.fingerprint("scientific-payload", authority.name),
        )
        val claims = ScientificSemanticKeys.CORE.map { key ->
            InformationClaim.create(
                domainId = StandardInformationDomains.SCIENCE,
                semanticKey = key,
                statement = "Statement for $key",
                state = InformationClaimState.SUPPORTED,
                confidence = 0.9,
                evidenceBindingIds = setOf(evidence.id),
                explanation = "Supported by scientific test evidence",
            )
        }
        InformationAssetAssembler().assemble(
            InformationAssetAssemblyRequest(
                request = ScientificInformationAssetFactory.request(
                    namespace = "science-test",
                    stableKey = "case-${authority.name.lowercase()}",
                    title = "Scientific case",
                ),
                sourcePhotons = listOf(source),
                evidenceBindings = listOf(evidence),
                claims = claims,
                participatingModules = setOf("scientific-v1"),
            )
        ).revision
    }
}
