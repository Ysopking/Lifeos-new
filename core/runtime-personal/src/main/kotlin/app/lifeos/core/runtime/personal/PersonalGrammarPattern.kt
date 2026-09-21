package app.lifeos.core.runtime.personal

import app.lifeos.core.language.IntentType
import app.lifeos.core.language.SemanticSearchTerms

data class PersonalGrammarPattern(
    val tokens: List<String>,
    val intent: IntentType,
    val support: Int,
    val conversations: Int,
) {
    init {
        require(tokens.size in 2..6)
        require(support > 0)
        require(conversations > 0)
        require(intent in SAFE_GRAMMAR_INTENTS)
    }

    val normalized: String = tokens.joinToString(" ") {
        SemanticSearchTerms.normalizeToken(it)
    }

    companion object {
        val SAFE_GRAMMAR_INTENTS = setOf(
            IntentType.SEARCH,
            IntentType.CONTINUE,
            IntentType.QUERY,
            IntentType.BUILD_OR_IMPLEMENT,
        )
    }
}

data class PersonalGrammarProposal(
    val surface: String,
    val intent: IntentType,
) {
    init {
        require(surface.isNotBlank())
        require(intent in PersonalGrammarPattern.SAFE_GRAMMAR_INTENTS)
        require(SemanticSearchTerms.tokens(surface).size in 2..6)
    }
}

class PersonalGrammarPatternMiner {
    fun propose(
        utterance: String,
        intent: IntentType,
        knownForms: Set<String>,
    ): List<PersonalGrammarProposal> {
        if (intent !in PersonalGrammarPattern.SAFE_GRAMMAR_INTENTS) return emptyList()
        val tokens = SemanticSearchTerms.tokens(utterance)
        if (tokens.size !in 2..6) return emptyList()
        if (tokens.all { it in knownForms }) return emptyList()
        val surface = tokens.joinToString(" ")
        return listOf(PersonalGrammarProposal(surface, intent))
    }
}
