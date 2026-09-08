package app.lifeos.core.field

import app.lifeos.core.model.PhotonId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class FieldEvidenceTest {
    private val domain = StableFieldIds.domain("legal.interpretation")
    private val observedAt = Instant.parse("2026-09-08T10:00:00Z")

    @Test
    fun `temporal validity uses exclusive end`() {
        val validity = TemporalValidity(
            validFrom = Instant.parse("2026-01-01T00:00:00Z"),
            validUntilExclusive = Instant.parse("2027-01-01T00:00:00Z"),
        )

        assertTrue(validity.contains(Instant.parse("2026-06-01T00:00:00Z")))
        assertFalse(validity.contains(Instant.parse("2027-01-01T00:00:00Z")))
    }

    @Test
    fun `invalid temporal interval is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            TemporalValidity(
                validFrom = Instant.parse("2027-01-01T00:00:00Z"),
                validUntilExclusive = Instant.parse("2026-01-01T00:00:00Z"),
            )
        }
    }

    @Test
    fun `same source revision and payload produce same evidence id`() {
        val first = evidence(revision = 1)
        val second = evidence(revision = 1)
        assertEquals(first.id, second.id)
        assertEquals(first.sourceFingerprint, second.sourceFingerprint)
    }

    @Test
    fun `source revision produces distinct evidence identity`() {
        val first = evidence(revision = 1)
        val second = evidence(revision = 2)
        assertNotEquals(first.id, second.id)
        assertNotEquals(first.sourceFingerprint, second.sourceFingerprint)
    }

    @Test
    fun `stable evidence ordering uses time then source and revision`() {
        val later = evidence(
            photonId = "p2",
            revision = 1,
            at = observedAt.plusSeconds(10),
        )
        val earlierSecondRevision = evidence(
            photonId = "p1",
            revision = 2,
            at = observedAt,
        )
        val earlierFirstRevision = evidence(
            photonId = "p1",
            revision = 1,
            at = observedAt,
        )

        val ordered = listOf(later, earlierSecondRevision, earlierFirstRevision).stableEvidenceOrder()
        assertEquals(listOf(earlierFirstRevision, earlierSecondRevision, later), ordered)
    }

    private fun evidence(
        photonId: String = "source-photon",
        revision: Long,
        at: Instant = observedAt,
    ): FieldEvidence = FieldEvidence.create(
        domainId = domain,
        sourcePhotonId = PhotonId(photonId),
        sourceRevision = revision,
        kind = EvidenceKind.NORM_SOURCE,
        semanticKey = "norm-applicability",
        confidence = 0.91,
        reliability = EvidenceReliability(0.95, "signed local source"),
        authority = SourceAuthority.OFFICIAL,
        observedAt = at,
        validity = TemporalValidity(
            validFrom = Instant.parse("2026-01-01T00:00:00Z"),
            validUntilExclusive = Instant.parse("2027-01-01T00:00:00Z"),
        ),
        payload = EvidencePayload(
            type = "legal-norm",
            values = mapOf("citation" to "example-1", "version" to "2026"),
        ),
        explanation = "Local legal-source observation",
    )
}
