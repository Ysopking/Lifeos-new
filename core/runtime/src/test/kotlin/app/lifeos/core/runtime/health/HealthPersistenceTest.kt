package app.lifeos.core.runtime.health

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class HealthPersistenceTest {
    /** Byte round trip makes each coordinator reconstruct state rather than share object identity. */
    private class Store : HealthControlStore {
        var bytes: ByteArray? = null
        var failWrite = false
        override fun load() = bytes?.let(HealthControlCodec::decode) ?: HealthControlSnapshot()
        override fun save(snapshot: HealthControlSnapshot) {
            if (failWrite) throw IOException("disk full")
            bytes = HealthControlCodec.encode(snapshot)
        }
    }

    @Test fun quarantineAndSafeModeSurviveRecreation() = runTest {
        val store = Store()
        val original = RecoveryCoordinator(controls = HealthControlRepository(store))
        assertFailsWith<SecurityException> {
            original.execute(HealthNodes.TaskStore) { throw SecurityException() }
        }
        val restored = RecoveryCoordinator(controls = HealthControlRepository(store))
        restored.restoreProtection()
        assertTrue(restored.safeMode.active)
        assertTrue(restored.quarantine.contains(HealthNodes.TaskStore))
        assertEquals(HealthState.QUARANTINED, restored.graph.states.value["TaskStore"])
        assertFailsWith<ComponentUnavailable> { restored.execute(HealthNodes.TaskStore) { fail("blocked") } }
        assertEquals(listOf("QUARANTINE", "SAFE_MODE"), restored.controls.snapshot().decisions.map { it.action })
    }

    @Test fun failedProbeNeverReleasesProtection() = runTest {
        val health = RecoveryCoordinator()
        health.safeMode.enter("BootLoop")
        assertFailsWith<IOException> { health.verifyAndRelease(setOf("BootLoop")) { throw IOException() } }
        assertTrue(health.safeMode.active)
    }

    @Test fun verifiedReleaseIsDurableAndAttributed() = runTest {
        val store = Store()
        val health = RecoveryCoordinator(controls = HealthControlRepository(store))
        health.safeMode.enter("BootLoop")
        assertTrue(health.verifyAndRelease(setOf("BootLoop")) { Unit })
        val restored = HealthControlRepository(store).snapshot()
        assertTrue(restored.safeReasons.isEmpty())
        assertEquals("VERIFIED_RELEASE", restored.decisions.last().action)
        assertEquals("user-requested-probe", restored.decisions.last().actor)
    }

    @Test fun newFaultDuringProbeInvalidatesRelease() = runTest {
        val health = RecoveryCoordinator()
        health.safeMode.enter("BootLoop")
        val entered = CompletableDeferred<Unit>()
        val continueProbe = CompletableDeferred<Unit>()
        val release = async {
            health.verifyAndRelease(setOf("BootLoop")) {
                entered.complete(Unit)
                continueProbe.await()
            }
        }
        entered.await()
        health.safeMode.enter("BootLoop")
        continueProbe.complete(Unit)
        assertFalse(release.await())
        assertTrue(health.safeMode.active)
    }

    @Test fun failedReleaseWriteKeepsOldProtectionAndOriginalBytes() = runTest {
        val store = Store()
        val health = RecoveryCoordinator(controls = HealthControlRepository(store))
        health.safeMode.enter("BootLoop")
        val original = store.bytes!!.copyOf()
        store.failWrite = true
        assertFalse(health.verifyAndRelease(setOf("BootLoop")) { Unit })
        assertTrue(health.safeMode.active)
        assertContentEquals(original, store.bytes)
        assertTrue("BootLoop" in HealthControlRepository(store).snapshot().safeReasons)
    }

    @Test fun corruptStoreIsPreservedAndCannotBeOverwrittenByProtectionChanges() = runTest {
        val store = Store().apply { bytes = byteArrayOf(1, 2, 3) }
        val health = RecoveryCoordinator(controls = HealthControlRepository(store))
        assertTrue(health.safeMode.active)
        health.safeMode.enter("BootLoop")
        assertContentEquals(byteArrayOf(1, 2, 3), store.bytes)
        assertFalse(health.verifyAndRelease(setOf("BootLoop")) { Unit })
    }

    @Test fun failedRestrictionWriteStillIsolatesInCurrentProcess() = runTest {
        val store = Store().apply { failWrite = true }
        val health = RecoveryCoordinator(controls = HealthControlRepository(store))
        health.quarantine.isolate(HealthNodes.Worker)
        assertTrue(health.quarantine.contains(HealthNodes.Worker))
        assertTrue(health.safeMode.active)
        assertTrue(HealthControlRepository.PERSISTENCE_FAILURE in health.controls.snapshot().safeReasons)
    }

    @Test fun codecRejectsTruncationFutureVersionAndTrailingBytes() {
        val bytes = HealthControlCodec.encode(HealthControlSnapshot())
        for (size in 0 until bytes.size) assertFails { HealthControlCodec.decode(bytes.copyOf(size)) }
        assertFails { HealthControlCodec.decode(bytes + byteArrayOf(0)) }
        assertFails { HealthControlCodec.decode(bytes.copyOf().apply { this[3] = 2 }) }
    }

    @Test fun boundedProvenanceRetainsProtectionBeyondHistoryWindow() {
        val controls = HealthControlRepository(Store())
        repeat(125) { controls.protect("node-$it", quarantine = true) }
        val restored = HealthControlCodec.decode(HealthControlCodec.encode(controls.snapshot()))
        assertEquals(100, restored.decisions.size)
        assertEquals(125, restored.quarantined.size)
        assertTrue("node-0" in restored.quarantined)
    }

    @Test fun releaseDoesNotClearUnverifiedComponent() = runTest {
        val health = RecoveryCoordinator()
        health.quarantine.isolate(HealthNodes.Worker)
        health.safeMode.enter("BootLoop")
        assertTrue(health.verifyAndRelease(setOf("BootLoop")) { Unit })
        assertTrue(health.quarantine.contains(HealthNodes.Worker))
    }
}
