package app.lifeos.next

import app.lifeos.core.runtime.boot.RuntimeAvailability
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LifeOsProcessStartupStateTest {
    @Test
    fun readOnlyProcessStateCanOpenReadUiWithoutOwnerEffects() {
        val state = LifeOsProcessStartupState.ready(RuntimeAvailability.READ_ONLY)
        assertTrue(state.ready)
        assertTrue(state.usable)
        assertTrue(state.readable)
        assertFalse(state.writable)
        assertFalse(state.actionable)
    }

    @Test
    fun fullProcessStateRemainsActionable() {
        val state = LifeOsProcessStartupState.ready(RuntimeAvailability.FULL)
        assertTrue(state.ready)
        assertTrue(state.usable)
        assertTrue(state.writable)
        assertTrue(state.actionable)
    }

    @Test
    fun failedProcessStateIsSafeModeAndNotUsable() {
        val state = LifeOsProcessStartupState.failed("boom")
        assertFalse(state.ready)
        assertFalse(state.usable)
        assertFalse(state.actionable)
        assertTrue(state.availability == RuntimeAvailability.SAFE_MODE)
    }
}
