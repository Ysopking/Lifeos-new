package app.lifeos.next.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object LifeOsTokens {
    object Spacing {
        val xxSmall: Dp = 2.dp
        val xSmall: Dp = 4.dp
        val small: Dp = 8.dp
        val medium: Dp = 12.dp
        val large: Dp = 16.dp
        val xLarge: Dp = 24.dp
        val xxLarge: Dp = 32.dp
        val hero: Dp = 48.dp
    }

    object Radius {
        val small: Dp = 12.dp
        val medium: Dp = 18.dp
        val large: Dp = 26.dp
        val xLarge: Dp = 32.dp
    }

    object Elevation {
        val flat: Dp = 0.dp
        val resting: Dp = 1.dp
        val raised: Dp = 6.dp
        val floating: Dp = 12.dp
    }

    object Layout {
        val contentMaxWidth: Dp = 960.dp
        val readingMaxWidth: Dp = 760.dp
        val expandedNavigationWidth: Dp = 216.dp
        val minimumTouchTarget: Dp = 48.dp
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
