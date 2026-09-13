package app.lifeos.next.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LifeOsDestinationTest {
    @Test
    fun destinationsHaveStableUniqueKeysInProductOrder() {
        assertEquals(
            listOf(
                LifeOsDestination.CHAT,
                LifeOsDestination.MEMORY,
                LifeOsDestination.ASSETS,
                LifeOsDestination.GOALS,
                LifeOsDestination.WHY,
                LifeOsDestination.TOOLS,
                LifeOsDestination.SYSTEM,
            ),
            LifeOsDestination.ordered,
        )
        val keys = LifeOsDestination.ordered.map { it.key }
        assertEquals(keys.size, keys.distinct().size)
        assertTrue(keys.all { it.isNotBlank() })
    }

    @Test
    fun chatIsTheSingleDefaultDestination() {
        assertSame(LifeOsDestination.CHAT, LifeOsDestination.default)
        assertEquals(1, LifeOsDestination.ordered.count { it.isDefault })
    }

    @Test
    fun unknownOrMissingRestoredKeyFailsSafeToChat() {
        assertSame(LifeOsDestination.CHAT, LifeOsDestination.fromKey(null))
        assertSame(LifeOsDestination.CHAT, LifeOsDestination.fromKey("unknown"))
        assertSame(LifeOsDestination.MEMORY, LifeOsDestination.fromKey("memory"))
        assertSame(LifeOsDestination.ASSETS, LifeOsDestination.fromKey("assets"))
        assertSame(LifeOsDestination.WHY, LifeOsDestination.fromKey("why"))
        assertSame(LifeOsDestination.TOOLS, LifeOsDestination.fromKey("tools"))
    }
}
