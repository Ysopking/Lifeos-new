package app.lifeos.core.runtime.workers

import app.lifeos.core.model.ModuleIdentity
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonLoadReport
import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.task.TaskId
import app.lifeos.core.model.task.TaskState
import app.lifeos.core.runtime.CausalCognitionEngine
import app.lifeos.core.runtime.CausalDerivedPhotonPersistence
import app.lifeos.core.runtime.CognitiveModule
import app.lifeos.core.runtime.CognitiveModuleDescriptor
import app.lifeos.core.runtime.CognitiveModuleProcessor
import app.lifeos.core.runtime.CognitiveModuleResult
import app.lifeos.core.runtime.PhotonIngressMarkerStore
import app.lifeos.core.runtime.PhotonIngressMode
import app.lifeos.core.runtime.RecursiveCausalCognitionCoordinator
import app.lifeos.core.runtime.StaticCognitiveModuleRegistry
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CausalCognitionTaskObserverIngressTest {
    private class MemoryPhotonRepository : PhotonRepository {
        private val data = linkedMapOf<PhotonId, Photon>()

        override suspend fun save(photon: Photon) { data[photon.id] = photon }
        override suspend fun load(id: PhotonId): Photon? = data[id]
        override suspend fun loadReport(): PhotonLoadReport = PhotonLoadReport(data.values.toList(), emptyList())
        override suspend fun loadAll(): List<Photon> = data.values.toList()
        override suspend fun delete(id: PhotonId) { data.remove(id) }
    }

    @Test
    fun onlyOriginCompletionSeedsRecursiveCognition() = runTest {
        val repository = MemoryPhotonRepository()
        var executions = 0
        val module = CognitiveModule(
            descriptor = CognitiveModuleDescriptor(
                identity = ModuleIdentity("observer.test", "1", "impl"),
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
        val observer = CausalCognitionTaskObserver(repository, coordinator)

        val origin = photon("origin")
        repository.save(origin)
        observer.onExecutionResult(completed(origin))
        val executionsAfterOrigin = executions
        assertTrue(executionsAfterOrigin > 0)

        val derived = photon("derived")
        repository.save(derived)
        PhotonIngressMarkerStore.mark(repository, derived, PhotonIngressMode.DERIVED)
        observer.onExecutionResult(completed(derived))
        assertEquals(executionsAfterOrigin, executions)

        val replay = photon("replay")
        repository.save(replay)
        PhotonIngressMarkerStore.mark(repository, replay, PhotonIngressMode.REPLAY)
        observer.onExecutionResult(completed(replay))
        assertEquals(executionsAfterOrigin, executions)
    }

    private fun photon(id: String): Photon = Photon(
        id = PhotonId(id),
        content = "payload:$id",
        provenance = Provenance("test", "lifeos", Instant.EPOCH),
    )

    private fun completed(photon: Photon): CognitiveTaskExecutionResult = CognitiveTaskExecutionResult(
        taskId = TaskId("task-${photon.id.value}"),
        photonId = photon.id,
        finalState = TaskState.COMPLETED,
        influences = emptyList(),
        failures = emptyList(),
    )
}
