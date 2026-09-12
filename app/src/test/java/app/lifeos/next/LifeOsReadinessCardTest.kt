package app.lifeos.next

import app.lifeos.core.runtime.life.LifeOsBlock
import app.lifeos.core.runtime.life.LifeOsCompletionReadiness
import app.lifeos.core.runtime.life.ReadinessState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LifeOsReadinessCardTest {
    @Test
    fun uiModelShowsAllRuntimeBlocksAndCompleteState() {
        val snapshot = LifeOsCompletionReadiness().snapshot(
            LifeOsBlock.entries.associateWith { ReadinessState.READY },
            LifeOsBlock.entries.associateWith { "verified" },
        )
        val model = buildReadinessUiModel(snapshot)
        assertEquals(16, model.rows.size)
        assertEquals("LIFEOS Runtime A–P bereit", model.title)
        assertEquals("16 bereit · 0 eingeschränkt · 0 blockiert", model.summary)
    }

    @Test
    fun uiModelDoesNotClaimProductGold() {
        val snapshot = LifeOsCompletionReadiness().snapshot(
            LifeOsBlock.entries.associateWith { ReadinessState.READY },
            LifeOsBlock.entries.associateWith { "runtime-verified" },
        )
        val model = buildReadinessUiModel(snapshot)

        assertTrue("gold" !in model.title.lowercase())
        assertTrue("gold" !in model.summary.lowercase())
    }
}
