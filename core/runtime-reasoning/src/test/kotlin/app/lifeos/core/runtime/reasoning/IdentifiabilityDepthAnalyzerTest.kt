package app.lifeos.core.runtime.reasoning

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IdentifiabilityDepthAnalyzerTest {
    @Test
    fun reportsPairwiseSeparationDepthInsteadOfForcingGlobalIdentity() {
        val result = IdentifiabilityDepthAnalyzer().assess(
            candidates = listOf(
                LayeredCandidateObservation("a", mapOf(0 to "same", 1 to "a1", 2 to "a2")),
                LayeredCandidateObservation("b", mapOf(0 to "same", 1 to "b1", 2 to "b2")),
                LayeredCandidateObservation("c", mapOf(0 to "same", 1 to "b1", 2 to "c2")),
            ),
            maximumDepth = 2,
        )

        assertTrue(result.fullyIdentifiable)
        assertEquals(2, result.completeSeparatingDepth)
        assertEquals(
            listOf(1, 1, 2),
            result.pairSeparations.map { it.firstSeparatingDepth },
        )
    }

    @Test
    fun missingSeparationRemainsUnresolvedNotIdentical() {
        val result = IdentifiabilityDepthAnalyzer().assess(
            candidates = listOf(
                LayeredCandidateObservation("a", mapOf(0 to "same", 1 to "same")),
                LayeredCandidateObservation("b", mapOf(0 to "same", 1 to "same")),
            ),
            maximumDepth = 1,
        )

        assertFalse(result.fullyIdentifiable)
        assertNull(result.completeSeparatingDepth)
        assertEquals(1, result.unresolvedPairs.size)
        assertFalse(result.mergeAuthority)
    }
}
