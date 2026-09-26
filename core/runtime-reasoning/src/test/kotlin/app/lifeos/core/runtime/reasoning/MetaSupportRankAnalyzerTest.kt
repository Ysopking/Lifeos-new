package app.lifeos.core.runtime.reasoning

import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MetaSupportRankAnalyzerTest {
    private fun vector(vararg entries: Pair<String, Int>) =
        MetaSupportVector(entries.associate { it.first to BigInteger.valueOf(it.second.toLong()) })

    @Test
    fun commonSupportAllowsScalarCompression() {
        val result = MetaSupportRankAnalyzer().assess(
            listOf(
                vector("semantic" to 2, "temporal" to 1),
                vector("semantic" to 2, "temporal" to 1),
            )
        )

        assertEquals(0, result.rank)
        assertEquals(ScalarCompressionDecision.ALLOWED, result.scalarCompression)
    }

    @Test
    fun independentSupportDifferenceForbidsScalarCompression() {
        val result = MetaSupportRankAnalyzer().assess(
            listOf(
                vector("semantic" to 2, "temporal" to 1),
                vector("semantic" to 3, "temporal" to 1),
                vector("semantic" to 2, "temporal" to 2),
            )
        )

        assertEquals(2, result.rank)
        assertEquals(
            ScalarCompressionDecision.FORBIDDEN_MULTI_SUPPORT,
            result.scalarCompression,
        )
    }

    @Test
    fun unspecifiedSupportNeverInventsRank() {
        val result = MetaSupportRankAnalyzer().assess(null)

        assertNull(result.rank)
        assertEquals(
            ScalarCompressionDecision.FORBIDDEN_UNSPECIFIED,
            result.scalarCompression,
        )
    }
}
