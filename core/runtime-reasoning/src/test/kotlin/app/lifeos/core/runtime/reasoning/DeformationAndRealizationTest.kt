package app.lifeos.core.runtime.reasoning

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeformationAndRealizationTest {
    @Test
    fun observedEarlierThanStructuralModelIsModelInconsistencySignal() {
        val result = PathRealizationGap.create(
            structuralDepth = 5,
            observedDepth = 3,
        )

        assertEquals(-2, result.signedGap)
        assertEquals(RealizationGapStatus.EARLIER_THAN_MODEL, result.status)
    }

    @Test
    fun missingIntermediateOrderDoesNotInventOnset() {
        val result = DeformationOnsetAnalyzer().assess(
            DeformationSeries(
                frozenProfileFingerprint = "profile",
                axisId = "contact-identity",
                observations = listOf(
                    OrderedDeformationObservation(0, "same", "same"),
                    OrderedDeformationObservation(1, "same", "same"),
                    OrderedDeformationObservation(3, "same", "changed"),
                ),
            )
        )

        assertNull(result.firstBreakingOrder)
        assertEquals(
            DeformationOnsetStatus.UNRESOLVED_MISSING_ORDER,
            result.status,
        )
    }

    @Test
    fun contiguousSeriesCanProveFirstBreakingOrder() {
        val result = DeformationOnsetAnalyzer().assess(
            DeformationSeries(
                frozenProfileFingerprint = "profile",
                axisId = "project-state",
                observations = listOf(
                    OrderedDeformationObservation(0, "a", "a"),
                    OrderedDeformationObservation(1, "a", "a"),
                    OrderedDeformationObservation(2, "a", "b"),
                ),
            )
        )

        assertEquals(2, result.firstBreakingOrder)
        assertEquals(1, result.preservedThroughOrder)
        assertEquals(DeformationOnsetStatus.BROKEN, result.status)
    }
}
