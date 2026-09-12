package app.lifeos.next

import app.lifeos.core.runtime.life.LifeOsBlock
import app.lifeos.core.runtime.life.LifeOsCompletionReadiness
import app.lifeos.core.runtime.life.ReadinessState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LifeOsReadinessCardTest {
    @Test
    fun uiModelShowsAllBlocksAndCompleteState() {
        val snapshot = LifeOsCompletionReadiness().snapshot(
            LifeOsBlock.entries.associateWith { ReadinessState.READY },
            LifeOsBlock.entries.associateWith { "verified" },
        )
        val model = buildReadinessUiModel(snapshot)
        assertEquals(8, model.rows.size)
        assertTrue(model.title.contains("bereit"))
        assertEquals("8 bereit · 0 eingeschränkt · 0 blockiert", model.summary)
    }
}
