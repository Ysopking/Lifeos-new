package app.lifeos.next.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object LifeOsTokens {
    object Spacing {
        val xSmall: Dp = 4.dp
        val small: Dp = 8.dp
        val medium: Dp = 12.dp
        val large: Dp = 16.dp
        val xLarge: Dp = 24.dp
        val xxLarge: Dp = 32.dp
    }

    object Radius {
        val small: Dp = 14.dp
        val medium: Dp = 20.dp
        val large: Dp = 28.dp
        val composer: Dp = 28.dp
    }

    object Layout {
        val compactHorizontalPadding: Dp = 16.dp
        val wideHorizontalPadding: Dp = 28.dp
        val readingMaxWidth: Dp = 720.dp
        val workspaceMaxWidth: Dp = 1120.dp
        val inspectorWidth: Dp = 360.dp
        val navigationRailWidth: Dp = 76.dp
    }

    object Size {
        val minimumTouchTarget: Dp = 48.dp
        val navigationIcon: Dp = 23.dp
        val actionIcon: Dp = 21.dp
    }

    object Elevation {
        val resting: Dp = 0.dp
        val raised: Dp = 1.dp
        val overlay: Dp = 6.dp
    }

    object Alpha {
        const val subtle: Float = 0.06f
        const val muted: Float = 0.62f
        const val strong: Float = 0.90f
    }

    object Motion {
        const val quickMs: Int = 120
        const val standardMs: Int = 220
        const val deliberateMs: Int = 320
    }
}

enum class LifeOsMotionMode {
    REDUCED,
    STANDARD,
}

object LifeOsMotionPolicy {
    fun modeFor(animatorDurationScale: Float): LifeOsMotionMode {
        require(animatorDurationScale.isFinite() && animatorDurationScale >= 0f)
        return if (animatorDurationScale == 0f) LifeOsMotionMode.REDUCED else LifeOsMotionMode.STANDARD
    }

    fun durationMs(baseDurationMs: Int, animatorDurationScale: Float): Int {
        require(baseDurationMs >= 0)
        return when (modeFor(animatorDurationScale)) {
            LifeOsMotionMode.REDUCED -> 0
            LifeOsMotionMode.STANDARD -> (baseDurationMs * animatorDurationScale).toInt().coerceAtLeast(1)
        }
    }
}
