package app.lifeos.core.language

data class MorphAnalysis(
    val surface: String,
    val lemma: String,
    val features: Set<String>,
    val confidence: Double,
) {
    init {
        require(surface.isNotBlank())
        require(lemma.isNotBlank())
        require(confidence.isFinite() && confidence in 0.0..1.0)
    }
}

class GermanMorphologyFst {
    fun analyze(raw: String): List<MorphAnalysis> {
        val normalized = normalizeFieldText(raw)
        if (normalized.isBlank()) return emptyList()
        val result = linkedMapOf<String, MorphAnalysis>()

        fun add(lemma: String, confidence: Double, vararg features: String) {
            if (lemma.length < 2) return
            val candidate = MorphAnalysis(normalized, lemma, features.toSet(), confidence)
            val previous = result[lemma]
            if (previous == null || candidate.confidence > previous.confidence) {
                result[lemma] = candidate
            }
        }

        add(normalized, 1.0, "surface")
        IRREGULAR[normalized]?.let { add(it, 0.99, "irregular") }

        INFLECTIONS.forEach { suffix ->
            if (normalized.endsWith(suffix) && normalized.length - suffix.length >= 3) {
                add(normalized.dropLast(suffix.length), 0.88, "inflection:" + suffix)
            }
        }

        if (normalized.startsWith("ge") && normalized.length >= 6) {
            PARTICIPLE_ENDINGS.forEach { suffix ->
                if (normalized.endsWith(suffix) && normalized.length > suffix.length + 4) {
                    add(
                        normalized.removePrefix("ge").dropLast(suffix.length),
                        0.91,
                        "participle:" + suffix,
                    )
                }
            }
        }

        SEPARABLE_PREFIXES.forEach { prefix ->
            if (normalized.startsWith(prefix) && normalized.length - prefix.length >= 3) {
                add(normalized.removePrefix(prefix), 0.86, "separable-prefix:" + prefix)
            }
        }

        return result.values.sortedWith(
            compareByDescending<MorphAnalysis> { it.confidence }.thenBy { it.lemma }
        )
    }

    private companion object {
        val INFLECTIONS = listOf(
            "est", "end", "ende", "enden", "ender", "endes",
            "en", "em", "er", "es", "e", "st", "t", "n", "s",
        )
        val PARTICIPLE_ENDINGS = listOf("t", "et", "en")
        val SEPARABLE_PREFIXES = listOf(
            "ab", "an", "auf", "aus", "bei", "ein", "fest", "fort", "her", "hin",
            "hoch", "los", "mit", "nach", "vor", "weg", "weiter", "zu", "zuruck", "zurueck",
        )
        val IRREGULAR = mapOf(
            "bin" to "sein", "bist" to "sein", "ist" to "sein", "sind" to "sein", "war" to "sein",
            "hat" to "haben", "hatte" to "haben", "ging" to "gehen", "kam" to "kommen",
            "wurde" to "werden", "wuerde" to "werden",
        )
    }
}
