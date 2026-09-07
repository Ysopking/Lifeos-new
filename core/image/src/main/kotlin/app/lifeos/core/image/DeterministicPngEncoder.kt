package app.lifeos.core.image

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/** Pure-JVM/Android PNG encoder. No framework codec or network dependency is required. */
class DeterministicPngEncoder(
    private val compressionLevel: Int = Deflater.BEST_SPEED,
) {
    init { require(compressionLevel in Deflater.NO_COMPRESSION..Deflater.BEST_COMPRESSION) }

    fun encode(image: Rgba8Image): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(PNG_SIGNATURE)
        writeChunk(output, "IHDR", ihdr(image.width, image.height))
        writeChunk(output, "IDAT", deflate(scanlines(image)))
        writeChunk(output, "IEND", ByteArray(0))
        return output.toByteArray()
    }

    private fun ihdr(width: Int, height: Int): ByteArray = ByteArrayOutputStream(13).also { bytes ->
        DataOutputStream(bytes).use { data ->
            data.writeInt(width)
            data.writeInt(height)
            data.writeByte(8) // bit depth
            data.writeByte(6) // truecolor with alpha
            data.writeByte(0) // deflate
            data.writeByte(0) // adaptive filter
            data.writeByte(0) // no interlace
        }
    }.toByteArray()

    private fun scanlines(image: Rgba8Image): ByteArray {
        val rgba = image.copyRgba()
        val rowBytes = Math.multiplyExact(image.width, 4)
        val outputSize = Math.addExact(rgba.size, image.height)
        val output = ByteArray(outputSize)
        var src = 0
        var dst = 0
        repeat(image.height) {
            output[dst++] = 0 // PNG filter type None: deterministic and lossless.
            rgba.copyInto(output, destinationOffset = dst, startIndex = src, endIndex = src + rowBytes)
            src += rowBytes
            dst += rowBytes
        }
        return output
    }

    private fun deflate(raw: ByteArray): ByteArray {
        val bytes = ByteArrayOutputStream()
        val deflater = Deflater(compressionLevel, false)
        try {
            DeflaterOutputStream(bytes, deflater).use { it.write(raw) }
        } finally {
            deflater.end()
        }
        return bytes.toByteArray()
    }

    private fun writeChunk(output: ByteArrayOutputStream, type: String, data: ByteArray) {
        require(type.length == 4 && type.all { it.code in 32..126 })
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        val crc = CRC32().apply {
            update(typeBytes)
            update(data)
        }
        DataOutputStream(output).apply {
            writeInt(data.size)
            write(typeBytes)
            write(data)
            writeInt(crc.value.toInt())
        }
    }

    private companion object {
        val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        )
    }
}
