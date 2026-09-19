package app.lifeos.core.runtime.self

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class SelfStateFingerprintTest {
    @Test
    fun unorderedSubsystemAndRepairInputsCanonicalizeIdentically() {
        val first = snapshot(
            registered = linkedSetOf("World", "HEALTH", "Tools"),
            repairs = linkedSetOf("repair-b", "repair-a"),
        )
        val second = snapshot(
            registered = linkedSetOf("tools", "health", "world"),
            repairs = linkedSetOf("repair-a", "repair-b"),
        )

        assertEquals(first.authorityFingerprint, second.authorityFingerprint)
        assertEquals(first.stateFingerprint, second.stateFingerprint)
    }

    @Test
    fun nullableEncodingDoesNotCollideWithLiteralUnavailableText() {
        val unavailable = snapshot(indexFingerprint = null)
        val literal = snapshot(indexFingerprint = "<unavailable>")

        assertNotEquals(unavailable.authorityFingerprint, literal.authorityFingerprint)
    }

    @Test
    fun unavailableCollectionDoesNotCollapseToKnownEmptyCollection() {
        val unavailable = snapshot(registered = null, repairs = null)
        val knownEmpty = snapshot(registered = emptySet(), repairs = emptySet())

        assertEquals(unavailable.authorityFingerprint, knownEmpty.authorityFingerprint)
        assertNotEquals(unavailable.stateFingerprint, knownEmpty.stateFingerprint)
    }

    @Test
    fun rebuildableMemoryTopologyAndToolChangesDoNotRewriteDurableAuthorityIdentity() {
        val first = snapshot()
        val second = first.copy(
            memory = first.memory.copy(
                authoritativePhotonCount = 99,
                graphNodeCount = 88,
                graphEdgeCount = 77,
                memoryFingerprint = "memory-rehydrated",
            ),
            runtime = first.runtime.copy(
                topologyFingerprint = "topology-rehydrated",
                registeredSubsystems = setOf("world", "health", "tools"),
                unboundSubsystems = setOf("tools"),
            ),
            tools = first.tools.copy(
                totalTools = 3,
                activeTools = 2,
                trialTools = 1,
            ),
        )

        assertEquals(first.authorityFingerprint, second.authorityFingerprint)
        assertNotEquals(first.stateFingerprint, second.stateFingerprint)
    }

    @Test
    fun operationalOnlyChangeDoesNotRewriteAuthorityIdentity() {
        val first = snapshot(operational = setOf("world"))
        val second = snapshot(operational = setOf("world", "health"))

        assertEquals(first.authorityFingerprint, second.authorityFingerprint)
        assertNotEquals(first.stateFingerprint, second.stateFingerprint)
    }

    private fun snapshot(
        registered: Set<String>? = setOf("world", "health"),
        operational: Set<String>? = setOf("world"),
        repairs: Set<String>? = emptySet(),
        indexFingerprint: String? = "index-a",
    ) = LifeOsSelfStateSnapshot(
        capturedAt = Instant.parse("2026-09-19T00:00:00Z"),
        photon = SelfPhotonState(1, 1, 0, indexFingerprint),
        memory = SelfMemoryState(1, 1, 0, "memory-a"),
        world = SelfWorldState(1, "world-a", 1, "eq-v1", "eq-a", "boot-a", "boot-fp-a", "cog-a"),
        runtime = SelfRuntimeState(
            topologyFingerprint = "topology-a",
            registeredSubsystems = registered,
            operationalSubsystems = operational,
            degradedSubsystems = emptySet(),
            unavailableSubsystems = emptySet(),
            unboundSubsystems = emptySet(),
        ),
        resource = SelfResourceState("hardware-a", 1.0, 1.0, 1.0, 1.0, 1.0),
        health = SelfHealthState(1, 0, 0, 0, 0, 0),
        recovery = SelfRecoveryState(repairs, "recovery-a"),
        tools = SelfToolState(1, 1, 0, 0, 0),
        liveSources = SelfLiveSourceState(1, 1, 0, 0, "source-a"),
    )
}
