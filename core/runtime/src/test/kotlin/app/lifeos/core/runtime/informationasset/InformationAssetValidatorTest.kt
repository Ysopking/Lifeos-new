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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InformationAssetValidatorTest {
    private val domain = StableFieldIds.domain("information-asset-validator-test")
    private val observedAt = Instant.parse("2026-09-15T10:00:00Z")
    private val evaluatedAt = Instant.parse("2026-09-15T11:00:00Z")

    @Test
    fun `valid assembled asset passes common semantic validation`() {
        val revision = revision(validity = TemporalValidity.UNBOUNDED)

        val report = InformationAssetValidator().validate(revision, evaluatedAt)

        assertTrue(report.isValid)
        assertFalse(report.hasBlockingViolation)
        assertEquals(emptyList(), report.violations)
    }

    @Test
    fun `temporally stale evidence is retained as warning instead of rewritten`() {
        val revision = revision(
            validity = TemporalValidity(
                validFrom = Instant.parse("2026-09-15T09:00:00Z"),
                validUntilExclusive = Instant.parse("2026-09-15T10:30:00Z"),
            )
        )

        val report = InformationAssetValidator().validate(revision, evaluatedAt)

        assertTrue(report.isValid)
        assertEquals(1, report.violations.size)
        assertEquals(InformationAssetValidationCode.EXPIRED_EVIDENCE, report.violations.single().code)
        assertEquals(InformationAssetValidationSeverity.WARNING, report.violations.single().severity)
    }

    @Test
    fun `profile failure fails closed without corrupting the revision`() {
        val revision = revision(validity = TemporalValidity.UNBOUNDED)
        val profile = object : InformationAssetValidationProfile {
            override val id: String = "broken-profile"

            override fun validate(
                revision: InformationAssetRevision,
                evaluatedAt: Instant,
            ): List<InformationAssetValidationViolation> = error("profile unavailable")
        }
        val validator = InformationAssetValidator(
            profiles = mapOf(InformationAssetKind.KNOWLEDGE to listOf(profile))
        )

        val report = validator.validate(revision, evaluatedAt)

        assertFalse(report.isValid)
        assertTrue(report.hasBlockingViolation)
        assertEquals(InformationAssetValidationCode.PROFILE_FAILURE, report.violations.single().code)
        assertEquals("profile unavailable", report.violations.single().message)
    }

    private fun revision(validity: TemporalValidity): InformationAssetRevision {
        val source = Photon(
            id = PhotonId("validator-source"),
            revision = 1,
            content = "validator evidence",
            provenance = Provenance(
                source = "test",
                actor = "test",
                createdAt = observedAt,
            ),
        )
        val evidence = InformationEvidenceBinding.create(
            source = PhotonRevisionReference.from(source),
            fieldEvidenceId = null,
            domainId = domain,
            authority = SourceAuthority.DOCUMENTED,
            confidence = 0.8,
            reliability = EvidenceReliability(0.9, "test evidence"),
            validity = validity,
            observedAt = observedAt,
            payloadFingerprint = StableFieldIds.fingerprint("validator-payload"),
        )
        val claim = InformationClaim.create(
            domainId = domain,
            semanticKey = "fact",
            statement = "Fact is supported",
            state = InformationClaimState.SUPPORTED,
            confidence = 0.8,
            evidenceBindingIds = setOf(evidence.id),
            explanation = "Supported by test evidence",
        )
        val request = InformationAssetRequest.create(
            namespace = "test",
            stableKey = "validator-asset",
            kind = InformationAssetKind.KNOWLEDGE,
            title = "Validator asset",
            primaryDomainId = domain,
            requiredSemanticKeys = setOf("fact"),
        )
        return InformationAssetAssembler().assemble(
            InformationAssetAssemblyRequest(
                request = request,
                sourcePhotons = listOf(source),
                evidenceBindings = listOf(evidence),
                claims = listOf(claim),
                participatingModules = setOf("validator-test"),
            )
        ).revision
    }
}
