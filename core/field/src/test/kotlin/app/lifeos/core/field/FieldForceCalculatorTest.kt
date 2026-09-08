package app.lifeos.core.field

import app.lifeos.core.model.PhotonId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FieldForceCalculatorTest {
    private val domain = StableFieldIds.domain("finance.debt")
    private val queryTime = Instant.parse("2026-09-08T12:00:00Z")
    private val context = FieldContext(
        temporal = TemporalContext(queryTime),
        domain = DomainContext(domain),
    )

    @Test
    fun `higher source authority yields stronger evidence force`() {
        val calculator = FieldForceCalculator()
        val low = calculator.evidenceForce(evidence(SourceAuthority.UNVERIFIED), context)
        val high = calculator.evidenceForce(evidence(SourceAuthority.OFFICIAL), context)
        assertTrue(high.composite > low.composite)
    }

    @Test
    fun `evidence outside applicable period has zero temporal component`() {
        val expired = evidence(
            authority = SourceAuthority.OFFICIAL,
            validity = TemporalValidity(
                validFrom = Instant.parse("2020-01-01T00:00:00Z"),
                validUntilExclusive = Instant.parse("2021-01-01T00:00:00Z"),
            ),
        )
        val breakdown = FieldForceCalculator().evidenceForce(expired, context)
        assertEquals(0.0, breakdown.temporalValidity)
    }

    @Test
    fun `relation types map to deterministic force polarity`() {
        val source = FieldNode.create(domain, FieldNodeKind.STATE, "source")
        val target = FieldNode.create(domain, FieldNodeKind.STATE, "target")
        val calculator = FieldForceCalculator()
        val support = calculator.relationForce(
            FieldRelation.create(domain, source.id, target.id, FieldRelationType.SUPPORTS, 0.5, "support"),
            sourceEnergy = 0.8,
        )
        val contradiction = calculator.relationForce(
            FieldRelation.create(domain, source.id, target.id, FieldRelationType.CONTRADICTS, 0.5, "conflict"),
            sourceEnergy = 0.8,
        )

        assertEquals(ForcePolarity.ATTRACTION, support.polarity)
        assertEquals(0.4, support.magnitude)
        assertEquals(ForcePolarity.REPULSION, contradiction.polarity)
        assertEquals(-0.4, contradiction.signedMagnitude)
    }

    private fun evidence(
        authority: SourceAuthority,
        validity: TemporalValidity = TemporalValidity.UNBOUNDED,
    ): FieldEvidence = FieldEvidence.create(
        domainId = domain,
        sourcePhotonId = PhotonId("source-${authority.name}"),
        sourceRevision = 1,
        kind = EvidenceKind.DOCUMENT_FACT,
        semanticKey = "debt.principal",
        confidence = 0.8,
        reliability = EvidenceReliability(0.8, "test source"),
        authority = authority,
        observedAt = queryTime.minusSeconds(60),
        validity = validity,
        payload = EvidencePayload("money", mapOf("amount" to "100.00", "currency" to "EUR")),
        explanation = "test evidence",
    )
}
