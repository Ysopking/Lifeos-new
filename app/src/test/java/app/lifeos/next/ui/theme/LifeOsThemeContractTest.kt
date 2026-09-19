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
        assertEquals(
            LifeOsTokens.Motion.standardMs,
            LifeOsMotionPolicy.durationMs(LifeOsTokens.Motion.standardMs, 1f),
        )
    }

    @Test
    fun designTokensPreserveResponsiveHierarchy() {
        val spacing = listOf(
            LifeOsTokens.Spacing.xSmall,
            LifeOsTokens.Spacing.small,
            LifeOsTokens.Spacing.medium,
            LifeOsTokens.Spacing.large,
            LifeOsTokens.Spacing.xLarge,
            LifeOsTokens.Spacing.xxLarge,
        )
        assertTrue(spacing.zipWithNext().all { (left, right) -> left < right })

        assertTrue(LifeOsTokens.Radius.small.value > 0f)
        assertTrue(LifeOsTokens.Radius.medium > LifeOsTokens.Radius.small)
        assertTrue(LifeOsTokens.Radius.large >= LifeOsTokens.Radius.medium)
        assertTrue(LifeOsTokens.Layout.readingMaxWidth < LifeOsTokens.Layout.workspaceMaxWidth)
        assertTrue(LifeOsTokens.Layout.inspectorWidth < LifeOsTokens.Layout.readingMaxWidth)
        assertTrue(LifeOsTokens.Size.minimumTouchTarget.value >= 48f)
    }

    @Test
    fun everyStateKindHasVisibleTextSoColorIsNotTheOnlySignal() {
        val labels = LifeOsStateKind.entries.map { it.visibleLabel }
        assertTrue(labels.all { it.isNotBlank() })
        assertEquals(labels.size, labels.distinct().size)
    }
}
