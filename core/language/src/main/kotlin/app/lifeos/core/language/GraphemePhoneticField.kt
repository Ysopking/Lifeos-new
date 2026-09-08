package app.lifeos.core.language

import kotlin.math.max

/** Character-level attraction/repulsion trace used by the lexical field. */
enum class GraphemeBondKind { MATCH, SUBSTITUTION, INSERTION, DELETION }

data class GraphemeFieldBond(
    val observedIndex: Int?,
    val expectedIndex: Int?,
    val observed: Char?,
    val expected: Char?,
    val force: Double,
    val kind: GraphemeBondKind,
) {
    init { require(force.isFinite()) }
}

data class GraphemeFieldTrace(
    val observed: String,
    val expected: String,
    val orthographicAffinity: Double,
    val phoneticAffinity: Double,
    val bonds: List<GraphemeFieldBond>,
) {
    init {
        require(orthographicAffinity in 0.0..1.0)
        require(phoneticAffinity in 0.0..1.0)
    }
}

/**
 * Deterministic text-to-phonetic signature. This is deliberately not an acoustic recognizer;
 * a future microphone/phoneme frontend can feed the same PHONEME field directly.
 */
class DeterministicPhoneticField {
    fun signature(value: String): String {
        val normalized = normalizePhoneticText(value)
        if (normalized.isBlank()) return ""
        val out = StringBuilder()
        var index = 0
        while (index < normalized.length) {
            val tail = normalized.substring(index)
            val code = when {
                tail.startsWith("sch") -> "S" to 3
                tail.startsWith("ch") -> "X" to 2
                tail.startsWith("ph") -> "F" to 2
                tail.startsWith("qu") -> "KV" to 2
                else -> when (val c = normalized[index]) {
                    'a', 'e', 'i', 'o', 'u', 'y' -> "A" to 1
                    'b', 'p' -> "P" to 1
                    'd', 't' -> "T" to 1
                    'f', 'v', 'w' -> "F" to 1
                    'g', 'k', 'q', 'c' -> "K" to 1
                    'l' -> "L" to 1
                    'm', 'n' -> "N" to 1
                    'r' -> "R" to 1
                    's', 'z' -> "S" to 1
                    'x' -> "KS" to 1
                    'h' -> "" to 1
                    else -> c.toString().uppercase() to 1
                }
            }
            code.first.forEach { symbol ->
                if (out.isEmpty() || out.last() != symbol) out.append(symbol)
            }
            index += code.second
        }
        return out.toString()
    }

    fun affinity(left: String, right: String): Double {
        val a = signature(left)
        val b = signature(right)
        if (a == b && a.isNotEmpty()) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val distance = levenshtein(a, b)
        return (1.0 - distance.toDouble() / max(a.length, b.length).toDouble()).coerceIn(0.0, 1.0)
    }

    private fun normalizePhoneticText(value: String): String = value
        .lowercase()
        .replace("ß", "ss")
        .replace('ä', 'a')
        .replace('ö', 'o')
        .replace('ü', 'u')
        .replace(Regex("[^a-z]+"), "")
}

class GraphemeFieldEngine(
    private val phoneticField: DeterministicPhoneticField = DeterministicPhoneticField(),
) {
    fun compare(observedRaw: String, expectedRaw: String): GraphemeFieldTrace {
        val observed = normalizeFieldText(observedRaw)
        val expected = normalizeFieldText(expectedRaw)
        if (observed.isEmpty() || expected.isEmpty()) {
            return GraphemeFieldTrace(observed, expected, 0.0, phoneticField.affinity(observedRaw, expectedRaw), emptyList())
        }

        val rows = observed.length + 1
        val cols = expected.length + 1
        val cost = Array(rows) { IntArray(cols) }
        for (i in 0 until rows) cost[i][0] = i
        for (j in 0 until cols) cost[0][j] = j
        for (i in 1 until rows) {
            for (j in 1 until cols) {
                val substitution = cost[i - 1][j - 1] + if (observed[i - 1] == expected[j - 1]) 0 else 1
                cost[i][j] = minOf(cost[i - 1][j] + 1, cost[i][j - 1] + 1, substitution)
            }
        }

        val bonds = mutableListOf<GraphemeFieldBond>()
        var i = observed.length
        var j = expected.length
        while (i > 0 || j > 0) {
            when {
                i > 0 && j > 0 && cost[i][j] == cost[i - 1][j - 1] + if (observed[i - 1] == expected[j - 1]) 0 else 1 -> {
                    val same = observed[i - 1] == expected[j - 1]
                    bonds += GraphemeFieldBond(
                        observedIndex = i - 1,
                        expectedIndex = j - 1,
                        observed = observed[i - 1],
                        expected = expected[j - 1],
                        force = if (same) 1.0 else -0.35,
                        kind = if (same) GraphemeBondKind.MATCH else GraphemeBondKind.SUBSTITUTION,
                    )
                    i--; j--
                }
                i > 0 && cost[i][j] == cost[i - 1][j] + 1 -> {
                    bonds += GraphemeFieldBond(i - 1, null, observed[i - 1], null, -0.30, GraphemeBondKind.DELETION)
                    i--
                }
                else -> {
                    bonds += GraphemeFieldBond(null, j - 1, null, expected[j - 1], -0.30, GraphemeBondKind.INSERTION)
                    j--
                }
            }
        }
        bonds.reverse()

        val editSimilarity = 1.0 - cost[observed.length][expected.length].toDouble() / max(observed.length, expected.length).toDouble()
        val positiveBondDensity = bonds.sumOf { if (it.force > 0.0) it.force else 0.0 } / max(observed.length, expected.length).toDouble()
        val orthographic = (editSimilarity * 0.78 + positiveBondDensity.coerceIn(0.0, 1.0) * 0.22).coerceIn(0.0, 1.0)
        return GraphemeFieldTrace(
            observed = observed,
            expected = expected,
            orthographicAffinity = orthographic,
            phoneticAffinity = phoneticField.affinity(observedRaw, expectedRaw),
            bonds = bonds,
        )
    }
}

private fun levenshtein(a: String, b: String): Int {
    var previous = IntArray(b.length + 1) { it }
    var current = IntArray(b.length + 1)
    for (i in 1..a.length) {
        current[0] = i
        for (j in 1..b.length) {
            current[j] = minOf(
                current[j - 1] + 1,
                previous[j] + 1,
                previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1,
            )
        }
        val swap = previous
        previous = current
        current = swap
    }
    return previous[b.length]
}
