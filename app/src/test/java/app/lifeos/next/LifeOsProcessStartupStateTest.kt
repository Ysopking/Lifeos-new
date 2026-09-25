package app.lifeos.next

import app.lifeos.core.runtime.boot.RuntimeAvailability
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LifeOsProcessStartupStateTest {
    @Test
    fun readOnlyStateOpensUiWithoutGrantingEffectReadiness() {
        val state = LifeOsProcessStartupState.readOnly("repair required")

        assertTrue(state.uiReady)
        assertTrue(state.usable)
        assertFalse(state.ready)
        assertTrue(state.availability == RuntimeAvailability.READ_ONLY)
    }

    @Test
    fun fullAndDegradedRemainUiAndEffectReady() {
        val full = LifeOsProcessStartupState.ready(RuntimeAvailability.FULL)
        val degraded = LifeOsProcessStartupState.ready(RuntimeAvailability.DEGRADED)

        assertTrue(full.uiReady && full.ready)
        assertTrue(degraded.uiReady && degraded.ready)
    }
}
