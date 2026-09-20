package app.lifeos.core.runtime.personal

import app.lifeos.core.language.SemanticSearchTerms
import app.lifeos.core.model.StableCognitiveIds

/**
 * Deterministic description of one personal-corpus lookup.
 *
 * It is separate from repository execution so benchmarks, traces and later performance evidence can
 * bind to the exact normalized query shape without retaining the raw user utterance.
 */
data class PersonalCorpusQueryPlan(
    val terms: List<String>,
    val requiredTags: Set<String>,
    val termTags: Set<String>,
    val retrievalLimit: Int,
    val fingerprint: String,
) {
    init {
        require(terms.isNotEmpty())
        require(terms.none { it.isBlank() })
        require(terms.size == terms.distinct().size)
        require(requiredTags.isNotEmpty())
        require(requiredTags.none { it.isBlank() })
        require(termTags.isNotEmpty())
        require(termTags.none { it.isBlank() })
        require(retrievalLimit > 0)
        require(fingerprint.matches(Regex("[0-9a-f]{64}")))
    }

    companion object {
        fun create(
            query: String,
            ownerOnly: Boolean,
            maxTerms: Int,
            perTermLimit: Int,
            maxCandidates: Int,
        ): PersonalCorpusQueryPlan? {
            require(maxTerms > 0)
            require(perTermLimit > 0)
            require(maxCandidates > 0)

            val terms = SemanticSearchTerms.tokens(query)
                .asSequence()
                .filter(String::isNotBlank)
                .distinct()
                .take(maxTerms)
                .toList()
            if (terms.isEmpty()) return null

            val requiredTags = buildSet {
                add("corpus:archive")
                if (ownerOnly) add("speaker:owner")
            }
            val termTags = terms.mapTo(linkedSetOf()) { term ->
                "corpus-term:$term"
            }
            val retrievalLimit = minOf(
                maxCandidates,
                perTermLimit * terms.size,
            )
            val fingerprint = StableCognitiveIds.fingerprint(
                "personal-corpus-query-plan/v1",
                terms.joinToString("\u001f"),
                requiredTags.sorted().joinToString("\u001f"),
                termTags.sorted().joinToString("\u001f"),
                retrievalLimit.toString(),
            )

            return PersonalCorpusQueryPlan(
                terms = terms,
                requiredTags = requiredTags,
                termTags = termTags,
                retrievalLimit = retrievalLimit,
                fingerprint = fingerprint,
            )
        }
    }
}
