package app.lifeos.next

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OutcomeOverviewTest {
    @Test
    fun `unsaved owner input is the next outcome before background work`() {
        val overview = buildOutcomeOverview(
            LifeOsState(
                loading = false,
                draft = "Bitte plane meinen nächsten sinnvollen Schritt",
            )
        )

        assertEquals(OutcomeAction.SAVE_DRAFT, overview.action)
        assertTrue(overview.summary.contains("nächsten sinnvollen Schritt"))
    }

    @Test
    fun `failed bootstrap fails closed and offers recovery`() {
        val overview = buildOutcomeOverview(
            LifeOsState(
                loading = false,
                loadFailed = true,
                error = "store unreadable",
            )
        )

        assertEquals(OutcomeAction.RETRY_LOAD, overview.action)
        assertEquals(OutcomeTone.ATTENTION, overview.tone)
    }

    @Test
    fun `healthy empty state stays simple for the owner`() {
        val overview = buildOutcomeOverview(LifeOsState(loading = false))

        assertEquals(OutcomeAction.NONE, overview.action)
        assertTrue(overview.title.contains("ersten Gedanken"))
    }
}
