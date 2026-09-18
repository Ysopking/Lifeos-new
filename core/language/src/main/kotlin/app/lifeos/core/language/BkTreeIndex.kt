package app.lifeos.core.language

/**
 * Deterministic bounded BK-tree over normalized strings.
 *
 * Search traverses only metric-compatible child distances and has a hard visited-node budget.
 * It never falls back to a full entry scan.
 */
class BkTreeIndex<T>(
    entries: Collection<T>,
    private val keyOf: (T) -> String,
) {
    private val root: Node<T>? = entries
        .sortedBy { keyOf(it) }
        .fold(null as Node<T>?) { current, entry ->
            if (current == null) Node(entry) else current.also { insert(it, entry) }
        }

    fun search(
        rawQuery: String,
        maxDistance: Int,
        limit: Int,
        maxVisited: Int = DEFAULT_MAX_VISITED,
    ): List<BkTreeMatch<T>> {
        require(maxDistance >= 0)
        require(limit > 0)
        require(maxVisited > 0)
        val query = normalizeFieldText(rawQuery)
        if (query.isBlank()) return emptyList()
        val start = root ?: return emptyList()

        val matches = mutableListOf<BkTreeMatch<T>>()
        val pending = ArrayDeque<Node<T>>()
        pending += start
        var visited = 0

        while (pending.isNotEmpty() && visited < maxVisited) {
            val node = pending.removeFirst()
            visited += 1
            val key = keyOf(node.value)
            val distance = editDistance(query, key)
            if (distance <= maxDistance) {
                matches += BkTreeMatch(node.value, distance)
            }
            val minChild = (distance - maxDistance).coerceAtLeast(0)
            val maxChild = distance + maxDistance
            node.children.entries
                .asSequence()
                .filter { it.key in minChild..maxChild }
                .sortedBy { it.key }
                .forEach { pending += it.value }
        }

        return matches
            .sortedWith(
                compareBy<BkTreeMatch<T>> { it.distance }
                    .thenBy { keyOf(it.value) }
            )
            .take(limit)
    }

    private fun insert(
        root: Node<T>,
        value: T,
    ) {
        var node = root
        while (true) {
            val distance = editDistance(keyOf(node.value), keyOf(value))
            if (distance == 0) {
                return
            }
            val child = node.children[distance]
            if (child == null) {
                node.children[distance] = Node(value)
                return
            }
            node = child
        }
    }

    private class Node<T>(
        val value: T,
        val children: MutableMap<Int, Node<T>> = sortedMapOf(),
    )

    private companion object {
        const val DEFAULT_MAX_VISITED = 256

        fun editDistance(a: String, b: String): Int {
            if (a == b) return 0
            if (a.isEmpty()) return b.length
            if (b.isEmpty()) return a.length
            var previous = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                val current = IntArray(b.length + 1)
                current[0] = i
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    current[j] = minOf(
                        current[j - 1] + 1,
                        previous[j] + 1,
                        previous[j - 1] + cost,
                    )
                }
                previous = current
            }
            return previous[b.length]
        }
    }
}

data class BkTreeMatch<T>(
    val value: T,
    val distance: Int,
)
