package app.lifeos.core.runtime.informationasset.financial

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
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FinancialInformationAssetTest {
    private val observedAt = Instant.parse("2026-09-15T16:00:00Z")

    @Test
    fun `factory creates financial request with required keys`() {
        val request = FinancialInformationAssetFactory.request("finance-test", "forecast-1", "Forecast")
        assertEquals(InformationAssetKind.FINANCIAL, request.kind)
        assertEquals(StandardInformationDomains.FINANCE, request.primaryDomainId)
        assertEquals(FinancialSemanticKeys.CORE, request.requiredSemanticKeys)
    }

    @Test
    fun `financial amount normalizes deterministic decimal representation`() {
        assertEquals("12.34", FinancialAmount("EUR", "12.3400").normalizedAmount())
        assertFailsWith<IllegalArgumentException> { FinancialAmount("eur", "12.34") }
    }

    @Test
    fun `owner provided financial evidence passes profile`() {
        assertTrue(validator().validate(revision(SourceAuthority.USER_PROVIDED), observedAt.plusSeconds(60)).isValid)
    }

    @Test
    fun `unverified financial value evidence is rejected`() {
        val report = validator().validate(revision(SourceAuthority.UNVERIFIED), observedAt.plusSeconds(60))
        assertTrue(report.violations.any {
            it.code == InformationAssetValidationCode.DOMAIN_PROFILE_REQUIREMENT && it.path.endsWith(".authority")
        })
    }

    private fun validator() = InformationAssetValidator(
        profiles = mapOf(InformationAssetKind.FINANCIAL to listOf(FinancialInformationAssetValidationProfile()))
    )

    private fun revision(authority: SourceAuthority) = run {
        val source = Photon(
            id = PhotonId("financial-source-${authority.name.lowercase()}"),
            revision = 1,
            content = "Financial source",
            provenance = Provenance(source = "test", actor = "test", createdAt = observedAt),
        )
        val evidence = InformationEvidenceBinding.create(
            source = PhotonRevisionReference.from(source),
            fieldEvidenceId = null,
            domainId = StandardInformationDomains.FINANCE,
            authority = authority,
            confidence = 0.9,
            reliability = EvidenceReliability(0.9, "financial test evidence"),
            validity = TemporalValidity.UNBOUNDED,
            observedAt = observedAt,
            payloadFingerprint = StableFieldIds.fingerprint("financial-payload", authority.name),
        )
        val claims = FinancialSemanticKeys.CORE.map { key ->
            InformationClaim.create(
                domainId = StandardInformationDomains.FINANCE,
                semanticKey = key,
                statement = "Statement for $key",
                state = InformationClaimState.SUPPORTED,
                confidence = 0.9,
                evidenceBindingIds = setOf(evidence.id),
                explanation = "Supported by financial test evidence",
            )
        }
        InformationAssetAssembler().assemble(
            InformationAssetAssemblyRequest(
                request = FinancialInformationAssetFactory.request(
                    "finance-test", "case-${authority.name.lowercase()}", "Financial case"
                ),
                sourcePhotons = listOf(source),
                evidenceBindings = listOf(evidence),
                claims = claims,
                participatingModules = setOf("financial-v1"),
            )
        ).revision
    }
}
