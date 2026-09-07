package app.lifeos.core.runtime.boot

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonLoadReport
import app.lifeos.core.model.PhotonRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class BootCoordinatorTest {
    @Test
    fun completesReadyBootAndPersistsTransitions() = runTest {
        val states = mutableListOf<BootState>()
        val coordinator = BootCoordinator(
            runtimeBootstrapper = object : RuntimeBootstrapper {
                override suspend fun bootstrap() = Unit
            },
            storeVerifier = object : StoreVerifier {
                override suspend fun verify() = StoreVerificationResult(
                    stores = listOf(StoreStatus("photons", StoreState.HEALTHY)),
                    canBootNormally = true,
                    requiresRecovery = false,
                )
            },
            stateRehydrator = object : StateRehydrator {
                override suspend fun rehydrate() = RehydratedRuntimeState(checkpointId = "cp-1", previousEpoch = 3)
            },
            photonRehydrator = PhotonRehydrator(EmptyPhotonRepository()),
            moduleRehydrator = object : ModuleRehydrator {
                override suspend fun rehydrate() = ModuleRestoreSummary(restored = 4)
            },
            thoughtMatrixWarmup = object : ThoughtMatrixWarmup {
                override suspend fun warmup() = ThoughtMatrixWarmupResult(snapshotId = "matrix-1")
            },
            capabilityWarmup = object : CapabilityWarmup {
                override suspend fun warmup() = CapabilityWarmupResult(availableCapabilities = 7)
            },
            deltaDetector = object : BootDeltaDetector {
                override suspend fun detect(context: BootContext) = 2L
            },
            validator = object : BootValidator {
                override suspend fun validate(context: BootContext): BootValidationResult = BootValidationResult.Ready
            },
            stateSink = BootStateSink { states += it.state },
            newBootId = { "boot-1" },
        )

        val result = assertIs<BootRunResult.Ready>(coordinator.boot())

        assertEquals(BootState.READY, result.snapshot.state)
        assertEquals("cp-1", result.snapshot.lastCheckpointId)
        assertEquals(4, result.snapshot.restoredModuleCount)
        assertEquals(2, result.snapshot.detectedDeltaCount)
        assertEquals(BootState.INITIALIZING, states.first())
        assertEquals(BootState.READY, states.last())
    }

    private class EmptyPhotonRepository : PhotonRepository {
        override suspend fun save(photon: Photon) = Unit
        override suspend fun loadAll(): List<Photon> = emptyList()
        override suspend fun delete(id: PhotonId) = Unit
        override suspend fun load(id: PhotonId): Photon? = null
        override suspend fun loadReport() = PhotonLoadReport(emptyList(), emptyList())
    }
}
