package app.lifeos.core.language

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SemanticSearchQueryPlannerTest {
    @Test
    fun searchQueryIsDerivedFromLifeosSemanticsInsteadOfRawDirectiveWording() {
        val result = LanguageUnderstandingEngine().understand(
            "Suche nach Jobcenter Bescheid Widerspruchsfrist"
        )
        val plan = SemanticSearchQueryPlanner().plan(result.goal)
        val normalized = SemanticSearchTerms.tokens(plan.primaryQuery)

        assertFalse("suche" in normalized)
        assertTrue("jobcenter" in normalized)
        assertTrue("bescheid" in normalized)
        assertTrue(plan.contextTerms.isNotEmpty())
    }

    @Test
    fun germanInflectionProducesSharedExpandedSearchForms() {
        val plural = SemanticSearchTerms.expandedTokens("Widerspruchsfristen")
        val singular = SemanticSearchTerms.expandedTokens("Widerspruchsfrist")
        assertTrue(plural.intersect(singular).isNotEmpty())
    }
}
