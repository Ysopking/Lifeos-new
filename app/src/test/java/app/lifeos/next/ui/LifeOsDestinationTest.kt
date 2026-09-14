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
                LifeOsDestination.GOALS,
                LifeOsDestination.MEMORY,
                LifeOsDestination.SYSTEM,
            ),
            LifeOsDestination.ordered,
        )
        val primaryKeys = LifeOsDestination.ordered.map { it.key }
        assertEquals(primaryKeys.size, primaryKeys.distinct().size)
        assertTrue(primaryKeys.all { it.isNotBlank() })

        val allKeys = LifeOsDestination.entries.map { it.key }
        assertEquals(allKeys.size, allKeys.distinct().size)
        assertTrue(allKeys.all { it.isNotBlank() })
    }

    @Test
    fun chatIsTheSingleDefaultDestination() {
        assertSame(LifeOsDestination.CHAT, LifeOsDestination.default)
        assertEquals(1, LifeOsDestination.entries.count { it.isDefault })
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
