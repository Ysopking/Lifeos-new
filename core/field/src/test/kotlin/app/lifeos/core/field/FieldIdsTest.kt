package app.lifeos.core.field

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class FieldIdsTest {
    @Test
    fun `stable ids are deterministic and canonical`() {
        val firstDomain = StableFieldIds.domain(" Legal.Interpretation ")
        val secondDomain = StableFieldIds.domain("legal.interpretation")
        assertEquals(firstDomain, secondDomain)

        val first = StableFieldIds.node(firstDomain, "CLAIM", "Norm 1")
        val second = StableFieldIds.node(secondDomain, "claim", "norm 1")
        assertEquals(first, second)
        assertTrue(first.value.startsWith("node:"))
    }

    @Test
    fun `length prefixed hashing distinguishes structural concatenation`() {
        assertNotEquals(
            StableFieldIds.fingerprint("ab", "c"),
            StableFieldIds.fingerprint("a", "bc"),
        )
    }

    @Test
    fun `source revision changes evidence identity`() {
        val domain = StableFieldIds.domain("finance.debt")
        val first = StableFieldIds.evidence(domain, "photon-1", 1, "principal")
        val second = StableFieldIds.evidence(domain, "photon-1", 2, "principal")
        assertNotEquals(first, second)
    }

    @Test
    fun `same logical relation has same id`() {
        val domain = StableFieldIds.domain("conversation.whatsapp")
        val source = StableFieldIds.node(domain, "MESSAGE", "a")
        val target = StableFieldIds.node(domain, "MESSAGE", "b")
        assertEquals(
            StableFieldIds.relation(domain, source, target, "SUPPORTS"),
            StableFieldIds.relation(domain, source, target, "supports"),
        )
    }
}
