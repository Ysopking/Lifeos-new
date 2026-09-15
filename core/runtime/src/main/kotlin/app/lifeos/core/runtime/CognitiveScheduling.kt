package app.lifeos.core.runtime

enum class CognitiveDepth { MINIMAL, LOCAL, EXTENDED, DEEP }
enum class CognitivePriority { FOREGROUND_CONVERSATION, INTERACTIVE_SUPPORT, BACKGROUND_INTELLIGENCE }

data class CognitiveWorkBudget(
    val depth: CognitiveDepth,
    val priority: CognitivePriority,
    val maxMillis: Long,
    val maxModules: Int,
    val allowDeepSearch: Boolean,
    val allowBackgroundConvergence: Boolean,
) { init { require(maxMillis > 0 && maxModules > 0) } }

/** System invariant: background intelligence must never block conversation. */
interface CognitivePreemptionController {
    fun foregroundWaiting(): Boolean
    suspend fun yieldIfForegroundWaiting()
}

class AdaptiveCognitiveDepthPolicy {
    fun budget(complexityMicros: Long, uncertaintyMicros: Long, riskMicros: Long, hardwarePressureMicros: Long): CognitiveWorkBudget {
        val pressure = hardwarePressureMicros.coerceIn(0, 1_000_000)
        val score = maxOf(complexityMicros, uncertaintyMicros, riskMicros).coerceIn(0, 1_000_000)
        val depth = when {
            score < 150_000 -> CognitiveDepth.MINIMAL
            score < 450_000 -> CognitiveDepth.LOCAL
            score < 750_000 -> CognitiveDepth.EXTENDED
            else -> CognitiveDepth.DEEP
        }
        val maxModules = when (depth) { CognitiveDepth.MINIMAL -> 2; CognitiveDepth.LOCAL -> 6; CognitiveDepth.EXTENDED -> 16; CognitiveDepth.DEEP -> 32 }
        return CognitiveWorkBudget(depth, CognitivePriority.FOREGROUND_CONVERSATION,
            maxMillis = (2_000L - pressure / 1_000L).coerceAtLeast(250L), maxModules = maxModules,
            allowDeepSearch = depth == CognitiveDepth.DEEP && pressure < 700_000,
            allowBackgroundConvergence = pressure < 850_000)
    }
}
