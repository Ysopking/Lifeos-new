package app.lifeos.core.runtime

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield

enum class CognitiveDepth { MINIMAL, LOCAL, EXTENDED, DEEP }
enum class CognitivePriority { FOREGROUND_CONVERSATION, INTERACTIVE_SUPPORT, BACKGROUND_INTELLIGENCE }

data class CognitiveWorkBudget(
    val depth: CognitiveDepth,
    val priority: CognitivePriority,
    val maxMillis: Long,
    val maxModules: Int,
    val allowDeepSearch: Boolean,
    val allowBackgroundConvergence: Boolean,
) {
    init { require(maxMillis > 0 && maxModules > 0) }
}

/** System invariant: background intelligence must never block conversation. */
interface CognitivePreemptionController {
    fun foregroundWaiting(): Boolean
    suspend fun yieldIfForegroundWaiting()
}

/**
 * Cooperative foreground-first controller for long-running cognitive loops. Background workers call
 * [yieldIfForegroundWaiting] at deterministic checkpoints; foreground work brackets itself with
 * [withForegroundWork]. No fact, policy, or outcome is changed by preemption: only compute timing changes.
 */
class ForegroundFirstCognitivePreemptionController : CognitivePreemptionController {
    private val foregroundDemand = AtomicInteger(0)

    override fun foregroundWaiting(): Boolean = foregroundDemand.get() > 0

    suspend fun <T> withForegroundWork(block: suspend () -> T): T {
        foregroundDemand.incrementAndGet()
        return try {
            block()
        } finally {
            val remaining = foregroundDemand.decrementAndGet()
            check(remaining >= 0) { "Foreground cognitive demand underflow" }
        }
    }

    override suspend fun yieldIfForegroundWaiting() {
        while (foregroundWaiting()) {
            // yield() gives an already-runnable foreground coroutine the first opportunity. The tiny delay
            // prevents a hot background loop from repeatedly re-entering the scheduler while foreground runs.
            yield()
            if (foregroundWaiting()) delay(PREEMPTION_POLL_MILLIS)
        }
    }

    private companion object {
        const val PREEMPTION_POLL_MILLIS = 2L
    }
}

class AdaptiveCognitiveDepthPolicy {
    fun budget(
        complexityMicros: Long,
        uncertaintyMicros: Long,
        riskMicros: Long,
        hardwarePressureMicros: Long,
        priority: CognitivePriority = CognitivePriority.FOREGROUND_CONVERSATION,
    ): CognitiveWorkBudget {
        val pressure = hardwarePressureMicros.coerceIn(0L, MICROS)
        val score = maxOf(complexityMicros, uncertaintyMicros, riskMicros).coerceIn(0L, MICROS)
        val requestedDepth = when {
            score < 150_000L -> CognitiveDepth.MINIMAL
            score < 450_000L -> CognitiveDepth.LOCAL
            score < 750_000L -> CognitiveDepth.EXTENDED
            else -> CognitiveDepth.DEEP
        }
        val depth = pressureLimitedDepth(requestedDepth, pressure, priority)
        val requestedModules = when (depth) {
            CognitiveDepth.MINIMAL -> 2
            CognitiveDepth.LOCAL -> 6
            CognitiveDepth.EXTENDED -> 16
            CognitiveDepth.DEEP -> 32
        }
        val capacityMicros = MICROS - pressure
        val maxModules = scaleIntFloor(requestedModules, capacityMicros, minimum = 1)
        val baseMillis = when (depth) {
            CognitiveDepth.MINIMAL -> 350L
            CognitiveDepth.LOCAL -> 1_000L
            CognitiveDepth.EXTENDED -> 2_000L
            CognitiveDepth.DEEP -> 4_000L
        }
        val minimumMillis = if (priority == CognitivePriority.FOREGROUND_CONVERSATION) 200L else 100L
        val maxMillis = scaleLongFloor(baseMillis, capacityMicros, minimumMillis)

        return CognitiveWorkBudget(
            depth = depth,
            priority = priority,
            maxMillis = maxMillis,
            maxModules = maxModules,
            allowDeepSearch = depth == CognitiveDepth.DEEP && pressure < 700_000L,
            allowBackgroundConvergence = priority != CognitivePriority.BACKGROUND_INTELLIGENCE || pressure < 600_000L,
        )
    }

    /** Fast-chat turns never pay for a world-scale cognitive run. */
    fun budgetForRoute(
        route: ConversationPath,
        complexityMicros: Long,
        uncertaintyMicros: Long,
        riskMicros: Long,
        hardwarePressureMicros: Long,
    ): CognitiveWorkBudget {
        if (route == ConversationPath.FAST_CHAT) {
            val pressure = hardwarePressureMicros.coerceIn(0L, MICROS)
            return CognitiveWorkBudget(
                depth = CognitiveDepth.MINIMAL,
                priority = CognitivePriority.FOREGROUND_CONVERSATION,
                maxMillis = scaleLongFloor(300L, MICROS - pressure, 120L),
                maxModules = 2,
                allowDeepSearch = false,
                allowBackgroundConvergence = false,
            )
        }
        return budget(
            complexityMicros = complexityMicros,
            uncertaintyMicros = uncertaintyMicros,
            riskMicros = riskMicros,
            hardwarePressureMicros = hardwarePressureMicros,
            priority = CognitivePriority.FOREGROUND_CONVERSATION,
        )
    }

    private fun pressureLimitedDepth(
        requested: CognitiveDepth,
        pressure: Long,
        priority: CognitivePriority,
    ): CognitiveDepth = when {
        pressure >= 900_000L -> CognitiveDepth.MINIMAL
        pressure >= 800_000L && requested.ordinal > CognitiveDepth.LOCAL.ordinal -> CognitiveDepth.LOCAL
        pressure >= 650_000L && priority == CognitivePriority.BACKGROUND_INTELLIGENCE && requested.ordinal > CognitiveDepth.LOCAL.ordinal -> CognitiveDepth.LOCAL
        pressure >= 650_000L && requested == CognitiveDepth.DEEP -> CognitiveDepth.EXTENDED
        else -> requested
    }

    private fun scaleIntFloor(value: Int, capacityMicros: Long, minimum: Int): Int =
        ((value.toLong() * capacityMicros) / MICROS).toInt().coerceAtLeast(minimum)

    private fun scaleLongFloor(value: Long, capacityMicros: Long, minimum: Long): Long =
        ((value * capacityMicros) / MICROS).coerceAtLeast(minimum)

    private companion object {
        const val MICROS = 1_000_000L
    }
}
