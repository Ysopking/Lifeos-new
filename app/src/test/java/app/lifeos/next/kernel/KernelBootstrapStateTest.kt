package app.lifeos.next.kernel

import app.lifeos.core.runtime.boot.RuntimeAvailability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KernelBootstrapStateTest {
    @Test
    fun runtimeAvailabilitySeparatesReadFromWriteAndEffects() {
        val full = KernelBootstrapState(status = KernelBootstrapStatus.READY)
        assertEquals(RuntimeAvailability.FULL, full.availability)
        assertTrue(full.readable)
        assertTrue(full.writable)
        assertTrue(full.actionable)
        assertTrue(full.usable)

        val degraded = KernelBootstrapState(status = KernelBootstrapStatus.DEGRADED)
        assertEquals(RuntimeAvailability.DEGRADED, degraded.availability)
        assertTrue(degraded.readable)
        assertTrue(degraded.writable)
        assertTrue(degraded.actionable)

        val readOnly = KernelBootstrapState(status = KernelBootstrapStatus.READ_ONLY)
        assertEquals(RuntimeAvailability.READ_ONLY, readOnly.availability)
        assertTrue(readOnly.readable)
        assertFalse(readOnly.writable)
        assertFalse(readOnly.actionable)
        assertTrue(readOnly.usable)

        val safeMode = KernelBootstrapState(status = KernelBootstrapStatus.SAFE_MODE)
        assertEquals(RuntimeAvailability.SAFE_MODE, safeMode.availability)
        assertFalse(safeMode.readable)
        assertFalse(safeMode.writable)
        assertFalse(safeMode.actionable)
        assertTrue(safeMode.usable)
    }
}
