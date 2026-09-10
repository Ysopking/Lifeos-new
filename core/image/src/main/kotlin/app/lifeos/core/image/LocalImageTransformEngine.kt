package app.lifeos.core.image

enum class LocalImageTransformOperation {
    BRIGHTER,
    DARKER,
    WARMER,
    COOLER,
    SHARPER,
    GRAYSCALE,
}

/**
 * Deterministic, model-free RGBA8 image transformations for the private offline runtime.
 * Alpha is always preserved. Operations are applied in the requested order and never mutate input.
 */
class LocalImageTransformEngine {
    fun transform(
        image: Rgba8Image,
        operations: List<LocalImageTransformOperation>,
    ): Rgba8Image {
        require(operations.isNotEmpty()) { "At least one image transformation is required" }
        require(operations.size <= LocalImageTransformOperation.entries.size) {
            "Too many image transformations"
        }
        require(operations.distinct().size == operations.size) {
            "Duplicate image transformations are not allowed"
        }

        var rgba = image.copyRgba()
        operations.forEach { operation ->
            rgba = when (operation) {
                LocalImageTransformOperation.BRIGHTER -> mapRgb(rgba) { channel ->
                    ((channel * 112 + 50) / 100 + 8).coerceIn(0, 255)
                }
                LocalImageTransformOperation.DARKER -> mapRgb(rgba) { channel ->
                    ((channel * 82 + 50) / 100).coerceIn(0, 255)
                }
                LocalImageTransformOperation.WARMER -> mapRgbChannels(rgba) { red, green, blue ->
                    intArrayOf(
                        (red + 14).coerceAtMost(255),
                        (green + 3).coerceAtMost(255),
                        (blue - 12).coerceAtLeast(0),
                    )
                }
                LocalImageTransformOperation.COOLER -> mapRgbChannels(rgba) { red, green, blue ->
                    intArrayOf(
                        (red - 12).coerceAtLeast(0),
                        (green + 2).coerceAtMost(255),
                        (blue + 14).coerceAtMost(255),
                    )
                }
                LocalImageTransformOperation.GRAYSCALE -> mapRgbChannels(rgba) { red, green, blue ->
                    val luminance = ((77 * red + 150 * green + 29 * blue + 128) shr 8)
                        .coerceIn(0, 255)
                    intArrayOf(luminance, luminance, luminance)
                }
                LocalImageTransformOperation.SHARPER -> sharpen(
                    rgba = rgba,
                    width = image.width,
                    height = image.height,
                )
            }
        }
        return Rgba8Image(image.width, image.height, rgba)
    }

    private fun mapRgb(
        input: ByteArray,
        transform: (Int) -> Int,
    ): ByteArray {
        val output = input.copyOf()
        var base = 0
        while (base < output.size) {
            output[base] = transform(unsigned(input[base])).toByte()
            output[base + 1] = transform(unsigned(input[base + 1])).toByte()
            output[base + 2] = transform(unsigned(input[base + 2])).toByte()
            base += 4
        }
        return output
    }

    private fun mapRgbChannels(
        input: ByteArray,
        transform: (Int, Int, Int) -> IntArray,
    ): ByteArray {
        val output = input.copyOf()
        var base = 0
        while (base < output.size) {
            val transformed = transform(
                unsigned(input[base]),
                unsigned(input[base + 1]),
                unsigned(input[base + 2]),
            )
            require(transformed.size == 3)
            output[base] = transformed[0].coerceIn(0, 255).toByte()
            output[base + 1] = transformed[1].coerceIn(0, 255).toByte()
            output[base + 2] = transformed[2].coerceIn(0, 255).toByte()
            base += 4
        }
        return output
    }

    private fun sharpen(
        rgba: ByteArray,
        width: Int,
        height: Int,
    ): ByteArray {
        if (width < 3 || height < 3) return rgba.copyOf()
        val output = rgba.copyOf()
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val center = (y * width + x) * 4
                val left = center - 4
                val right = center + 4
                val up = center - width * 4
                val down = center + width * 4
                for (channel in 0..2) {
                    val value =
                        unsigned(rgba[center + channel]) * 5 -
                            unsigned(rgba[left + channel]) -
                            unsigned(rgba[right + channel]) -
                            unsigned(rgba[up + channel]) -
                            unsigned(rgba[down + channel])
                    output[center + channel] = value.coerceIn(0, 255).toByte()
                }
            }
        }
        return output
    }

    private fun unsigned(value: Byte): Int = value.toInt() and 0xff
}
