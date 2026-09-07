package app.lifeos.core.scene

import app.lifeos.core.language.LanguageUnderstandingEngine
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MmsiSceneDirectInputsTest {
    @Test
    fun `reference raster fields pack into direct native-order JNI buffers`() {
        val goal = LanguageUnderstandingEngine()
            .understand("Erzeuge ein Bild von zwei Leuten, die Fußball spielen.")
            .goal
        val graph = assertIs<SceneCompileResult.Compiled>(ProceduralSceneCompiler().compile(goal)).graph
        val raster = assertIs<SceneRasterResult.Rasterized>(
            ReferenceCpuSceneRasterizer().rasterize(graph, SceneRasterSize(64, 36))
        ).buffers

        val direct = raster.toDirectMmsiInputs()
        val pixels = raster.size.pixelCount
        assertTrue(direct.albedoLinearRgba32f.isDirect)
        assertTrue(direct.normalsXyz32f.isDirect)
        assertTrue(direct.depthR32f.isDirect)
        assertTrue(direct.roughnessR32f.isDirect)
        assertEquals(ByteOrder.nativeOrder(), direct.albedoLinearRgba32f.order())
        assertEquals(pixels * 4 * Float.SIZE_BYTES, direct.albedoLinearRgba32f.capacity())
        assertEquals(pixels * 3 * Float.SIZE_BYTES, direct.normalsXyz32f.capacity())
        assertEquals(pixels * Float.SIZE_BYTES, direct.depthR32f.capacity())
        assertEquals(pixels * Float.SIZE_BYTES, direct.roughnessR32f.capacity())

        val firstAlbedo = direct.albedoLinearRgba32f.asFloatBuffer().get(0)
        assertEquals(raster.albedoLinearRgba[0], firstAlbedo)
    }
}
