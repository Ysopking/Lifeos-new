package app.lifeos.core.runtime.reasoning

import app.lifeos.core.field.StableFieldIds
import java.math.BigInteger

data class MetaSupportVector(
    val coordinates: Map<String, BigInteger>,
) {
    init {
        require(coordinates.keys.none { it.isBlank() })
    }
}

enum class SupportKnowledge {
    EXPLICIT,
    UNSPECIFIED,
}

enum class ScalarCompressionDecision {
    ALLOWED,
    FORBIDDEN_MULTI_SUPPORT,
    FORBIDDEN_UNSPECIFIED,
}

data class MetaSupportRankAssessment(
    val rank: Int?,
    val supportKnowledge: SupportKnowledge,
    val scalarCompression: ScalarCompressionDecision,
    val dimensions: List<String>,
    val fingerprint: String,
) {
    init {
        require(rank == null || rank >= 0)
        when (supportKnowledge) {
            SupportKnowledge.UNSPECIFIED -> {
                require(rank == null)
                require(scalarCompression == ScalarCompressionDecision.FORBIDDEN_UNSPECIFIED)
            }
            SupportKnowledge.EXPLICIT -> {
                require(rank != null)
                require(
                    scalarCompression ==
                        if (rank == 0) {
                            ScalarCompressionDecision.ALLOWED
                        } else {
                            ScalarCompressionDecision.FORBIDDEN_MULTI_SUPPORT
                        }
                )
            }
        }
    }
}

class MetaSupportRankAnalyzer {
    fun assess(
        supports: Collection<MetaSupportVector>?,
    ): MetaSupportRankAssessment {
        if (supports == null) {
            return MetaSupportRankAssessment(
                rank = null,
                supportKnowledge = SupportKnowledge.UNSPECIFIED,
                scalarCompression = ScalarCompressionDecision.FORBIDDEN_UNSPECIFIED,
                dimensions = emptyList(),
                fingerprint = StableFieldIds.fingerprint(
                    "meta-support-rank/v1",
                    SupportKnowledge.UNSPECIFIED.name,
                ),
            )
        }
        require(supports.isNotEmpty()) {
            "Explicit support assessment requires at least one vector"
        }
        val canonical = supports.toList()
        val dimensions = canonical
            .flatMap { it.coordinates.keys }
            .distinct()
            .sorted()
        val reference = canonical.first()
        val differenceRows = canonical.drop(1).map { vector ->
            dimensions.map { dimension ->
                vector.coordinates.getOrDefault(dimension, BigInteger.ZERO) -
                    reference.coordinates.getOrDefault(dimension, BigInteger.ZERO)
            }
        }
        val rank = ExactIntegerMatrixRank.compute(differenceRows)
        return MetaSupportRankAssessment(
            rank = rank,
            supportKnowledge = SupportKnowledge.EXPLICIT,
            scalarCompression =
                if (rank == 0) {
                    ScalarCompressionDecision.ALLOWED
                } else {
                    ScalarCompressionDecision.FORBIDDEN_MULTI_SUPPORT
                },
            dimensions = dimensions,
            fingerprint = StableFieldIds.fingerprint(
                "meta-support-rank/v1",
                SupportKnowledge.EXPLICIT.name,
                rank.toString(),
                *dimensions.flatMap { dimension ->
                    listOf(
                        "dimension:$dimension",
                        *canonical.map {
                            it.coordinates.getOrDefault(dimension, BigInteger.ZERO).toString()
                        }.toTypedArray(),
                    )
                }.toTypedArray(),
            ),
        )
    }
}

internal object ExactIntegerMatrixRank {
    fun compute(rows: List<List<BigInteger>>): Int {
        if (rows.isEmpty()) return 0
        val columns = rows.maxOfOrNull { it.size } ?: 0
        if (columns == 0) return 0
        val matrix = rows.map { row ->
            MutableList(columns) { column ->
                row.getOrElse(column) { BigInteger.ZERO }
            }
        }.toMutableList()

        var pivotRow = 0
        for (column in 0 until columns) {
            val selected = (pivotRow until matrix.size)
                .firstOrNull { matrix[it][column] != BigInteger.ZERO }
                ?: continue
            if (selected != pivotRow) {
                val swap = matrix[pivotRow]
                matrix[pivotRow] = matrix[selected]
                matrix[selected] = swap
            }
            val pivot = matrix[pivotRow][column]
            for (rowIndex in pivotRow + 1 until matrix.size) {
                val factor = matrix[rowIndex][column]
                if (factor == BigInteger.ZERO) continue
                for (c in column until columns) {
                    matrix[rowIndex][c] =
                        pivot * matrix[rowIndex][c] -
                            factor * matrix[pivotRow][c]
                }
                normalizeRow(matrix[rowIndex], column)
            }
            pivotRow += 1
            if (pivotRow == matrix.size) break
        }
        return pivotRow
    }

    private fun normalizeRow(
        row: MutableList<BigInteger>,
        fromColumn: Int,
    ) {
        val gcd = row.drop(fromColumn)
            .map(BigInteger::abs)
            .filter { it != BigInteger.ZERO }
            .fold(BigInteger.ZERO) { acc, value ->
                if (acc == BigInteger.ZERO) value else acc.gcd(value)
            }
        if (gcd > BigInteger.ONE) {
            for (index in fromColumn until row.size) {
                row[index] = row[index] / gcd
            }
        }
    }
}
