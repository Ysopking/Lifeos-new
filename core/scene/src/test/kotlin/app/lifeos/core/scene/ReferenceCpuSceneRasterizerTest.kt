package app.lifeos.core.scene

import app.lifeos.core.image.MmsiBufferLayout
import app.lifeos.core.language.LanguageUnderstandingEngine
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ReferenceCpuSceneRasterizerTest {
    private val language = LanguageUnderstandingEngine()
    private val compiler = ProceduralSceneCompiler()
    private val rasterizer = ReferenceCpuSceneRasterizer()

    @Test
    fun `football scene rasterizes actors ball and ground into MMSI fields`() {
        val graph = footballScene()
        val size = SceneRasterSize(160, 90)
        val result = assertIs<SceneRasterResult.Rasterized>(rasterizer.rasterize(graph, size))
        val buffers = result.buffers

        assertEquals(size.pixelCount * 4, buffers.albedoLinearRgba.size)
        assertEquals(size.pixelCount * 3, buffers.normalsXyz.size)
        assertEquals(size.pixelCount, buffers.depth.size)
        assertEquals(size.pixelCount, buffers.roughness.size)
        assertEquals(
            size.pixelCount.toLong() * (
                MmsiBufferLayout.ALBEDO_RGBA32F_BYTES_PER_PIXEL +
                    MmsiBufferLayout.NORMAL_DEPTH_RGBA32F_BYTES_PER_PIXEL +
                    MmsiBufferLayout.ROUGHNESS_R32F_BYTES_PER_PIXEL
                ),
            buffers.mmsiInputBytes,
        )
        assertTrue(result.stats.coveredPixels > 0)
        assertTrue(result.stats.actorPixels > 0)
        assertTrue(result.stats.objectPixels > 0)
        assertTrue(result.stats.groundPixels > 0)
    }

    @Test
    fun `same graph produces byte-for-byte stable float fields`() {
        val graph = footballScene()
        val size = SceneRasterSize(128, 72)
        val first = assertIs<SceneRasterResult.Rasterized>(rasterizer.rasterize(graph, size)).buffers
        val second = assertIs<SceneRasterResult.Rasterized>(rasterizer.rasterize(graph, size)).buffers

        assertTrue(first.albedoLinearRgba.contentEquals(second.albedoLinearRgba))
        assertTrue(first.normalsXyz.contentEquals(second.normalsXyz))
        assertTrue(first.depth.contentEquals(second.depth))
        assertTrue(first.roughness.contentEquals(second.roughness))
    }

    @Test
    fun `covered pixels have finite unit view normals and bounded material fields`() {
        val buffers = assertIs<SceneRasterResult.Rasterized>(
            rasterizer.rasterize(footballScene(), SceneRasterSize(128, 72))
        ).buffers

        var inspected = 0
        for (pixel in 0 until buffers.size.pixelCount) {
            if (!buffers.isCovered(pixel)) continue
            val offset = pixel * 3
            val x = buffers.normalsXyz[offset].toDouble()
            val y = buffers.normalsXyz[offset + 1].toDouble()
            val z = buffers.normalsXyz[offset + 2].toDouble()
            val length = sqrt(x * x + y * y + z * z)
            assertTrue(abs(length - 1.0) < 1e-4)
            assertTrue(buffers.depth[pixel] in 0f..<1f)
            assertTrue(buffers.roughness[pixel] in 0.04f..1f)
            inspected++
        }
        assertTrue(inspected > 100)
    }

    @Test
    fun `reference rasterizer rejects rotations it cannot represent rather than ignoring them`() {
        val graph = footballScene()
        val actor = graph.nodes.first { it.role == SceneNodeRole.ACTOR }
        val rotated = graph.copy(
            nodes = graph.nodes.map { node ->
                if (node.id == actor.id) {
                    node.copy(transform = node.transform.copy(rotationDeg = SceneVec3(0.0, 15.0, 0.0)))
                } else node
            }
        )

        val result = assertIs<SceneRasterResult.Blocked>(
            rasterizer.rasterize(rotated, SceneRasterSize(96, 54))
        )
        assertTrue(result.reasons.any { it.contains("rotation not supported") })
    }

    @Test
    fun `background remains uncovered and deterministic`() {
        val result = assertIs<SceneRasterResult.Rasterized>(
            rasterizer.rasterize(footballScene(), SceneRasterSize(160, 90))
        )
        val buffers = result.buffers
        val uncovered = buffers.depth.indices.filter { buffers.depth[it] == 1f }
        assertTrue(uncovered.isNotEmpty())
        val pixel = uncovered.first()
        assertEquals(1f, buffers.roughness[pixel])
        assertEquals(0f, buffers.albedoLinearRgba[pixel * 4])
        assertEquals(1f, buffers.albedoLinearRgba[pixel * 4 + 3])
    }

    private fun footballScene(): ProceduralSceneGraph {
        val goal = language.understand("Erzeuge ein Bild von zwei Leuten, die Fußball spielen.").goal
        return assertIs<SceneCompileResult.Compiled>(compiler.compile(goal)).graph
    }
}
