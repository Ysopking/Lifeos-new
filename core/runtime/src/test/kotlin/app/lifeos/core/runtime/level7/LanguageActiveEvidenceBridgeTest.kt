package app.lifeos.core.runtime.level7

import app.lifeos.core.language.LanguageUnderstandingEngine
import app.lifeos.core.runtime.deepsearch.DeepSearchBudget
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LanguageActiveEvidenceBridgeTest {
    @Test
    fun `explicit search selects bounded local and external evidence when available`() {
        val goal = LanguageUnderstandingEngine()
            .understand("Suche nach Jobcenter Bescheid Widerspruchsfrist")
            .goal

        val plan = LanguageActiveEvidenceBridge().plan(
            goal = goal,
            sourceCycleId = "cycle-1",
            budget = DeepSearchBudget(),
            externalAvailable = true,
        )

        assertTrue(plan.selected(EvidenceActionKind.LOCAL_RETRIEVAL))
        assertTrue(plan.selected(EvidenceActionKind.DEEP_SEARCH))
        assertTrue(plan.actions.all { !it.executionAuthority })
        assertTrue(plan.actions.all { !it.currentCycleWorldMutationAllowed })
    }

    @Test
    fun `without external source planner cannot invent Web authority`() {
        val goal = LanguageUnderstandingEngine().understand("Suche nach Bescheid").goal
        val plan = LanguageActiveEvidenceBridge().plan(
            goal = goal,
            sourceCycleId = "cycle-2",
            budget = DeepSearchBudget(),
            externalAvailable = false,
        )

        assertTrue(plan.selected(EvidenceActionKind.LOCAL_RETRIEVAL))
        assertFalse(plan.selected(EvidenceActionKind.DEEP_SEARCH))
    }
}
