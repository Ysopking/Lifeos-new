package app.lifeos.core.runtime

import app.lifeos.core.model.ModuleIdentity
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.Provenance
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecursiveCausalCognitionCoordinatorTest {
    @Test
    fun derivedPhotonCanAttractAnotherModuleWithoutCyclingBack() = runTest {
        val first = CognitiveModule(
            descriptor = CognitiveModuleDescriptor(
                identity = ModuleIdentity("module.first", "1", "impl-first"),
                acceptedMimeTypes = setOf("text/plain"),
                baseAttraction = 0.4,
            ),
            processor = CognitiveModuleProcessor { _, _ ->
                CognitiveModuleResult(
                    outputPhotons = listOf(
                        Photon(
                            content = "fact-one",
                            mimeType = "application/vnd.lifeos.fact+text",
                            provenance = Provenance("module.first", "lifeos", Instant.EPOCH),
                        ),
                    ),
                )
            },
        )
        val second = CognitiveModule(
            descriptor = CognitiveModuleDescriptor(
                identity = ModuleIdentity("module.second", "1", "impl-second"),
                acceptedMimeTypes = setOf("application/vnd.lifeos.fact+text"),
                baseAttraction = 0.4,
            ),
            processor = CognitiveModuleProcessor { _, _ ->
                CognitiveModuleResult(
                    outputPhotons = listOf(
                        Photon(
                            content = "fact-two",
                            mimeType = "application/vnd.lifeos.second-fact+text",
                            provenance = Provenance("module.second", "lifeos", Instant.EPOCH),
                        ),
                    ),
                )
            },
        )
        val registry = MutableCognitiveModuleRegistry(listOf(first, second))
        val persisted = mutableListOf<Photon>()
        val coordinator = RecursiveCausalCognitionCoordinator(
            modules = registry,
            engine = CausalCognitionEngine(),
            persistence = CausalDerivedPhotonPersistence { photon, _ -> persisted += photon },
            config = RecursiveCausalCognitionConfig(maxDepth = 4),
        )

        val summary = coordinator.processRoot(
            Photon(
                id = PhotonId("root"),
                content = "root",
                mimeType = "text/plain",
                provenance = Provenance("test", "user", Instant.EPOCH),
            ),
        )

        assertTrue(summary.failures.isEmpty())
        assertTrue(persisted.any { "module:module.first" in it.tags })
        assertTrue(persisted.any { "module:module.second" in it.tags })
        val secondModuleOutputs = persisted.filter { "module:module.second" in it.tags }
        assertTrue(secondModuleOutputs.all { "module:module.first" in it.tags })
        assertTrue(summary.persistedDerivedPhotons <= 6)
    }

    @Test
    fun replayGuardPhotonsNeverEnterRecursiveCognition() = runTest {
        var executions = 0
        val module = CognitiveModule(
            descriptor = CognitiveModuleDescriptor(
                identity = ModuleIdentity("module.any", "1", "impl"),
                acceptedMimeTypes = setOf("*/*"),
                baseAttraction = 0.4,
            ),
            processor = CognitiveModuleProcessor { _, _ ->
                executions += 1
                CognitiveModuleResult()
            },
        )
        val coordinator = RecursiveCausalCognitionCoordinator(
            modules = StaticCognitiveModuleRegistry(listOf(module)),
            engine = CausalCognitionEngine(),
            persistence = CausalDerivedPhotonPersistence { _, _ -> },
        )
        val summary = coordinator.processRoot(
            Photon(
                content = "guard",
                mimeType = PhotonBackedCausalLedgerStore.MIME_TYPE,
                tags = setOf("replay-guard"),
                provenance = Provenance("causal-ledger", "lifeos", Instant.EPOCH),
            ),
        )

        assertEquals(0, executions)
        assertEquals(0, summary.persistedDerivedPhotons)
    }
}
