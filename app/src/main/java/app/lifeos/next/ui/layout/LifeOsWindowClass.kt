package app.lifeos.next.ui.layout

enum class LifeOsWindowClass {
    COMPACT,
    MEDIUM,
    EXPANDED;

    companion object {
        const val MEDIUM_MIN_WIDTH_DP: Float = 600f
        const val EXPANDED_MIN_WIDTH_DP: Float = 840f

        fun fromWidthDp(widthDp: Float): LifeOsWindowClass {
            require(widthDp.isFinite() && widthDp >= 0f)
            return when {
                widthDp < MEDIUM_MIN_WIDTH_DP -> COMPACT
                widthDp < EXPANDED_MIN_WIDTH_DP -> MEDIUM
                else -> EXPANDED
            }
        }
    }
}
