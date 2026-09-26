package app.lifeos.core.runtime.reasoning

import app.lifeos.core.field.StableFieldIds

enum class RealizationGapStatus {
    ALIGNED,
    DELAYED,
    EARLIER_THAN_MODEL,
    NOT_OBSERVED,
}

data class PathRealizationGap(
    val structuralDepth: Int,
    val observedDepth: Int?,
    val signedGap: Int?,
    val status: RealizationGapStatus,
    val fingerprint: String,
) {
    init {
        require(structuralDepth >= 0)
        require(observedDepth == null || observedDepth >= 0)
        require(signedGap == observedDepth?.minus(structuralDepth))
    }

    companion object {
        fun create(
            structuralDepth: Int,
            observedDepth: Int?,
        ): PathRealizationGap {
            require(structuralDepth >= 0)
            require(observedDepth == null || observedDepth >= 0)
            val gap = observedDepth?.minus(structuralDepth)
            val status = when {
                observedDepth == null -> RealizationGapStatus.NOT_OBSERVED
                gap == 0 -> RealizationGapStatus.ALIGNED
                requireNotNull(gap) > 0 -> RealizationGapStatus.DELAYED
                else -> RealizationGapStatus.EARLIER_THAN_MODEL
            }
            return PathRealizationGap(
                structuralDepth = structuralDepth,
                observedDepth = observedDepth,
                signedGap = gap,
                status = status,
                fingerprint = StableFieldIds.fingerprint(
                    "meta-realization-gap/v1",
                    structuralDepth.toString(),
                    observedDepth?.toString() ?: "NOT_OBSERVED",
                    status.name,
                ),
            )
        }
    }
}

data class OrderedDeformationObservation(
    val order: Int,
    val baselineFingerprint: String,
    val deformedFingerprint: String,
) {
    init {
        require(order >= 0)
        require(baselineFingerprint.isNotBlank())
        require(deformedFingerprint.isNotBlank())
    }
}

enum class DeformationOnsetStatus {
    STABLE_WITHIN_OBSERVED,
    BROKEN,
    UNRESOLVED_MISSING_ORDER,
}

data class DeformationSeries(
    val frozenProfileFingerprint: String,
    val axisId: String,
    val observations: List<OrderedDeformationObservation>,
) {
    init {
        require(frozenProfileFingerprint.isNotBlank())
        require(axisId.isNotBlank())
        require(observations.isNotEmpty())
        require(observations == observations.sortedBy { it.order })
        require(observations.map { it.order }.distinct().size == observations.size)
        require(observations.first().order == 0)
    }
}

data class DeformationOnsetAssessment(
    val firstBreakingOrder: Int?,
    val preservedThroughOrder: Int,
    val status: DeformationOnsetStatus,
    val fingerprint: String,
)

class DeformationOnsetAnalyzer {
    fun assess(series: DeformationSeries): DeformationOnsetAssessment {
        val byOrder = series.observations.associateBy { it.order }
        val maxObserved = series.observations.maxOf { it.order }
        var contiguousThrough = -1
        for (order in 0..maxObserved) {
            if (byOrder[order] == null) break
            contiguousThrough = order
        }

        val firstObservedBreak = series.observations.firstOrNull {
            it.baselineFingerprint != it.deformedFingerprint
        }
        val firstBreak = firstObservedBreak
            ?.takeIf { it.order <= contiguousThrough }
            ?.order

        val status = when {
            firstBreak != null -> DeformationOnsetStatus.BROKEN
            firstObservedBreak != null -> DeformationOnsetStatus.UNRESOLVED_MISSING_ORDER
            contiguousThrough < maxObserved -> DeformationOnsetStatus.UNRESOLVED_MISSING_ORDER
            else -> DeformationOnsetStatus.STABLE_WITHIN_OBSERVED
        }
        val preservedThrough = when {
            firstBreak != null -> firstBreak - 1
            else -> contiguousThrough
        }.coerceAtLeast(0)

        return DeformationOnsetAssessment(
            firstBreakingOrder = firstBreak,
            preservedThroughOrder = preservedThrough,
            status = status,
            fingerprint = StableFieldIds.fingerprint(
                "meta-deformation-onset/v1",
                series.frozenProfileFingerprint,
                series.axisId,
                status.name,
                firstBreak?.toString() ?: "UNRESOLVED",
                preservedThrough.toString(),
                *series.observations.flatMap {
                    listOf(
                        it.order.toString(),
                        it.baselineFingerprint,
                        it.deformedFingerprint,
                    )
                }.toTypedArray(),
            ),
        )
    }
}
