package app.lifeos.core.runtime.reasoning

import app.lifeos.core.runtime.self.LifeOsSelfStateSnapshot
import app.lifeos.core.runtime.self.SelfHealthState
import app.lifeos.core.runtime.self.SelfLiveSourceState
import app.lifeos.core.runtime.self.SelfMemoryState
import app.lifeos.core.runtime.self.SelfPhotonState
import app.lifeos.core.runtime.self.SelfRecoveryState
import app.lifeos.core.runtime.self.SelfResourceState
import app.lifeos.core.runtime.self.SelfRuntimeState
import app.lifeos.core.runtime.self.SelfStateProjectionResult
import app.lifeos.core.runtime.self.SelfToolState
import app.lifeos.core.runtime.self.SelfWorldState
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MetaRealizationShadowRuntimeTest {
    @AfterTest
    fun clearRegistry() {
        MetaRealizationShadowRuntimeRegistry.clearForTestOnly()
    }

    @Test
    fun `self observation becomes a frozen non authoritative realization cycle`() = runTest {
        val runtime = MetaRealizationShadowRuntime()

        val status = runtime.observe(snapshot(T0, "world:a"))
        val ready = assertIs<MetaRealizationShadowStatus.Ready>(status)

        assertEquals(1L, ready.snapshot.realization.revision)
        assertEquals(
            MetaRealizationCycleState.STATE_FROZEN,
            ready.snapshot.cycle.state,
        )
        assertFalse(ready.snapshot.truthAuthority)
        assertFalse(ready.snapshot.executionAuthority)
        assertFalse(ready.snapshot.cycle.executionAuthority)
    }

    @Test
    fun `submit hands shadow work off without awaiting realization processing`() = runTest {
        val runtime = MetaRealizationShadowRuntime(
            processingScope = backgroundScope,
        )

        assertTrue(runtime.submit(snapshot(T0, "world:queued")))

        val ready = withTimeout(1_000) {
            runtime.status.first { it is MetaRealizationShadowStatus.Ready }
        } as MetaRealizationShadowStatus.Ready

        assertEquals("world:queued", ready.snapshot.realization.components
            .single { it.kind == RealizationComponentKind.PRODUCTIVE_WORLD }
            .provenanceFingerprints
            .first())
        assertEquals(0L, runtime.rejectedSubmissionCount())
        runtime.close()
    }

    @Test
    fun `successive observations form exact predecessor bound realization revisions`() = runTest {
        val runtime = MetaRealizationShadowRuntime()

        val first = assertIs<MetaRealizationShadowStatus.Ready>(
            runtime.observe(snapshot(T0, "world:a"))
        ).snapshot
        val second = assertIs<MetaRealizationShadowStatus.Ready>(
            runtime.observe(snapshot(T0.plusSeconds(1), "world:b"))
        ).snapshot

        assertEquals(2L, second.realization.revision)
        assertEquals(
            first.realization.revisionId,
            second.realization.predecessorRevisionId,
        )
        assertNotEquals(
            first.realization.representationFingerprint,
            second.realization.representationFingerprint,
        )
    }

    @Test
    fun `same semantic state at a later capture preserves equivalence identity`() = runTest {
        val runtime = MetaRealizationShadowRuntime()

        val first = assertIs<MetaRealizationShadowStatus.Ready>(
            runtime.observe(snapshot(T0, "world:a"))
        ).snapshot
        val second = assertIs<MetaRealizationShadowStatus.Ready>(
            runtime.observe(snapshot(T0.plusSeconds(1), "world:a"))
        ).snapshot

        assertEquals(first.realization.id, second.realization.id)
        assertNotEquals(first.realization.revisionId, second.realization.revisionId)
    }

    @Test
    fun `history is bounded without altering current revision chain`() = runTest {
        val runtime = MetaRealizationShadowRuntime(historyCapacity = 2)

        runtime.observe(snapshot(T0, "world:a"))
        runtime.observe(snapshot(T0.plusSeconds(1), "world:b"))
        val latest = assertIs<MetaRealizationShadowStatus.Ready>(
            runtime.observe(snapshot(T0.plusSeconds(2), "world:c"))
        ).snapshot

        val history = runtime.history()
        assertEquals(2, history.size)
        assertEquals(listOf(2L, 3L), history.map { it.realization.revision })
        assertEquals(3L, latest.realization.revision)
    }

    @Test
    fun `registry publishes exactly the installed shadow runtime`() {
        val runtime = MetaRealizationShadowRuntime()

        MetaRealizationShadowRuntimeRegistry.install(runtime)

        assertEquals(runtime, MetaRealizationShadowRuntimeRegistry.requireCurrent())
    }

    private fun snapshot(
        capturedAt: Instant,
        worldFingerprint: String,
    ): SelfStateProjectionResult =
        SelfStateProjectionResult(
            snapshot = LifeOsSelfStateSnapshot(
                capturedAt = capturedAt,
                photon = SelfPhotonState(
                    latestRevisionCount = 2,
                    livePhotonCount = 2,
                    tombstonedPhotonCount = 0,
                    indexFingerprint = "photon:index",
                    headFingerprint = "photon:head",
                ),
                memory = SelfMemoryState(
                    authoritativePhotonCount = 2,
                    graphNodeCount = 2,
                    graphEdgeCount = 1,
                    memoryFingerprint = "memory:a",
                ),
                world = SelfWorldState(
                    worldHeadRevision = 1,
                    worldHeadFingerprint = worldFingerprint,
                    worldEquationRevision = 1,
                    worldEquationVersion = "equation:v1",
                    worldEquationFingerprint = "equation:head",
                    bootCycleId = "cycle:1",
                    bootCycleFingerprint = "cycle:fingerprint",
                    cognitiveSnapshotFingerprint = "cognitive:a",
                ),
                runtime = SelfRuntimeState(
                    topologyFingerprint = "topology:a",
                    registeredSubsystems = setOf("runtime"),
                    operationalSubsystems = setOf("runtime"),
                    degradedSubsystems = emptySet(),
                    unavailableSubsystems = emptySet(),
                    unboundSubsystems = emptySet(),
                ),
                resource = SelfResourceState(
                    hardwareFingerprint = "hardware:a",
                    memoryHeadroom = 0.8,
                    storageHeadroom = 0.7,
                    thermalHeadroom = 0.9,
                    energyAvailability = 0.6,
                    capabilityReadiness = 1.0,
                ),
                health = SelfHealthState(
                    healthy = 1,
                    degraded = 0,
                    unhealthy = 0,
                    recovering = 0,
                    quarantined = 0,
                    disabled = 0,
                    unknown = 0,
                ),
                recovery = SelfRecoveryState(
                    activeRepairIds = emptySet(),
                    recoveryStateFingerprint = "recovery:a",
                ),
                tools = SelfToolState(
                    totalTools = 1,
                    activeTools = 1,
                    trialTools = 0,
                    quarantinedTools = 0,
                    rejectedTools = 0,
                ),
                liveSources = SelfLiveSourceState(
                    sourceCount = 1,
                    healthyCount = 1,
                    blockedCount = 0,
                    failedCount = 0,
                    sourceStateFingerprint = "sources:a",
                ),
            ),
            issues = emptyList(),
        )

    private companion object {
        val T0: Instant = Instant.parse("2026-09-25T15:00:00Z")
    }
}
