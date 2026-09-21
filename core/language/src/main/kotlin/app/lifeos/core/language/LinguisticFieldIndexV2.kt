package app.lifeos.core.language

import kotlin.math.min

data class IndexedLinguisticForm(
    val form: String,
    val conceptId: String,
)

class GermanMorphologyEngine(
    private val fst: GermanMorphologyFst = GermanMorphologyFst(),
) {
    fun candidates(raw: String): Set<String> {
        val value = normalizeFieldText(raw)
        if (value.isBlank()) return emptySet()
        val result = linkedSetOf(value)
        fst.analyze(value).forEach { analysis -> result += analysis.lemma }

        // Productive German inflection endings.
        INFLECTION_SUFFIXES.forEach { suffix ->
            if (value.endsWith(suffix) && value.length - suffix.length >= 3) {
                result += value.dropLast(suffix.length)
            }
        }

        // Common participle patterns: gemacht -> mach, gespeichert -> speicher, gesucht -> such.
        if (value.startsWith("ge") && value.length >= 6) {
            PARTICIPLE_SUFFIXES.forEach { suffix ->
                if (value.endsWith(suffix) && value.length - 2 - suffix.length >= 3) {
                    result += value.substring(2, value.length - suffix.length)
                }
            }
        }

        // Separable verbs: hochladen -> laden, weitermachen -> machen.
        SEPARABLE_PREFIXES.forEach { prefix ->
            if (value.startsWith(prefix) && value.length - prefix.length >= 3) {
                result += value.removePrefix(prefix)
            }
        }

        // Nominalisation / comparison.
        NOMINAL_SUFFIXES.forEach { suffix ->
            if (value.endsWith(suffix) && value.length - suffix.length >= 4) {
                result += value.dropLast(suffix.length)
            }
        }

        return result
    }

    fun affinity(left: String, right: String): Double {
        val leftForms = candidates(left)
        val rightForms = candidates(right)
        if (leftForms.any { it in rightForms }) return 0.98

        var best = 0.0
        leftForms.forEach { a ->
            rightForms.forEach { b ->
                if (a == b) return 0.98
                val prefix = commonPrefix(a, b).toDouble() / maxOf(a.length, b.length).coerceAtLeast(1)
                val containment = when {
                    a.length >= 4 && b.contains(a) -> 0.84
                    b.length >= 4 && a.contains(b) -> 0.84
                    else -> 0.0
                }
                best = maxOf(best, prefix, containment)
            }
        }
        return best.coerceIn(0.0, 1.0)
    }

    private fun commonPrefix(a: String, b: String): Int {
        val limit = min(a.length, b.length)
        var index = 0
        while (index < limit && a[index] == b[index]) index++
        return index
    }

    private companion object {
        val INFLECTION_SUFFIXES = listOf(
            "ern", "est", "end", "ende", "enden", "ender", "endes",
            "en", "em", "er", "es", "e", "st", "t", "n", "s",
        )
        val PARTICIPLE_SUFFIXES = listOf("t", "et", "en")
        val SEPARABLE_PREFIXES = listOf(
            "ab", "an", "auf", "aus", "bei", "ein", "fest", "fort", "her", "hin",
            "hoch", "los", "mit", "nach", "vor", "weg", "weiter", "zu", "zuruck", "zurueck",
        )
        val NOMINAL_SUFFIXES = listOf(
            "ung", "ungen", "heit", "heiten", "keit", "keiten", "schaft", "schaften",
            "isch", "ische", "ischen", "licher", "lich", "bar", "barer",
        )
    }
}

/**
 * Immutable candidate index for the field lexicon.
 *
 * Exact/morphology/prefix/suffix/phonetic/BK-tree typo candidates are gathered before field
 * convergence. The expensive field iterations therefore operate on a small bounded set only.
 */
