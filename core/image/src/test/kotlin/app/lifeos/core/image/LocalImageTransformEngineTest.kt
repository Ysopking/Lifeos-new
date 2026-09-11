package app.lifeos.core.image

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LocalImageTransformEngineTest {
    private val engine = LocalImageTransformEngine()

    @Test
    fun `brightness transform is deterministic preserves alpha and input`() {
        val input = Rgba8Image(
            width = 2,
            height = 1,
            rgba = byteArrayOf(
                10, 20, 30, 40,
                100.toByte(), 120.toByte(), 140.toByte(), 160.toByte(),
            ),
        )
        val original = input.copyRgba()

        val first = engine.transform(input, listOf(LocalImageTransformOperation.BRIGHTER))
        val second = engine.transform(input, listOf(LocalImageTransformOperation.BRIGHTER))

        assertContentEquals(original, input.copyRgba())
        assertContentEquals(first.copyRgba(), second.copyRgba())
        assertEquals(40, first.pixel(0)[3])
        assertEquals(160, first.pixel(1)[3])
        assertTrue(first.pixel(0)[0] > input.pixel(0)[0])
        assertTrue(first.pixel(1)[2] > input.pixel(1)[2])
    }

    @Test
    fun `warmer and grayscale apply in requested order`() {
        val input = Rgba8Image(1, 1, byteArrayOf(80, 100, 120, 77))

        val warmGray = engine.transform(
            input,
            listOf(LocalImageTransformOperation.WARMER, LocalImageTransformOperation.GRAYSCALE),
        )
        val grayWarm = engine.transform(
            input,
            listOf(LocalImageTransformOperation.GRAYSCALE, LocalImageTransformOperation.WARMER),
        )

        val first = warmGray.pixel(0)
        val second = grayWarm.pixel(0)
        assertEquals(first[0], first[1])
        assertEquals(first[1], first[2])
        assertTrue(second[0] > second[2])
        assertEquals(77, first[3])
        assertEquals(77, second[3])
    }

    @Test
    fun `sharpen changes interior only and preserves alpha`() {
        val rgba = ByteArray(3 * 3 * 4)
        repeat(9) { index ->
            val base = index * 4
            val value = if (index == 4) 100 else 20
            rgba[base] = value.toByte()
            rgba[base + 1] = value.toByte()
            rgba[base + 2] = value.toByte()
            rgba[base + 3] = 200.toByte()
        }
        val input = Rgba8Image(3, 3, rgba)

        val output = engine.transform(input, listOf(LocalImageTransformOperation.SHARPER))

        assertEquals(20, output.pixel(0)[0])
        assertEquals(255, output.pixel(4)[0])
        repeat(9) { assertEquals(200, output.pixel(it)[3]) }
    }

    @Test
    fun `duplicate or empty operations are rejected`() {
        val image = Rgba8Image(1, 1, byteArrayOf(1, 2, 3, 4))
        assertFailsWith<IllegalArgumentException> { engine.transform(image, emptyList()) }
        assertFailsWith<IllegalArgumentException> {
            engine.transform(
                image,
                listOf(LocalImageTransformOperation.DARKER, LocalImageTransformOperation.DARKER),
            )
        }
    }
}
