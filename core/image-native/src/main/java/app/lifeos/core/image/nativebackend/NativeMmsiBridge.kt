package app.lifeos.core.image.nativebackend

import app.lifeos.core.image.SolarVector
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Zero-copy JNI entry point for the MMSI inverse-radiometry pass. */
class NativeMmsiBridge {
    fun isAvailable(): Boolean = runCatching {
        ensureLoaded()
        true
    }.getOrDefault(false)

    fun inverseRadiometry(
        rgb: ByteBuffer,
        normals: ByteBuffer,
        roughness: ByteBuffer,
        output: ByteBuffer,
        pixelCount: Int,
        sunDirection: SolarVector,
        sunIntensity: Float,
        ambientIntensity: Float,
    ) {
        require(pixelCount >= 0)
        require(rgb.isDirect) { "RGB buffer must be direct" }
        require(normals.isDirect) { "Normal buffer must be direct" }
        require(roughness.isDirect) { "Roughness buffer must be direct" }
        require(output.isDirect) { "Output buffer must be direct" }
        require(rgb.capacity() >= checkedByteCount(pixelCount, RGB_BYTES_PER_PIXEL))
        require(normals.capacity() >= checkedByteCount(pixelCount, NORMAL_BYTES_PER_PIXEL))
        require(roughness.capacity() >= checkedByteCount(pixelCount, ROUGHNESS_BYTES_PER_PIXEL))
        require(output.capacity() >= checkedByteCount(pixelCount, OUTPUT_BYTES_PER_PIXEL))
        require(sunIntensity >= 0f && sunIntensity.isFinite())
        require(ambientIntensity >= 0f && ambientIntensity.isFinite())
        val sun = sunDirection.normalized()

        ensureLoaded()
        nativeInverseRadiometry(
            rgb,
            normals.order(ByteOrder.nativeOrder()),
            roughness.order(ByteOrder.nativeOrder()),
            output.order(ByteOrder.nativeOrder()),
            pixelCount,
            sun.x.toFloat(),
            sun.y.toFloat(),
            sun.z.toFloat(),
            sunIntensity,
            ambientIntensity,
        )
    }

    private external fun nativeInverseRadiometry(
        rgbBuffer: ByteBuffer,
        normalBuffer: ByteBuffer,
        roughnessBuffer: ByteBuffer,
        outputBuffer: ByteBuffer,
        pixelCount: Int,
        sunX: Float,
        sunY: Float,
        sunZ: Float,
        sunIntensity: Float,
        ambientIntensity: Float,
    )

    companion object {
        const val RGB_BYTES_PER_PIXEL = 3
        const val NORMAL_BYTES_PER_PIXEL = 3 * Float.SIZE_BYTES
        const val ROUGHNESS_BYTES_PER_PIXEL = Float.SIZE_BYTES
        const val OUTPUT_BYTES_PER_PIXEL = 3 * Float.SIZE_BYTES

        @Volatile private var loaded = false

        private fun ensureLoaded() {
            if (loaded) return
            synchronized(this) {
                if (!loaded) {
                    System.loadLibrary("lifeos_mmsi_native")
                    loaded = true
                }
            }
        }

        fun allocateRgb(pixelCount: Int): ByteBuffer = direct(checkedByteCount(pixelCount, RGB_BYTES_PER_PIXEL))
        fun allocateNormals(pixelCount: Int): ByteBuffer = direct(checkedByteCount(pixelCount, NORMAL_BYTES_PER_PIXEL))
        fun allocateRoughness(pixelCount: Int): ByteBuffer = direct(checkedByteCount(pixelCount, ROUGHNESS_BYTES_PER_PIXEL))
        fun allocateOutput(pixelCount: Int): ByteBuffer = direct(checkedByteCount(pixelCount, OUTPUT_BYTES_PER_PIXEL))

        internal fun checkedByteCount(pixelCount: Int, bytesPerPixel: Int): Int {
            require(pixelCount >= 0) { "pixelCount must be non-negative" }
            require(bytesPerPixel > 0) { "bytesPerPixel must be positive" }
            return try {
                Math.multiplyExact(pixelCount, bytesPerPixel)
            } catch (overflow: ArithmeticException) {
                throw IllegalArgumentException(
                    "Native buffer byte count overflow: pixelCount=$pixelCount bytesPerPixel=$bytesPerPixel",
                    overflow,
                )
            }
        }

        private fun direct(bytes: Int): ByteBuffer {
            require(bytes >= 0)
            return ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
        }
    }
}
