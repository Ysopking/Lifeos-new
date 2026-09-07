package app.lifeos.core.scene

import app.lifeos.core.language.LanguageUnderstandingEngine
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReferenceCpuProceduralMmsiRendererTest {
    @Test
    fun `same scene renders byte-identically and keeps uncovered background black`() {
        val graph = compileFootballScene()
        val raster = ReferenceCpuSceneRasterizer().rasterize(graph, SceneRasterSize(48, 27))
            as SceneRasterResult.Rasterized
        val renderer = ReferenceCpuProceduralMmsiRenderer()

        val first = renderer.render(raster.buffers)
        val second = renderer.render(raster.buffers)

        assertContentEquals(first.copyRgba(), second.copyRgba())
        assertEquals("mmsi-cpu-procedural-v1", renderer.rendererId)
        val rgba = first.copyRgba()
        for (pixel in 0 until raster.buffers.size.pixelCount) {
            if (!raster.buffers.isCovered(pixel)) {
                val base = pixel * 4
                assertEquals(0, rgba[base].toInt() and 0xff)
                assertEquals(0, rgba[base + 1].toInt() and 0xff)
                assertEquals(0, rgba[base + 2].toInt() and 0xff)
                assertEquals(255, rgba[base + 3].toInt() and 0xff)
            }
        }
    }

    @Test
    fun `covered procedural scene produces visible finite color`() {
        val graph = compileFootballScene()
        val raster = ReferenceCpuSceneRasterizer().rasterize(graph, SceneRasterSize(48, 27))
            as SceneRasterResult.Rasterized
        val image = ReferenceCpuProceduralMmsiRenderer().render(raster.buffers)
        val rgba = image.copyRgba()

        val visibleCovered = (0 until raster.buffers.size.pixelCount).count { pixel ->
            raster.buffers.isCovered(pixel) && run {
                val base = pixel * 4
                (rgba[base].toInt() and 0xff) +
                    (rgba[base + 1].toInt() and 0xff) +
                    (rgba[base + 2].toInt() and 0xff) > 0
            }
        }
        assertTrue(raster.stats.coveredPixels > 0)
        assertTrue(visibleCovered > 0)
    }

    private fun compileFootballScene(): ProceduralSceneGraph {
        val goal = LanguageUnderstandingEngine()
            .understand("Erstelle ein Bild von zwei Leuten die Fussball spielen")
            .goal
        return (ProceduralSceneCompiler().compile(goal) as SceneCompileResult.Compiled).graph
    }
}
