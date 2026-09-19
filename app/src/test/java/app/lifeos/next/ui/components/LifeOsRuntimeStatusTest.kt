package app.lifeos.next.ui.components

import app.lifeos.core.runtime.life.LifeOsBlock
import app.lifeos.core.runtime.life.LifeOsCompletionReadiness
import app.lifeos.core.runtime.life.LifeOsReadinessSnapshot
import app.lifeos.core.runtime.life.ReadinessState
import app.lifeos.next.kernel.KernelBootstrapStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LifeOsRuntimeStatusTest {
    @Test
    fun failedBootOverridesHealthyEvidence() {
        assertEquals(
            RuntimeHealthLevel.FAILED,
            buildRuntimeHealthUiModel(
                KernelBootstrapStatus.FAILED,
                healthyReadiness(),
                healthyTopology(),
            ).level,
        )
    }

    @Test
    fun loadingBootIsStartingEvenWithStaleHealthyEvidence() {
        assertEquals(
            RuntimeHealthLevel.STARTING,
            buildRuntimeHealthUiModel(
                KernelBootstrapStatus.LOADING,
                healthyReadiness(),
                healthyTopology(),
            ).level,
        )
    }

    @Test
    fun createdBootIsStarting() {
        assertEquals(
            RuntimeHealthLevel.STARTING,
            buildRuntimeHealthUiModel(
                KernelBootstrapStatus.CREATED,
                null,
                null,
            ).level,
        )
    }

    @Test
    fun readyBootWithoutReadinessEvidenceIsVerifying() {
        assertEquals(
            RuntimeHealthLevel.VERIFYING,
            buildRuntimeHealthUiModel(
                KernelBootstrapStatus.READY,
                null,
                healthyTopology(),
            ).level,
        )
    }

    @Test
    fun readyBootWithoutTopologyEvidenceIsVerifying() {
        assertEquals(
            RuntimeHealthLevel.VERIFYING,
            buildRuntimeHealthUiModel(
                KernelBootstrapStatus.READY,
                healthyReadiness(),
                null,
            ).level,
        )
    }

    @Test
    fun unobservedTopologyIsNotEquivalentToHealthyZeroCounts() {
        assertEquals(
            RuntimeHealthLevel.VERIFYING,
            buildRuntimeHealthUiModel(
                KernelBootstrapStatus.READY,
                healthyReadiness(),
                healthyTopology().copy(observed = false),
            ).level,
        )
    }

    @Test
    fun blockedReadinessCannotMapReady() {
        val model = buildRuntimeHealthUiModel(
            KernelBootstrapStatus.READY,
            readinessWith(LifeOsBlock.A, ReadinessState.BLOCKED),
            healthyTopology(),
        )
        assertEquals(RuntimeHealthLevel.DEGRADED, model.level)
        assertFalse(model.level == RuntimeHealthLevel.READY)
    }

    @Test
    fun degradedReadinessCannotMapReady() {
        val model = buildRuntimeHealthUiModel(
            KernelBootstrapStatus.READY,
            readinessWith(LifeOsBlock.B, ReadinessState.DEGRADED),
            healthyTopology(),
        )
        assertEquals(RuntimeHealthLevel.DEGRADED, model.level)
    }

    @Test
    fun unavailableTopologyCannotMapReady() {
        val model = buildRuntimeHealthUiModel(
            KernelBootstrapStatus.READY,
            healthyReadiness(),
            healthyTopology().copy(
                operationalSubsystems = 15,
                unavailableSubsystems = 1,
                fullyConnected = false,
                fullyOperational = false,
            ),
        )
        assertEquals(RuntimeHealthLevel.DEGRADED, model.level)
    }

    @Test
    fun unboundTopologyCannotMapReady() {
        val model = buildRuntimeHealthUiModel(
            KernelBootstrapStatus.READY,
            healthyReadiness(),
            healthyTopology().copy(
                operationalSubsystems = 15,
                unboundSubsystems = 1,
                fullyConnected = false,
                fullyOperational = false,
            ),
        )
        assertEquals(RuntimeHealthLevel.DEGRADED, model.level)
    }

    @Test
    fun degradedTopologyCannotMapReady() {
        val model = buildRuntimeHealthUiModel(
            KernelBootstrapStatus.READY,
            healthyReadiness(),
            healthyTopology().copy(
                degradedSubsystems = 1,
                fullyOperational = false,
            ),
        )
        assertEquals(RuntimeHealthLevel.DEGRADED, model.level)
    }

    @Test
    fun degradedBootCannotMapReadyWhenEvidenceIsComplete() {
        val model = buildRuntimeHealthUiModel(
            KernelBootstrapStatus.DEGRADED,
            healthyReadiness(),
            healthyTopology(),
        )
        assertEquals(RuntimeHealthLevel.DEGRADED, model.level)
    }

    @Test
    fun fullyHealthyEvidenceMapsReady() {
        val model = buildRuntimeHealthUiModel(
            KernelBootstrapStatus.READY,
            healthyReadiness(),
            healthyTopology(),
        )
        assertEquals(RuntimeHealthLevel.READY, model.level)
        assertEquals("Runtime bereit", model.compactLabel)
    }

    @Test
    fun mapperIsDeterministicForSameEvidence() {
        val first = buildRuntimeHealthUiModel(
            KernelBootstrapStatus.READY,
            healthyReadiness(),
            healthyTopology(),
        )
        val second = buildRuntimeHealthUiModel(
            KernelBootstrapStatus.READY,
            healthyReadiness(),
            healthyTopology(),
        )
        assertEquals(first, second)
    }

    @Test
    fun verifiedSelfStateIsProjectedWithoutChangingReadySemantics() {
        val selfState = healthySelfState()
        val model = buildRuntimeHealthUiModel(
            KernelBootstrapStatus.READY,
            healthyReadiness(),
            healthyTopology(),
            selfState,
        )

        assertEquals(RuntimeHealthLevel.READY, model.level)
        assertTrue(model.selfStateObserved)
        assertTrue(model.selfStateSummary.contains("STABLE"))
        assertTrue(model.selfStateSummary.contains("World r7"))
    }

    @Test
    fun missingSelfStateRemainsExplicitlyUnobserved() {
        val model = buildRuntimeHealthUiModel(
            KernelBootstrapStatus.READY,
            healthyReadiness(),
            healthyTopology(),
        )

        assertFalse(model.selfStateObserved)
        assertEquals("Self-State-Evidenz ausstehend", model.selfStateSummary)
    }

    @Test
    fun userVisibleProjectionNeverClaimsCiReleaseOrGold() {
        val model = buildRuntimeHealthUiModel(
            KernelBootstrapStatus.READY,
            healthyReadiness(),
            healthyTopology(),
        )
        val text = listOf(
            model.compactLabel,
            model.summary,
            model.bootLabel,
            model.readinessSummary,
            model.topologySummary,
            model.selfStateSummary,
        ).joinToString(" ").lowercase()

        assertTrue("gold" !in text)
        assertTrue("release" !in text)
        assertTrue("ci" !in text)
    }

    private fun healthyReadiness(): LifeOsReadinessSnapshot =
        LifeOsCompletionReadiness().snapshot(
            LifeOsBlock.entries.associateWith { ReadinessState.READY },
            LifeOsBlock.entries.associateWith { "verified" },
        )

    private fun readinessWith(
        block: LifeOsBlock,
        state: ReadinessState,
    ): LifeOsReadinessSnapshot =
        LifeOsCompletionReadiness().snapshot(
            LifeOsBlock.entries.associateWith {
                if (it == block) state else ReadinessState.READY
            },
            LifeOsBlock.entries.associateWith { "verified" },
        )

    private fun healthySelfState(): SelfStateUiEvidence = SelfStateUiEvidence(
        authorityFingerprintShort = "0123456789abcdef",
        worldRevision = 7,
        equationVersion = "eq-v7",
        photonCount = 42,
        memoryNodeCount = 12,
        healthyNodes = 8,
        degradedNodes = 0,
        unknownHealthNodes = 0,
        resourceCapabilityReadiness = 0.9,
        activeRepairs = 0,
        activeTools = 2,
        liveSources = 4,
        observationBand = "STABLE",
    )

    private fun healthyTopology(): RuntimeTopologyUiEvidence = RuntimeTopologyUiEvidence(
        observed = true,
        registeredSubsystems = 16,
        operationalSubsystems = 16,
        unavailableSubsystems = 0,
        unboundSubsystems = 0,
        degradedSubsystems = 0,
        capabilityProviders = 8,
        generatedProviders = 0,
        fullyConnected = true,
        fullyOperational = true,
    )
}
