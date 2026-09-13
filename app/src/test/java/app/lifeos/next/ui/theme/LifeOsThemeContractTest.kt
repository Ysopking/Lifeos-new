package app.lifeos.next.ui.theme

import app.lifeos.next.ui.components.LifeOsStateKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LifeOsThemeContractTest {
    @Test
    fun zeroAnimatorScaleDisablesMotion() {
        assertEquals(LifeOsMotionMode.REDUCED, LifeOsMotionPolicy.modeFor(0f))
        assertEquals(0, LifeOsMotionPolicy.durationMs(LifeOsTokens.Motion.standardMs, 0f))
    }

    @Test
    fun positiveAnimatorScalePreservesMotion() {
        assertEquals(LifeOsMotionMode.STANDARD, LifeOsMotionPolicy.modeFor(1f))
        assertEquals(LifeOsTokens.Motion.standardMs, LifeOsMotionPolicy.durationMs(LifeOsTokens.Motion.standardMs, 1f))
    }

    @Test
    fun everyStateKindHasVisibleTextSoColorIsNotTheOnlySignal() {
        val labels = LifeOsStateKind.entries.map { it.visibleLabel }
        assertTrue(labels.all { it.isNotBlank() })
        assertEquals(labels.size, labels.distinct().size)
    }
}
