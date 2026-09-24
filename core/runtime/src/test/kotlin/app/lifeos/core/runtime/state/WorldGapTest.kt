package app.lifeos.core.runtime.state

import app.lifeos.core.field.FieldDomainId
import app.lifeos.core.runtime.capability.CapabilityGap
import app.lifeos.core.runtime.capability.CapabilityGapType
import app.lifeos.core.runtime.capability.CapabilityId
import app.lifeos.core.runtime.capability.CapabilityRequirement
import app.lifeos.core.runtime.capability.GapSeverity
import app.lifeos.core.runtime.life.ObservationAuthorityClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WorldGapTest {
    private val domain = FieldDomainId("finance")
    private val contract = StateContract.create(
        id = "finance-current",
        domain = domain,
        dimensions = listOf(
            StateDimensionRequirement(
                dimension = StateDimensionId("finance.balance"),
                minimumAuthority = ObservationAuthorityClass.AUTHENTICATED_API,
            ),
            StateDimensionRequirement(
                dimension = StateDimensionId("finance.open-obligations"),
                minimumAuthority = ObservationAuthorityClass.PLATFORM_PROVIDER,
            ),
        ),
    )

    @Test
    fun missingAndConflictingStateBecomeDifferentGapTypes() {
        val result = StateSufficiencyResult(
            contractId = contract.id,
            contractFingerprint = contract.fingerprint,
            status = StateSufficiencyStatus.CONFLICTED,
            satisfied = emptySet(),
            missing = setOf(StateDimensionId("finance.open-obligations")),
            stale = emptySet(),
            conflicted = setOf(StateDimensionId("finance.balance")),
            supportingEvidenceIds = setOf("balance-a", "balance-b"),
        )

        val gaps = StateWorldGapDetector.detect(contract, result)

        assertEquals(2, gaps.size)
        assertTrue(gaps.any { it is WorldGap.Perception })
        assertTrue(gaps.any { it is WorldGap.Consistency })
    }

    @Test
    fun existingCapabilityGapIsAdaptedWithoutChangingItsRegistryModel() {
        val existing = CapabilityGap(
            requirement = CapabilityRequirement(
                capabilityId = CapabilityId("finance.balance.observe"),
                severity = GapSeverity.BLOCKING,
            ),
            type = CapabilityGapType.PROVIDER_UNHEALTHY,
            candidateProviderIds = listOf("bank-app"),
        )

        val gap = CapabilityWorldGapAdapter.adapt(domain, existing)

        assertIs<WorldGap.Capability>(gap)
        assertEquals("finance.balance.observe", gap.capabilityId)
        assertEquals(setOf("bank-app"), gap.providerCandidates)
        assertEquals("provider_unhealthy", gap.reason)
    }

    @Test
    fun worldGapIdentityIsDeterministicAcrossSetOrdering() {
        val first = WorldGap.Consistency(
            domain = domain,
            conflictingDimensions = linkedSetOf(
                StateDimensionId("b"),
                StateDimensionId("a"),
            ),
            evidenceIds = linkedSetOf("e2", "e1"),
            reason = "conflict",
        )
        val second = WorldGap.Consistency(
            domain = domain,
            conflictingDimensions = linkedSetOf(
                StateDimensionId("a"),
                StateDimensionId("b"),
            ),
            evidenceIds = linkedSetOf("e1", "e2"),
            reason = "conflict",
        )

        assertEquals(first.id, second.id)
    }
}
