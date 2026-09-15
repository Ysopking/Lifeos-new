package app.lifeos.core.runtime.informationasset.legal

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

class LegalInformationAssetTest {
    private val observedAt = Instant.parse("2026-09-15T12:00:00Z")

    @Test
    fun `factory creates legal request with required legal reasoning keys`() {
        val request = LegalInformationAssetFactory.request(
            namespace = "legal-test",
            stableKey = "case-1",
            title = "Case analysis",
        )

        assertEquals(InformationAssetKind.LEGAL, request.kind)
        assertEquals(StandardInformationDomains.LEGAL, request.primaryDomainId)
        assertEquals(LegalSemanticKeys.CORE, request.requiredSemanticKeys)
    }

    @Test
    fun `documented legal rule evidence passes legal profile`() {
        val revision = legalRevision(SourceAuthority.OFFICIAL)
        val validator = InformationAssetValidator(
            profiles = mapOf(
                InformationAssetKind.LEGAL to listOf(LegalInformationAssetValidationProfile())
            )
        )

        val report = validator.validate(revision, observedAt.plusSeconds(60))

        assertTrue(report.isValid)
        assertTrue(report.violations.none { it.code == InformationAssetValidationCode.DOMAIN_PROFILE_REQUIREMENT })
    }

    @Test
    fun `unverified legal rule evidence is rejected by legal profile`() {
        val revision = legalRevision(SourceAuthority.UNVERIFIED)
        val validator = InformationAssetValidator(
            profiles = mapOf(
                InformationAssetKind.LEGAL to listOf(LegalInformationAssetValidationProfile())
            )
        )

        val report = validator.validate(revision, observedAt.plusSeconds(60))

        assertTrue(report.violations.any {
            it.code == InformationAssetValidationCode.DOMAIN_PROFILE_REQUIREMENT &&
                it.path.endsWith(".authority")
        })
    }

    @Test
    fun `legal norm rejects an inverted effective interval`() {
        assertFailsWith<IllegalArgumentException> {
            LegalNormReference(
                identifier = "N-1",
                title = "Test norm",
                jurisdiction = LegalJurisdiction("DE", "Germany"),
                hierarchy = LegalNormHierarchy.STATUTE,
                effectiveFrom = Instant.parse("2026-09-15T12:00:00Z"),
                effectiveUntilExclusive = Instant.parse("2026-09-15T11:59:59Z"),
            )
        }
    }

    private fun legalRevision(authority: SourceAuthority) = run {
        val source = Photon(
            id = PhotonId("legal-source-${authority.name.lowercase()}"),
            revision = 1,
            content = "Legal source",
            provenance = Provenance(
                source = "test",
                actor = "test",
                createdAt = observedAt,
            ),
        )
        val evidence = InformationEvidenceBinding.create(
            source = PhotonRevisionReference.from(source),
            fieldEvidenceId = null,
            domainId = StandardInformationDomains.LEGAL,
            authority = authority,
            confidence = 0.9,
            reliability = EvidenceReliability(0.9, "legal test evidence"),
            validity = TemporalValidity.UNBOUNDED,
            observedAt = observedAt,
            payloadFingerprint = StableFieldIds.fingerprint("legal-payload", authority.name),
        )
        val claims = LegalSemanticKeys.CORE.map { key ->
            InformationClaim.create(
                domainId = StandardInformationDomains.LEGAL,
                semanticKey = key,
                statement = "Statement for $key",
                state = InformationClaimState.SUPPORTED,
                confidence = 0.9,
                evidenceBindingIds = setOf(evidence.id),
                explanation = "Supported by legal test evidence",
            )
        }
        val request = LegalInformationAssetFactory.request(
            namespace = "legal-test",
            stableKey = "case-${authority.name.lowercase()}",
            title = "Legal case",
        )

        InformationAssetAssembler().assemble(
            InformationAssetAssemblyRequest(
                request = request,
                sourcePhotons = listOf(source),
                evidenceBindings = listOf(evidence),
                claims = claims,
                participatingModules = setOf("legal-v1"),
            )
        ).revision
    }
}
