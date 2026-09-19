package app.lifeos.core.data.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SegmentRevisionPolicyTest {
    private data class Entry(val key: String, val revision: Long)

    @Test
    fun perKeyHistoryRequiresIndependentContiguousRevisionChains() {
        val policy = SegmentRevisionPolicy<String, Entry>(
            scope = SegmentRevisionScope.PER_KEY,
            keyOf = { it.key },
            revisionOf = { it.revision },
        )
        val valid = listOf(
            Entry("a", 1),
            Entry("b", 1),
            Entry("a", 2),
        )

        policy.validateHistory(valid)

        assertEquals(2L, policy.currentRevision(valid, "a"))
        assertEquals(1L, policy.currentRevision(valid, "b"))
        assertFailsWith<IllegalArgumentException> {
            policy.validateHistory(listOf(Entry("a", 1), Entry("a", 3)))
        }
    }

    @Test
    fun globalHistoryRejectsDuplicateOrGappedRevisionsAcrossKeys() {
        val policy = SegmentRevisionPolicy<String, Entry>(
            scope = SegmentRevisionScope.GLOBAL,
            keyOf = { it.key },
            revisionOf = { it.revision },
        )
        val valid = listOf(
            Entry("a", 1),
            Entry("b", 2),
            Entry("a", 3),
        )

        policy.validateHistory(valid)

        assertEquals(3L, policy.currentRevision(valid, "unused"))
        assertFailsWith<IllegalArgumentException> {
            policy.validateHistory(listOf(Entry("a", 1), Entry("b", 1)))
        }
        assertFailsWith<IllegalArgumentException> {
            policy.validateHistory(listOf(Entry("a", 1), Entry("b", 3)))
        }
    }

    @Test
    fun appendRequiresExactSuccessorRevision() {
        val policy = SegmentRevisionPolicy<String, Entry>(
            scope = SegmentRevisionScope.GLOBAL,
            keyOf = { it.key },
            revisionOf = { it.revision },
        )

        policy.requireAppend(7L, Entry("a", 8L))
        assertFailsWith<IllegalArgumentException> {
            policy.requireAppend(7L, Entry("a", 9L))
        }
    }
}
