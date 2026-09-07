package app.lifeos.core.image

import kotlin.test.Test
import kotlin.test.assertEquals

class MmsiBufferLayoutTest {
    @Test
    fun layoutMatchesForwardShaderStorageBuffers() {
        assertEquals(16, MmsiBufferLayout.ALBEDO_RGBA32F_BYTES_PER_PIXEL)
        assertEquals(16, MmsiBufferLayout.NORMAL_DEPTH_RGBA32F_BYTES_PER_PIXEL)
        assertEquals(4, MmsiBufferLayout.ROUGHNESS_R32F_BYTES_PER_PIXEL)
        assertEquals(16, MmsiBufferLayout.OUTPUT_RGBA32F_BYTES_PER_PIXEL)
        assertEquals(52, MmsiBufferLayout.BYTES_PER_PIXEL)
    }

    @Test
    fun byteCalculationUsesLongArithmetic() {
        assertEquals(16_000_000L, MmsiBufferLayout.bytesForPixels(1_000_000L, 16))
    }
}
