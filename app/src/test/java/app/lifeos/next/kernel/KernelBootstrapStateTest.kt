package app.lifeos.next.kernel

import app.lifeos.core.runtime.boot.RuntimeAvailability
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KernelBootstrapStateTest {
    @Test
    fun readOnlyStateIsReadableButCannotWriteOrAct() {
        val state = KernelBootstrapState(status = KernelBootstrapStatus.READ_ONLY)
        assertTrue(state.readable)
        assertFalse(state.writable)
        assertFalse(state.actionable)
        assertFalse(state.ready)
        assertTrue(state.availability == RuntimeAvailability.READ_ONLY)
    }

    @Test
    fun degradedStateRetainsProductiveAvailability() {
        val state = KernelBootstrapState(status = KernelBootstrapStatus.DEGRADED)
        assertTrue(state.readable)
        assertTrue(state.writable)
        assertTrue(state.actionable)
        assertTrue(state.ready)
        assertTrue(state.availability == RuntimeAvailability.DEGRADED)
    }

    @Test
    fun safeModeIsFailClosed() {
        val state = KernelBootstrapState(status = KernelBootstrapStatus.SAFE_MODE)
        assertFalse(state.readable)
        assertFalse(state.writable)
        assertFalse(state.actionable)
        assertFalse(state.ready)
        assertTrue(state.availability == RuntimeAvailability.SAFE_MODE)
    }
}
