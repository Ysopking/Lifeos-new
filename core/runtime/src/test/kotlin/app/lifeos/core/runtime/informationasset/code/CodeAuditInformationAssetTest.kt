package app.lifeos.core.runtime.informationasset.code

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

class CodeAuditInformationAssetTest {
    private val observedAt = Instant.parse("2026-09-15T16:00:00Z")

    @Test
    fun `factory creates code audit request with required keys`() {
        val request = CodeAuditInformationAssetFactory.request(
            namespace = "code-audit-test",
            stableKey = "audit-1",
            title = "Audit",
        )

        assertEquals(InformationAssetKind.CODE_AUDIT, request.kind)
        assertEquals(StandardInformationDomains.CODE, request.primaryDomainId)
        assertEquals(CodeAuditSemanticKeys.CORE, request.requiredSemanticKeys)
    }

    @Test
    fun `owner supplied code evidence can support a finding`() {
        assertTrue(validator().validate(revision(SourceAuthority.USER_PROVIDED), observedAt.plusSeconds(60)).isValid)
    }

    @Test
    fun `unverified code evidence cannot support a finding`() {
        val report = validator().validate(revision(SourceAuthority.UNVERIFIED), observedAt.plusSeconds(60))

        assertTrue(report.violations.any {
            it.code == InformationAssetValidationCode.DOMAIN_PROFILE_REQUIREMENT &&
                it.path.endsWith(".authority")
        })
    }

    private fun validator() = InformationAssetValidator(
        profiles = mapOf(
            InformationAssetKind.CODE_AUDIT to listOf(CodeAuditInformationAssetValidationProfile())
        )
    )

    private fun revision(authority: SourceAuthority) = run {
        val source = Photon(
            id = PhotonId("code-audit-source-${authority.name.lowercase()}"),
            revision = 1,
            content = "fun example() = Unit",
            provenance = Provenance(source = "test", actor = "test", createdAt = observedAt),
        )
        val evidence = InformationEvidenceBinding.create(
            source = PhotonRevisionReference.from(source),
            fieldEvidenceId = null,
            domainId = StandardInformationDomains.CODE,
            authority = authority,
            confidence = 0.9,
            reliability = EvidenceReliability(0.9, "code audit test evidence"),
            validity = TemporalValidity.UNBOUNDED,
            observedAt = observedAt,
            payloadFingerprint = StableFieldIds.fingerprint("code-audit-payload", authority.name),
        )
        val claims = CodeAuditSemanticKeys.CORE.map { key ->
            InformationClaim.create(
                domainId = StandardInformationDomains.CODE,
                semanticKey = key,
                statement = "Statement for $key",
                state = InformationClaimState.SUPPORTED,
                confidence = 0.9,
                evidenceBindingIds = setOf(evidence.id),
                explanation = "Supported by code audit test evidence",
            )
        }
        InformationAssetAssembler().assemble(
            InformationAssetAssemblyRequest(
                request = CodeAuditInformationAssetFactory.request(
                    namespace = "code-audit-test",
                    stableKey = "case-${authority.name.lowercase()}",
                    title = "Code audit case",
                ),
                sourcePhotons = listOf(source),
                evidenceBindings = listOf(evidence),
                claims = claims,
                participatingModules = setOf("code-audit-v1"),
            )
        ).revision
    }
}
