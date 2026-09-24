package app.lifeos.core.runtime.state

import app.lifeos.core.field.FieldDomainId
import app.lifeos.core.runtime.life.ObservationAuthorityClass
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StateSufficiencyDetectorTest {
    private val now = Instant.parse("2026-09-24T12:00:00Z")
    private val balance = StateDimensionId("finance.balance")
    private val obligations = StateDimensionId("finance.open-obligations")
    private val contract = StateContract.create(
        id = "monthly-spendability",
        domain = FieldDomainId("finance"),
        dimensions = listOf(
            StateDimensionRequirement(
                dimension = balance,
                minimumAuthority = ObservationAuthorityClass.AUTHENTICATED_API,
                maximumAge = Duration.ofMinutes(15),
            ),
            StateDimensionRequirement(
                dimension = obligations,
                minimumAuthority = ObservationAuthorityClass.PLATFORM_PROVIDER,
                maximumAge = Duration.ofHours(12),
            ),
        ),
    )

    @Test
    fun missingDimensionRemainsExplicitInsteadOfBeingInferred() {
        val result = StateSufficiencyDetector().evaluate(
            contract = contract,
            evidence = listOf(
                StateDimensionEvidence(
                    dimension = balance,
                    evidenceIds = setOf("bank-balance-1"),
                    strongestAuthority = ObservationAuthorityClass.AUTHENTICATED_API,
                    latestObservedAt = now,
                )
            ),
            at = now,
        )

        assertEquals(StateSufficiencyStatus.INSUFFICIENT, result.status)
        assertTrue(obligations in result.missing)
        assertTrue(balance in result.satisfied)
    }

    @Test
    fun staleAuthoritativeEvidenceDoesNotBecomeCurrentState() {
        val result = StateSufficiencyDetector().evaluate(
            contract = contract,
            evidence = listOf(
                StateDimensionEvidence(
                    dimension = balance,
                    evidenceIds = setOf("bank-balance-1"),
                    strongestAuthority = ObservationAuthorityClass.AUTHENTICATED_API,
                    latestObservedAt = now.minus(Duration.ofHours(1)),
                ),
                StateDimensionEvidence(
                    dimension = obligations,
                    evidenceIds = setOf("obligations-1"),
                    strongestAuthority = ObservationAuthorityClass.PLATFORM_PROVIDER,
                    latestObservedAt = now,
                ),
            ),
            at = now,
        )

        assertEquals(StateSufficiencyStatus.STALE, result.status)
        assertTrue(balance in result.stale)
    }

    @Test
    fun conflictingEvidenceWinsOverOtherwiseCompleteState() {
        val result = StateSufficiencyDetector().evaluate(
            contract = contract,
            evidence = listOf(
                StateDimensionEvidence(
                    dimension = balance,
                    evidenceIds = setOf("bank-balance-1", "bank-balance-2"),
                    strongestAuthority = ObservationAuthorityClass.AUTHENTICATED_API,
                    latestObservedAt = now,
                    conflictCount = 1,
                ),
                StateDimensionEvidence(
                    dimension = obligations,
                    evidenceIds = setOf("obligations-1"),
                    strongestAuthority = ObservationAuthorityClass.PLATFORM_PROVIDER,
                    latestObservedAt = now,
                ),
            ),
            at = now,
        )

        assertEquals(StateSufficiencyStatus.CONFLICTED, result.status)
        assertTrue(balance in result.conflicted)
    }
}
