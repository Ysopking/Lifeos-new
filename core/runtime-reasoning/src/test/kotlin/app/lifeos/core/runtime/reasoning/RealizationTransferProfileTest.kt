package app.lifeos.core.runtime.reasoning

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class RealizationTransferProfileTest {
    private val t0 = Instant.parse("2026-09-25T12:30:00Z")

    @Test
    fun `profile canonicalizes all unordered contract collections`() {
        val first = profile(
            components = listOf(
                RealizationComponentKind.PRODUCTIVE_WORLD,
                RealizationComponentKind.PERSONAL_CONTEXT,
            ),
            observables = listOf("owner.agency", "world.head"),
            auxiliaries = listOf("memory.activation", "episode.context"),
            invariants = listOf("truth-not-permission", "observation-not-world"),
            failures = listOf("projection-not-closed", "insufficient-evidence"),
        )
        val second = profile(
            components = first.requiredComponents.reversed(),
            observables = first.observableIds.reversed(),
            auxiliaries = first.allowedAuxiliaryVariableIds.reversed(),
            invariants = first.invariantIds.reversed(),
            failures = first.failureCriterionIds.reversed(),
        )

        assertEquals(first, second)
        assertEquals(
            listOf(
                RealizationComponentKind.PERSONAL_CONTEXT,
                RealizationComponentKind.PRODUCTIVE_WORLD,
            ),
            first.requiredComponents,
        )
    }

    @Test
    fun `frozen contract identity changes when observables change`() {
        val first = profile(observables = listOf("world.head"))
        val second = profile(observables = listOf("world.head", "owner.agency"))

        assertNotEquals(first.profileId, second.profileId)
        assertNotEquals(first.fingerprint, second.fingerprint)
    }

    @Test
    fun `frozen contract identity changes when failure criteria change`() {
        val first = profile(failures = listOf("projection-not-closed"))
        val second = profile(
            failures = listOf("projection-not-closed", "state-underidentified")
        )

        assertNotEquals(first.fingerprint, second.fingerprint)
    }

    @Test
    fun `frozen instant is part of exact profile identity`() {
        val first = profile()
        val second = RealizationTransferProfile.create(
            version = first.version,
            requiredComponents = first.requiredComponents,
            projectionRegistryFingerprint = first.projectionRegistryFingerprint,
            stateContractFingerprint = first.stateContractFingerprint,
            observableIds = first.observableIds,
            allowedAuxiliaryVariableIds = first.allowedAuxiliaryVariableIds,
            invariantIds = first.invariantIds,
            failureCriterionIds = first.failureCriterionIds,
            frozenAt = t0.plusSeconds(1),
        )

        assertNotEquals(first.profileId, second.profileId)
    }

    @Test
    fun `duplicate inputs are collapsed before the profile is frozen`() {
        val profile = RealizationTransferProfile.create(
            version = "v2.2",
            requiredComponents = listOf(
                RealizationComponentKind.PERSONAL_CONTEXT,
                RealizationComponentKind.PERSONAL_CONTEXT,
            ),
            projectionRegistryFingerprint = "projection-registry",
            stateContractFingerprint = "state-contract",
            observableIds = listOf("world.head", "world.head"),
            invariantIds = listOf("truth-not-permission", "truth-not-permission"),
            failureCriterionIds = listOf("insufficient-evidence", "insufficient-evidence"),
            frozenAt = t0,
        )

        assertEquals(1, profile.requiredComponents.size)
        assertEquals(1, profile.observableIds.size)
        assertEquals(1, profile.invariantIds.size)
        assertEquals(1, profile.failureCriterionIds.size)
    }

    @Test
    fun `profile rejects missing observables`() {
        assertFailsWith<IllegalArgumentException> {
            RealizationTransferProfile.create(
                version = "v2.2",
                requiredComponents = listOf(RealizationComponentKind.PERSONAL_CONTEXT),
                projectionRegistryFingerprint = "projection-registry",
                stateContractFingerprint = "state-contract",
                observableIds = emptyList(),
                invariantIds = listOf("truth-not-permission"),
                failureCriterionIds = listOf("insufficient-evidence"),
                frozenAt = t0,
            )
        }
    }

    @Test
    fun `profile rejects missing invariants`() {
        assertFailsWith<IllegalArgumentException> {
            RealizationTransferProfile.create(
                version = "v2.2",
                requiredComponents = listOf(RealizationComponentKind.PERSONAL_CONTEXT),
                projectionRegistryFingerprint = "projection-registry",
                stateContractFingerprint = "state-contract",
                observableIds = listOf("world.head"),
                invariantIds = emptyList(),
                failureCriterionIds = listOf("insufficient-evidence"),
                frozenAt = t0,
            )
        }
    }

    @Test
    fun `profile is descriptive and grants no authority`() {
        val profile = profile()

        assertFalse(profile.truthAuthority)
        assertFalse(profile.policyAuthority)
        assertFalse(profile.executionAuthority)
    }

    private fun profile(
        components: List<RealizationComponentKind> =
            listOf(RealizationComponentKind.PERSONAL_CONTEXT),
        observables: List<String> = listOf("world.head"),
        auxiliaries: List<String> = emptyList(),
        invariants: List<String> = listOf("truth-not-permission"),
        failures: List<String> = listOf("insufficient-evidence"),
    ): RealizationTransferProfile =
        RealizationTransferProfile.create(
            version = "v2.2",
            requiredComponents = components,
            projectionRegistryFingerprint = "projection-registry",
            stateContractFingerprint = "state-contract",
            observableIds = observables,
            allowedAuxiliaryVariableIds = auxiliaries,
            invariantIds = invariants,
            failureCriterionIds = failures,
            frozenAt = t0,
        )
}
