package app.lifeos.core.image

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.util.zip.CRC32
import java.util.zip.InflaterInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeterministicPngEncoderTest {
    @Test
    fun `encoder is byte deterministic and preserves exact RGBA scanlines`() {
        val rgba = byteArrayOf(
            255.toByte(), 0, 0, 255.toByte(),
            0, 255.toByte(), 0, 255.toByte(),
            0, 0, 255.toByte(), 128.toByte(),
            255.toByte(), 255.toByte(), 255.toByte(), 0,
        )
        val image = Rgba8Image(2, 2, rgba)
        val encoder = DeterministicPngEncoder()

        val first = encoder.encode(image)
        val second = encoder.encode(image)

        assertContentEquals(first, second)
        val parsed = parse(first)
        assertEquals(2, parsed.width)
        assertEquals(2, parsed.height)
        assertContentEquals(
            byteArrayOf(
                0,
                255.toByte(), 0, 0, 255.toByte(),
                0, 255.toByte(), 0, 255.toByte(),
                0,
                0, 0, 255.toByte(), 128.toByte(),
                255.toByte(), 255.toByte(), 255.toByte(), 0,
            ),
            parsed.rawScanlines,
        )
    }

    @Test
    fun `rgba model rejects mismatched storage`() {
        val error = runCatching { Rgba8Image(2, 2, ByteArray(15)) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    private data class Parsed(val width: Int, val height: Int, val rawScanlines: ByteArray)

    private fun parse(png: ByteArray): Parsed {
        val signature = png.copyOfRange(0, 8)
        assertContentEquals(
            byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a),
            signature,
        )
        val input = DataInputStream(ByteArrayInputStream(png, 8, png.size - 8))
        var width = 0
        var height = 0
        val idat = ArrayList<Byte>()
        while (input.available() > 0) {
            val length = input.readInt()
            val typeBytes = ByteArray(4).also(input::readFully)
            val data = ByteArray(length).also(input::readFully)
            val expectedCrc = input.readInt().toLong() and 0xffffffffL
            val crc = CRC32().apply { update(typeBytes); update(data) }.value
            assertEquals(expectedCrc, crc)
            when (typeBytes.toString(Charsets.US_ASCII)) {
                "IHDR" -> DataInputStream(ByteArrayInputStream(data)).use {
                    width = it.readInt()
                    height = it.readInt()
                    assertEquals(8, it.readUnsignedByte())
                    assertEquals(6, it.readUnsignedByte())
                }
                "IDAT" -> data.forEach(idat::add)
                "IEND" -> break
            }
        }
        val compressed = ByteArray(idat.size) { idat[it] }
        val raw = InflaterInputStream(ByteArrayInputStream(compressed)).use { it.readBytes() }
        return Parsed(width, height, raw)
    }
}
