package app.lifeos.core.runtime.reasoning

import app.lifeos.core.field.StableFieldIds

internal const val PREDICTIVE_PROBABILITY_SCALE = 1_000_000L

/**
 * Exact discrete future law used by the B519 predictive quotient.
 *
 * A future law is a distribution over outcome ids. It does not identify or authorize one future
 * event. Zero-mass outcomes are rejected so semantically identical laws have one representation.
 */
data class DiscreteFutureLaw private constructor(
    val probabilityMicros: Map<String, Long>,
    val fingerprint: String,
) {
    init {
        require(probabilityMicros.isNotEmpty()) {
            "Predictive future law requires at least one outcome"
        }
        require(probabilityMicros.keys.none { it.isBlank() }) {
            "Predictive outcome ids must not be blank"
        }
        require(probabilityMicros.values.all { it > 0L }) {
            "Predictive future law stores only positive-mass outcomes"
        }
        require(probabilityMicros.values.sum() == PREDICTIVE_PROBABILITY_SCALE) {
            "Predictive future law must sum to the fixed probability scale"
        }
        require(probabilityMicros.keys.toList() == probabilityMicros.keys.sorted()) {
            "Predictive future law outcomes must be canonical"
        }
        require(fingerprint == expectedFingerprint(probabilityMicros)) {
            "Predictive future law fingerprint does not match distribution"
        }
    }

    /** A probability law does not claim which individual event will occur. */
    val singleEventAuthority: Boolean
        get() = false

    companion object {
        fun create(
            probabilityMicros: Map<String, Long>,
        ): DiscreteFutureLaw {
            require(probabilityMicros.isNotEmpty())
            require(probabilityMicros.keys.none { it.isBlank() })
            require(probabilityMicros.values.all { it > 0L })
            val canonical = probabilityMicros.toSortedMap()
            require(canonical.values.sum() == PREDICTIVE_PROBABILITY_SCALE) {
                "Predictive future law must sum to $PREDICTIVE_PROBABILITY_SCALE"
            }
            return DiscreteFutureLaw(
                probabilityMicros = canonical,
                fingerprint = expectedFingerprint(canonical),
            )
        }

        private fun expectedFingerprint(
            probabilityMicros: Map<String, Long>,
        ): String = StableFieldIds.fingerprint(
            "discrete-future-law/v1",
            *probabilityMicros.map { (outcomeId, probability) ->
                "$outcomeId:$probability"
            }.toTypedArray(),
        )
    }
}

data class PredictiveHistory(
    val realizationProfileFingerprint: String,
    val historyFingerprint: String,
    val futureLaw: DiscreteFutureLaw,
) {
    init {
        require(realizationProfileFingerprint.isNotBlank())
        require(historyFingerprint.isNotBlank())
    }
}

data class PredictiveStateClass(
    val id: String,
    val realizationProfileFingerprint: String,
    val memberHistoryFingerprints: List<String>,
    val futureLaw: DiscreteFutureLaw,
) {
    init {
        require(realizationProfileFingerprint.isNotBlank())
        require(memberHistoryFingerprints.isNotEmpty())
        require(
            memberHistoryFingerprints ==
                memberHistoryFingerprints.distinct().sorted()
        ) {
            "Predictive-state members must be unique and canonical"
        }
        require(
            id == expectedId(
                realizationProfileFingerprint = realizationProfileFingerprint,
                futureLawFingerprint = futureLaw.fingerprint,
            )
        ) {
            "Predictive-state id must bind profile and future law"
        }
    }

    val truthAuthority: Boolean
        get() = false

    val executionAuthority: Boolean
        get() = false

    companion object {
        fun create(
            realizationProfileFingerprint: String,
            memberHistoryFingerprints: Collection<String>,
            futureLaw: DiscreteFutureLaw,
        ): PredictiveStateClass {
            val members = memberHistoryFingerprints.distinct().sorted()
            require(realizationProfileFingerprint.isNotBlank())
            require(members.isNotEmpty())
            return PredictiveStateClass(
                id = expectedId(realizationProfileFingerprint, futureLaw.fingerprint),
                realizationProfileFingerprint = realizationProfileFingerprint,
                memberHistoryFingerprints = members,
                futureLaw = futureLaw,
            )
        }

        private fun expectedId(
            realizationProfileFingerprint: String,
            futureLawFingerprint: String,
        ): String = "predictive-state:" + StableFieldIds.fingerprint(
            "predictive-state-class/v1",
            realizationProfileFingerprint,
            futureLawFingerprint,
        )
    }
}

/**
 * Exact M2 quotient: histories are equivalent iff their complete represented future laws match.
 *
 * This is intentionally stricter than finite-data inference. B520 handles empirical
 * MERGE/SPLIT/UNRESOLVED classification.
 */
class PredictiveStateQuotient {
    fun exact(
        histories: Collection<PredictiveHistory>,
    ): List<PredictiveStateClass> {
        require(histories.isNotEmpty()) {
            "Predictive quotient requires histories"
        }
        val canonical = histories
            .distinctBy { it.historyFingerprint }
            .sortedBy { it.historyFingerprint }
        require(canonical.size == histories.size) {
            "Predictive quotient history fingerprints must be unique"
        }

        val profile = canonical.first().realizationProfileFingerprint
        require(canonical.all { it.realizationProfileFingerprint == profile }) {
            "Predictive quotient histories must use one frozen realization profile"
        }

        return canonical
            .groupBy { it.futureLaw.fingerprint }
            .toSortedMap()
            .map { (_, members) ->
                val law = members.first().futureLaw
                require(members.all { it.futureLaw == law }) {
                    "Predictive future-law fingerprint collision"
                }
                PredictiveStateClass.create(
                    realizationProfileFingerprint = profile,
                    memberHistoryFingerprints =
                        members.map(PredictiveHistory::historyFingerprint),
                    futureLaw = law,
                )
            }
            .sortedBy { it.id }
    }
}
