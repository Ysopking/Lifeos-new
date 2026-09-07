package app.lifeos.core.image

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
    fun compactSpectralLayoutUsesTwoVec4sInsteadOfThirtyOneBands() {
        assertEquals(32, MmsiBufferLayout.SPECTRAL_COEFFICIENTS_VEC4X2_BYTES_PER_PIXEL)
        assertEquals(68, MmsiBufferLayout.SPECTRAL_RENDER_BYTES_PER_PIXEL)
        assertEquals(84, MmsiBufferLayout.SPECTRAL_PHASE2_PEAK_BYTES_PER_PIXEL)
        assertTrue(MmsiBufferLayout.SPECTRAL_RENDER_BYTES_PER_PIXEL < 31 * Float.SIZE_BYTES)
    }

    @Test
    fun spectralTilePlannerRemainsInsideNinetySixMiBBudget() {
        val planner = MmsiTilePlanner(
            bytesPerPixel = MmsiBufferLayout.SPECTRAL_PHASE2_PEAK_BYTES_PER_PIXEL,
        )
        val plan = planner.plan(3840, 2160)

        assertTrue(plan.estimatedWorkingSetBytes <= 96L * 1024L * 1024L)
        assertEquals(3840L * 2160L, plan.tiles.sumOf { it.width.toLong() * it.height.toLong() })
    }

    @Test
    fun byteCalculationUsesLongArithmetic() {
        assertEquals(16_000_000L, MmsiBufferLayout.bytesForPixels(1_000_000L, 16))
    }
}
