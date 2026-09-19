package app.lifeos.core.runtime.process

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class NonOwningRuntimeSlotTest {
    @Test
    fun reinstallReplacesProcessInstanceAndClearDropsAllAuthority() {
        val slot = NonOwningRuntimeSlot<Any>("test runtime")
        val first = Any()
        val second = Any()

        assertNull(slot.currentOrNull())
        slot.install(first)
        assertSame(first, slot.requireCurrent())

        slot.install(second)
        assertSame(second, slot.requireCurrent())

        slot.clear()
        assertNull(slot.currentOrNull())
        assertFailsWith<IllegalArgumentException> {
            slot.requireCurrent()
        }
    }
}
