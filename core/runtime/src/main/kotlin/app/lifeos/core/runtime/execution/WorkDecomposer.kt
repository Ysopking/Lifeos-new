package app.lifeos.core.runtime.execution

import kotlin.math.ceil

data class WorkDecompositionContext(
    val node: WorkNode,
    val predictedWorkUnits: Long,
    val schedulingOverheadWorkUnits: Long,
    val adaptiveQuantum: Long,
    val maximumBatchSize: Int,
    val availableParallelism: Int,
    val cachedExactResult: WorkResultRef? = null,
) {
    init {
        require(predictedWorkUnits >= 0L)
        require(schedulingOverheadWorkUnits >= 0L)
        require(adaptiveQuantum > 0L)
        require(maximumBatchSize > 0)
        require(availableParallelism > 0)
        require(cachedExactResult == null || cachedExactResult.nodeId == node.id)
    }
}

enum class WorkDecompositionKind {
    ATOMIC,
    SPLIT,
    BATCH,
    REUSE,
}

data class WorkDecompositionDecision(
    val kind: WorkDecompositionKind,
    val partitions: Int = 1,
    val batchSize: Int = 1,
    val reusedResult: WorkResultRef? = null,
    val reason: String,
) {
    init {
        require(partitions > 0)
        require(batchSize > 0)
        require(reason.isNotBlank())
        require((kind == WorkDecompositionKind.REUSE) == (reusedResult != null))
        if (kind != WorkDecompositionKind.SPLIT) require(partitions == 1)
    }
}

fun interface WorkDecomposer {
    fun decide(context: WorkDecompositionContext): WorkDecompositionDecision
}

class WorkDecomposerRegistry(
    decomposers: Map<String, WorkDecomposer> = emptyMap(),
    private val fallback: WorkDecomposer = AdaptiveGranularityController(),
) {
    private val decomposers = decomposers.toMap().also { map ->
        require(map.keys.none(String::isBlank))
    }

    fun forOperation(operationKind: String): WorkDecomposer {
        require(operationKind.isNotBlank())
        return decomposers[operationKind] ?: fallback
    }
}

/**
 * Cost-aware decomposition. Exact cache reuse always wins. Splitting is admitted only while useful
 * work is at least [minimumUsefulToOverheadRatio] times scheduler overhead.
 */
class AdaptiveGranularityController(
    private val minimumUsefulToOverheadRatio: Double = 8.0,
    private val maximumPartitions: Int = 256,
) : WorkDecomposer {
    init {
        require(minimumUsefulToOverheadRatio.isFinite() && minimumUsefulToOverheadRatio >= 1.0)
        require(maximumPartitions >= 2)
    }

    override fun decide(context: WorkDecompositionContext): WorkDecompositionDecision {
        val cached = context.cachedExactResult
        if (cached != null && context.node.pure) {
            return WorkDecompositionDecision(
                kind = WorkDecompositionKind.REUSE,
                reusedResult = cached,
                reason = "exact-result-cache-hit",
            )
        }

        val predicted = context.predictedWorkUnits
        val overhead = context.schedulingOverheadWorkUnits
        val economicallySplittable = predicted > context.adaptiveQuantum &&
            context.availableParallelism > 1 &&
            (overhead == 0L || predicted.toDouble() >= overhead.toDouble() * minimumUsefulToOverheadRatio)

        if (economicallySplittable) {
            val desired = ceil(predicted.toDouble() / context.adaptiveQuantum.toDouble())
                .toInt()
                .coerceAtLeast(2)
            val bounded = minOf(
                desired,
                context.availableParallelism * 4,
                maximumPartitions,
            )
            return WorkDecompositionDecision(
                kind = WorkDecompositionKind.SPLIT,
                partitions = bounded,
                reason = "predicted-work-exceeds-economic-split-threshold",
            )
        }

        if (predicted > context.adaptiveQuantum && context.maximumBatchSize > 1) {
            val batch = ceil(predicted.toDouble() / context.adaptiveQuantum.toDouble())
                .toInt()
                .coerceIn(1, context.maximumBatchSize)
            return WorkDecompositionDecision(
                kind = WorkDecompositionKind.BATCH,
                batchSize = batch,
                reason = "split-overhead-not-economic-batch-instead",
            )
        }

        return WorkDecompositionDecision(
            kind = WorkDecompositionKind.ATOMIC,
            reason = "work-below-adaptive-split-threshold",
        )
    }
}
