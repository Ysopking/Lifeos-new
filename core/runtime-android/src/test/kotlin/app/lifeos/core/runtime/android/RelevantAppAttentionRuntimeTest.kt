package app.lifeos.core.runtime.android

import app.lifeos.core.field.FieldDomainId
import app.lifeos.core.runtime.world.SensorStateDimensionSelector
import app.lifeos.core.runtime.world.SensorStateDimensionSelectorType
import app.lifeos.core.runtime.world.StateDimensionId
import app.lifeos.core.runtime.world.WorldGap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class RelevantAppAttentionRuntimeTest {
    private val resolver = RelevantAppAttentionResolver()

    @Test
    fun matchingWorldGapFocusesOnlyExplicitlyCoveredPackage() {
        val finance = surface(
            packageName = "example.bank",
            prefix = "finance.account.",
        )
        val communication = surface(
            packageName = "example.chat",
            prefix = "communication.message.",
        )
        val gap = WorldGap.Perception(
            domain = FieldDomainId("finance"),
            missingDimensions = setOf(StateDimensionId("finance.account.balance")),
            reason = "balance-missing",
        )

        val plan = resolver.resolve(
            gaps = listOf(gap),
            surfaces = listOf(communication, finance),
        )

        assertEquals(listOf("example.bank"), plan.focusedPackages)
        assertEquals(listOf(gap.id), plan.matchedGapIdsByPackage.getValue("example.bank"))
        assertEquals(emptyList(), plan.unresolvedObservationGapIds)
        assertFalse(plan.observationGrantAuthority)
        assertFalse(plan.effectAuthority)
    }

    @Test
    fun capabilityGapNeverFocusesPackage() {
        val surface = surface("example.bank", "finance.account.")
        val gap = WorldGap.Capability(
            domain = FieldDomainId("finance"),
            capabilityId = "finance.account.read",
            providerCandidates = setOf("example.bank"),
            reason = "provider-required",
        )

        val plan = resolver.resolve(listOf(gap), listOf(surface))

        assertEquals(emptyList(), plan.focusedPackages)
        assertEquals(listOf(gap.id), plan.nonSensorGapIds)
    }

    @Test
    fun unmatchedObservationGapRemainsExplicit() {
        val surface = surface("example.chat", "communication.message.")
        val gap = WorldGap.Perception(
            domain = FieldDomainId("finance"),
            missingDimensions = setOf(StateDimensionId("finance.account.balance")),
            reason = "balance-missing",
        )

        val plan = resolver.resolve(listOf(gap), listOf(surface))

        assertEquals(emptyList(), plan.focusedPackages)
        assertEquals(listOf(gap.id), plan.unresolvedObservationGapIds)
    }

    private fun surface(
        packageName: String,
        prefix: String,
    ) = RelevantAppSurfaceProfile(
        packageName = packageName,
        surfaceKey = "semantic-ui",
        stateDimensions = listOf(
            SensorStateDimensionSelector(
                SensorStateDimensionSelectorType.PREFIX,
                prefix,
            )
        ),
        sourceFingerprint = "a".repeat(64),
    )
}
