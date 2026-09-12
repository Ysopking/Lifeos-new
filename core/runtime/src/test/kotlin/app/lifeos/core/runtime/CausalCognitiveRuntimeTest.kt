package app.lifeos.core.runtime

import app.lifeos.core.model.ModuleIdentity
import app.lifeos.core.model.Photon
import app.lifeos.core.model.Provenance
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CausalCognitiveRuntimeTest {
    @Test
    fun runtimeProcessesThroughCausalEngineAndEmitsDerivedAndIntegratedPhotons() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher + backgroundScope.coroutineContext)
        val emitted = mutableListOf<Photon>()
        val module = CognitiveModule(
            descriptor = CognitiveModuleDescriptor(
                identity = ModuleIdentity("test.module", "1", "impl"),
                acceptedMimeTypes = setOf("text/*"),
                baseAttraction = 0.4,
            ),
            processor = CognitiveModuleProcessor { _, _ ->
                CognitiveModuleResult(
                    outputPhotons = listOf(
                        Photon(
                            content = "fact",
                            provenance = Provenance("test.module", "lifeos"),
                        ),
                    ),
                )
            },
        )
        val runtime = CausalCognitiveRuntime(
            scope = scope,
            moduleRegistry = StaticCognitiveModuleRegistry(listOf(module)),
            engine = CausalCognitionEngine(),
            photonSink = CausalPhotonSink { photon, _ -> emitted += photon },
        )

        runtime.start()
        runtime.ingest(
            Photon(
                content = "hello",
                provenance = Provenance("test", "user"),
            ),
        )
        advanceUntilIdle()

        assertEquals(1, runtime.state.value.processed)
        assertEquals(0, runtime.state.value.failed)
        assertEquals(2, emitted.size)
        assertTrue(emitted.any { it.mimeType == "application/vnd.lifeos.cognitive-integration+text" })
        runtime.stop()
    }
}