class LinguisticFieldIndexV2(
    private val lexicon: LinguisticLexicon,
    private val morphology: GermanMorphologyEngine = GermanMorphologyEngine(),
    private val maxCandidates: Int = 12,
) {
    private val forms: List<IndexedLinguisticForm> = lexicon.concepts
        .flatMap { concept ->
            concept.allForms.map { form -> IndexedLinguisticForm(form, concept.id) }
        }
        .distinct()
        .sortedWith(compareBy<IndexedLinguisticForm> { it.form }.thenBy { it.conceptId })

    private val exact: Map<String, List<String>> = forms
        .groupBy({ it.form }, { it.conceptId })
        .mapValues { (_, ids) -> ids.distinct().sorted() }

    private val prefix3: Map<String, List<String>> = forms
        .filter { it.form.length >= 3 }
        .groupBy({ it.form.take(3) }, { it.conceptId })
        .mapValues { (_, ids) -> ids.distinct().sorted() }

    private val suffix3: Map<String, List<String>> = forms
        .filter { it.form.length >= 3 }
        .groupBy({ it.form.takeLast(3) }, { it.conceptId })
        .mapValues { (_, ids) -> ids.distinct().sorted() }

    private val phonetic: Map<String, List<String>> = forms
        .groupBy({ phoneticKey(it.form) }, { it.conceptId })
        .mapValues { (_, ids) -> ids.distinct().sorted() }

    private val typoIndex = BkTreeIndex(
        entries = forms.map { it.form }.distinct(),
        keyOf = { it },
    )

    private val trieRoot = TrieNode().also { root ->
        forms.forEach { indexed ->
            var cursor = root
            indexed.form.forEach { char ->
                cursor = cursor.children.getOrPut(char) { TrieNode() }
            }
            cursor.forms += indexed
        }
    }

    fun candidateConceptIds(token: String): List<String> {
        val normalized = normalizeFieldText(token)
        if (normalized.isBlank()) return emptyList()
        val scored = linkedMapOf<String, Double>()

        fun add(ids: Iterable<String>, score: Double) {
            ids.forEach { id -> scored[id] = maxOf(scored[id] ?: 0.0, score) }
        }

        add(exact[normalized].orEmpty(), 1.0)

        morphology.candidates(normalized).forEach { form ->
            add(exact[form].orEmpty(), if (form == normalized) 1.0 else 0.94)
        }

        if (normalized.length >= 3) {
            add(prefix3[normalized.take(3)].orEmpty(), 0.60)
            add(suffix3[normalized.takeLast(3)].orEmpty(), 0.55)
        }
        add(phonetic[phoneticKey(normalized)].orEmpty(), 0.58)

        // Typo lookup is only attempted when stronger buckets did not already fill the bound.
        if (scored.size < maxCandidates) {
            val maxDistance = when {
                normalized.length <= 4 -> 1
                else -> 2
            }
            typoIndex.search(
                rawQuery = normalized,
                maxDistance = maxDistance,
                limit = maxCandidates,
            ).forEach { match ->
                add(
                    exact[match.value].orEmpty(),
                    0.52 - match.distance * 0.08,
                )
            }
        }

        return scored.entries
            .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
            .take(maxCandidates)
            .map { it.key }
    }

    fun formsStartingAt(
        token: String,
        position: Int,
    ): List<IndexedLinguisticForm> {
        val normalized = normalizeFieldText(token)
        if (position !in normalized.indices) return emptyList()
        var cursor = trieRoot
        val found = mutableListOf<IndexedLinguisticForm>()
        var index = position
        while (index < normalized.length) {
            cursor = cursor.children[normalized[index]] ?: break
            if (cursor.forms.isNotEmpty()) found += cursor.forms
            index++
        }
        return found
            .distinct()
            .sortedWith(
                compareByDescending<IndexedLinguisticForm> { it.form.length }
                    .thenBy { it.conceptId }
            )
            .take(maxCandidates)
    }

    private fun phoneticKey(value: String): String {
        val normalized = normalizeFieldText(value)
        if (normalized.isBlank()) return ""
        val out = StringBuilder()
        var previous: Char? = null
        normalized.forEach { char ->
            val mapped = when (char) {
                in "bpfv" -> '1'
                in "cgjkqsxz" -> '2'
                in "dt" -> '3'
                'l' -> '4'
                in "mn" -> '5'
                'r' -> '6'
                else -> '0'
            }
            if (mapped != '0' && mapped != previous) out.append(mapped)
            previous = mapped
        }
        return normalized.first() + out.toString()
    }

    private class TrieNode {
        val children = linkedMapOf<Char, TrieNode>()
        val forms = mutableListOf<IndexedLinguisticForm>()
    }
}
