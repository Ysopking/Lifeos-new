package app.lifeos.next.kernel

import android.content.Context
import app.lifeos.core.image.MmsiTile
import app.lifeos.core.image.ProceduralMmsiProfile
import app.lifeos.core.image.Rgba8Image
import app.lifeos.core.image.nativebackend.HardwareBufferMmsiInterop
import app.lifeos.core.image.nativebackend.MmsiHardwareHealthGuard
import app.lifeos.core.image.nativebackend.MmsiProceduralSpectralHardwarePipeline
import app.lifeos.core.image.nativebackend.MmsiRuntimeBackendProbe
import app.lifeos.core.image.nativebackend.VulkanMmsiRenderer
import app.lifeos.core.language.GoalFrame
import app.lifeos.core.scene.ProceduralSceneCompiler
import app.lifeos.core.scene.ProceduralSceneGraph
import app.lifeos.core.scene.ReferenceCpuProceduralMmsiRenderer
import app.lifeos.core.scene.SceneCompileResult
import app.lifeos.core.scene.SceneLightingResolver
import app.lifeos.core.scene.SceneRasterResult
import app.lifeos.core.scene.SceneRasterSize
import app.lifeos.core.scene.SceneRasterizer
import app.lifeos.core.scene.toDirectMmsiInputs
import app.lifeos.core.scene.toMmsiViewSpace
import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

sealed interface ProceduralImageRenderResult {
    data class Rendered(
        val graph: ProceduralSceneGraph,
        val image: Rgba8Image,
        val rendererId: String,
    ) : ProceduralImageRenderResult

    data class Blocked(val reasons: List<String>) : ProceduralImageRenderResult {
        init { require(reasons.isNotEmpty()) }
    }
}

/** Deterministic, fully offline GoalFrame -> rendered image service with GPU fast path + CPU fallback. */
class ProceduralImageGenerationEngine(
    context: Context,
    private val runtimeProbe: MmsiRuntimeBackendProbe,
    private val sceneCompiler: ProceduralSceneCompiler,
    private val sceneRasterizer: SceneRasterizer,
    private val computeDispatcher: CoroutineDispatcher,
    private val baseProfile: ProceduralMmsiProfile = ProceduralMmsiProfile(),
    private val sceneLightingResolver: SceneLightingResolver = SceneLightingResolver(),
    private val outputSize: SceneRasterSize = SceneRasterSize(512, 288),
    private val hardwareHealth: MmsiHardwareHealthGuard = MmsiHardwareHealthGuard(),
) {
    private val appContext = context.applicationContext

    suspend fun render(
        goal: GoalFrame,
        referenceInstant: Instant,
    ): ProceduralImageRenderResult = withContext(computeDispatcher) {
        val compiledGraph = when (val compiled = sceneCompiler.compile(goal)) {
            is SceneCompileResult.Compiled -> compiled.graph
            is SceneCompileResult.Blocked -> return@withContext ProceduralImageRenderResult.Blocked(compiled.reasons)
        }
        val lighting = sceneLightingResolver.resolve(
            environment = compiledGraph.environment,
            referenceInstant = referenceInstant,
            fallbackProfile = baseProfile,
        )
        val graph = compiledGraph.copy(
            environment = lighting.environment,
            warnings = (compiledGraph.warnings + lighting.warnings).distinct(),
        )
        val renderProfile = lighting.profile.toMmsiViewSpace(graph.camera)
        val rasterized = when (val raster = sceneRasterizer.rasterize(graph, outputSize)) {
            is SceneRasterResult.Rasterized -> raster
            is SceneRasterResult.Blocked -> return@withContext ProceduralImageRenderResult.Blocked(raster.reasons)
        }

        val hardware = renderHardware(rasterized.buffers, renderProfile)
        if (hardware != null) {
            return@withContext ProceduralImageRenderResult.Rendered(
                graph = graph,
                image = hardware,
                rendererId = if (lighting.astronomical) "$HARDWARE_RENDERER_ID-astro" else HARDWARE_RENDERER_ID,
            )
        }
        val cpuRenderer = ReferenceCpuProceduralMmsiRenderer(renderProfile)
        ProceduralImageRenderResult.Rendered(
            graph = graph,
            image = cpuRenderer.render(rasterized.buffers),
            rendererId = if (lighting.astronomical) "${cpuRenderer.rendererId}-astro" else cpuRenderer.rendererId,
        )
    }

    private fun renderHardware(
        buffers: app.lifeos.core.scene.MmsiSceneRasterBuffers,
        profile: ProceduralMmsiProfile,
    ): Rgba8Image? {
        if (!runtimeProbe.snapshot().capabilities.spectralAhbSyncFd) return null
        if (!hardwareHealth.canAttemptHardware()) return null
        return try {
            val pipeline = MmsiProceduralSpectralHardwarePipeline.create(appContext)
            if (pipeline == null) {
                hardwareHealth.recordRejected()
                return null
            }
            pipeline.use { renderer ->
                val interop = HardwareBufferMmsiInterop()
                interop.allocateSpectralForTile(MmsiTile(0, 0, buffers.size.width, buffers.size.height)).use { storage ->
                    val direct = buffers.toDirectMmsiInputs()
                    val result = renderer.renderTileRgba8(
                        storage = storage,
                        width = buffers.size.width,
                        height = buffers.size.height,
                        intrinsicAlbedoRgba32f = direct.albedoLinearRgba32f,
                        normals = direct.normalsXyz32f,
                        depth = direct.depthR32f,
                        roughness = direct.roughnessR32f,
                        coefficientProjection = profile.coefficientProjection,
                        forwardParameters = VulkanMmsiRenderer.Parameters(
                            sunDirection = profile.sunDirection,
                            sunSolidAngleRad = profile.sunSolidAngleRad,
                            sunColorLinear = profile.sunColorLinear,
                            skyAmbientLinear = profile.skyAmbientLinear,
                            shadowFloor = profile.shadowFloor,
                        ),
                        rgbProjection = profile.rgbProjection,
                    )
                    if (result.accepted && result.image != null) {
                        hardwareHealth.recordSuccess()
                        result.image
                    } else {
                        hardwareHealth.recordRejected()
                        null
                    }
                }
            }
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            hardwareHealth.recordFailure(error)
            null
        }
    }

    companion object {
        const val HARDWARE_RENDERER_ID = "mmsi-vulkan-spectral-procedural-v1"
    }
}
