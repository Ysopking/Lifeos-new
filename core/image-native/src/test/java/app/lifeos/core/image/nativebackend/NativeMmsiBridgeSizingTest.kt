package app.lifeos.core.image.nativebackend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NativeMmsiBridgeSizingTest {
    @Test
    fun `checked byte count accepts zero and exact positive sizes`() {
        assertEquals(0, NativeMmsiBridge.checkedByteCount(0, 12))
        assertEquals(36, NativeMmsiBridge.checkedByteCount(3, 12))
    }

    @Test
    fun `checked byte count rejects negative pixel count`() {
        assertFailsWith<IllegalArgumentException> {
            NativeMmsiBridge.checkedByteCount(-1, 12)
        }
    }

    @Test
    fun `checked byte count rejects integer overflow before allocation`() {
        assertFailsWith<IllegalArgumentException> {
            NativeMmsiBridge.checkedByteCount(Int.MAX_VALUE, 12)
        }
    }

    @Test
    fun `sync fence ownership can be transferred only once`() {
        val fence = MmsiSyncFence.fromNative(42)!!
        assertEquals(42, fence.take())
        assertFailsWith<IllegalStateException> { fence.take() }
        fence.close()
    }
}
