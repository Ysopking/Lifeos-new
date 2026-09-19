package app.lifeos.core.runtime.self

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class SelfStateModelsTest {
    @Test
    fun sameAuthoritativeHeadsProduceSameAuthorityFingerprint() {
        val first = snapshot()
        val second = snapshot(capturedAt = first.capturedAt.plusSeconds(30))

        assertEquals(first.authorityFingerprint, second.authorityFingerprint)
    }

    @Test
    fun changedEquationHeadChangesAuthorityFingerprint() {
        val first = snapshot()
        val changed = snapshot(
            world = first.world.copy(
                worldEquationRevision = 8,
                worldEquationFingerprint = "equation-b",
            )
        )

        assertNotEquals(first.authorityFingerprint, changed.authorityFingerprint)
    }

    @Test
    fun changedWorldHeadChangesAuthorityFingerprint() {
        val first = snapshot()
        val changed = snapshot(
            world = first.world.copy(
                worldHeadRevision = 12,
                worldHeadFingerprint = "world-b",
            )
        )

        assertNotEquals(first.authorityFingerprint, changed.authorityFingerprint)
    }

    @Test
    fun capturedAtOnlyChangesStateFingerprintNotAuthorityFingerprint() {
        val first = snapshot()
        val second = snapshot(capturedAt = first.capturedAt.plusSeconds(1))

        assertEquals(first.authorityFingerprint, second.authorityFingerprint)
        assertNotEquals(first.stateFingerprint, second.stateFingerprint)
    }

    @Test
    fun negativeCountersAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            SelfPhotonState(
                latestRevisionCount = -1,
                livePhotonCount = 0,
                tombstonedPhotonCount = 0,
                indexFingerprint = "index",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SelfToolState(
                totalTools = -1,
                activeTools = 0,
                trialTools = 0,
                quarantinedTools = 0,
                rejectedTools = 0,
            )
        }
    }

    @Test
    fun invalidRatiosAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            SelfResourceState(
                hardwareFingerprint = "hardware",
                memoryHeadroom = 1.01,
                storageHeadroom = 1.0,
                thermalHeadroom = 1.0,
                energyAvailability = 1.0,
                capabilityReadiness = 1.0,
            )
        }
    }

    private fun snapshot(
        capturedAt: Instant = Instant.parse("2026-09-19T00:00:00Z"),
        world: SelfWorldState = world(),
    ): LifeOsSelfStateSnapshot = LifeOsSelfStateSnapshot(
        capturedAt = capturedAt,
        photon = SelfPhotonState(12, 10, 2, "photon-index-a"),
        memory = SelfMemoryState(10, 7, 9, "memory-a"),
        world = world,
        runtime = SelfRuntimeState(
            topologyFingerprint = "topology-a",
            registeredSubsystems = setOf("world", "health"),
            operationalSubsystems = setOf("world"),
            degradedSubsystems = setOf("health"),
            unavailableSubsystems = emptySet(),
            unboundSubsystems = emptySet(),
        ),
        resource = SelfResourceState("hardware-a", 0.8, 0.7, 1.0, 0.9, 0.75),
        health = SelfHealthState(5, 1, 0, 0, 0, 0),
        recovery = SelfRecoveryState(setOf("repair-1"), "recovery-a"),
        tools = SelfToolState(4, 2, 1, 1, 0),
        liveSources = SelfLiveSourceState(3, 2, 1, 0, "sources-a"),
    )

    private fun world() = SelfWorldState(
        worldHeadRevision = 11,
        worldHeadFingerprint = "world-a",
        worldEquationRevision = 7,
        worldEquationVersion = "equation-v7",
        worldEquationFingerprint = "equation-a",
        bootCycleId = "boot-1",
        bootCycleFingerprint = "boot-a",
        cognitiveSnapshotFingerprint = "cognition-a",
    )
}
