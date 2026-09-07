package app.lifeos.core.image

class Rgba8Image(
    val width: Int,
    val height: Int,
    rgba: ByteArray,
) {
    private val data = rgba.copyOf()

    init {
        require(width > 0 && height > 0)
        val pixels = Math.multiplyExact(width.toLong(), height.toLong())
        val expected = Math.multiplyExact(pixels, 4L)
        require(expected <= Int.MAX_VALUE.toLong()) { "RGBA8 image is too large" }
        require(data.size.toLong() == expected) {
            "RGBA8 byte count ${data.size} does not match ${width}x$height"
        }
    }

    fun copyRgba(): ByteArray = data.copyOf()

    fun pixel(index: Int): IntArray {
        require(index in 0 until width * height)
        val base = index * 4
        return intArrayOf(
            data[base].toInt() and 0xff,
            data[base + 1].toInt() and 0xff,
            data[base + 2].toInt() and 0xff,
            data[base + 3].toInt() and 0xff,
        )
    }

    fun contentEquals(other: Rgba8Image): Boolean =
        width == other.width && height == other.height && data.contentEquals(other.data)
}
