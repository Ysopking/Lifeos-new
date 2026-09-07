package app.lifeos.core.scene

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Direct-buffer adapter for the CPU reference rasterizer and existing JNI MMSI bridges. */
data class MmsiSceneDirectInputs(
    val albedoLinearRgba32f: ByteBuffer,
    val normalsXyz32f: ByteBuffer,
    val depthR32f: ByteBuffer,
    val roughnessR32f: ByteBuffer,
)

fun MmsiSceneRasterBuffers.toDirectMmsiInputs(): MmsiSceneDirectInputs = MmsiSceneDirectInputs(
    albedoLinearRgba32f = albedoLinearRgba.toDirectFloatBuffer(),
    normalsXyz32f = normalsXyz.toDirectFloatBuffer(),
    depthR32f = depth.toDirectFloatBuffer(),
    roughnessR32f = roughness.toDirectFloatBuffer(),
)

private fun FloatArray.toDirectFloatBuffer(): ByteBuffer {
    val bytes = Math.multiplyExact(size, Float.SIZE_BYTES)
    return ByteBuffer.allocateDirect(bytes)
        .order(ByteOrder.nativeOrder())
        .also { buffer -> buffer.asFloatBuffer().put(this) }
}
