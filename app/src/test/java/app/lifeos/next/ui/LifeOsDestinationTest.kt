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
            ),
            LifeOsDestination.ordered,
        )
        val primaryKeys = LifeOsDestination.ordered.map { it.key }
        assertEquals(primaryKeys.size, primaryKeys.distinct().size)
        assertTrue(primaryKeys.all { it.isNotBlank() })
    }

    @Test
    fun lifeosIsTheSingleDefaultDestination() {
        assertSame(LifeOsDestination.CHAT, LifeOsDestination.default)
        assertEquals("LIFEOS", LifeOsDestination.CHAT.label)
        assertEquals(1, LifeOsDestination.entries.count { it.isDefault })
    }

    @Test
    fun restoredKeysResolveAndFormerTechnicalKeysFailSafeToLifeos() {
        assertSame(LifeOsDestination.CHAT, LifeOsDestination.fromKey(null))
        assertSame(LifeOsDestination.CHAT, LifeOsDestination.fromKey("unknown"))
        assertSame(LifeOsDestination.CHAT, LifeOsDestination.fromKey("chat"))
        assertSame(LifeOsDestination.GOALS, LifeOsDestination.fromKey("goals"))
        assertSame(LifeOsDestination.MEMORY, LifeOsDestination.fromKey("memory"))
        assertSame(LifeOsDestination.CHAT, LifeOsDestination.fromKey("system"))
        assertSame(LifeOsDestination.CHAT, LifeOsDestination.fromKey("tools"))
        assertSame(LifeOsDestination.CHAT, LifeOsDestination.fromKey("assets"))
        assertSame(LifeOsDestination.CHAT, LifeOsDestination.fromKey("why"))
    }
}
