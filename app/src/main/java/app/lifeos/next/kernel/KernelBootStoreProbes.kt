package app.lifeos.next.kernel

import app.lifeos.core.data.boot.EncryptedBootEngineCycleRepository
import app.lifeos.core.data.cognition.EncryptedCognitiveModuleSnapshotRepository
import app.lifeos.core.data.evolution.EncryptedEvolutionStore
import app.lifeos.core.data.evolution.EncryptedWorldEquationEvidenceRepository
import app.lifeos.core.data.goal.EncryptedGoalPlanRepository
import app.lifeos.core.data.health.EncryptedProtectionStateRepository
import app.lifeos.core.data.learning.EncryptedLearningAdaptationRepository
import app.lifeos.core.data.learning.EncryptedLearningWatermarkRepository
import app.lifeos.core.data.thought.EncryptedFieldThoughtGraphProjectionOutboxRepository
import app.lifeos.core.data.thought.EncryptedThoughtGraphDeltaRepository
import app.lifeos.core.data.thought.EncryptedThoughtMatrixStateRepository
import app.lifeos.core.data.world.EncryptedProductiveWorldHeadRepository
import app.lifeos.core.data.world.EncryptedWorldEquationHeadRepository
import app.lifeos.core.data.world.EncryptedWorldEquationSpecRepository
import app.lifeos.core.data.world.EncryptedWorldFormulaSnapshotRepository
import app.lifeos.core.data.worldmodel.EncryptedWorldModelRepository
import app.lifeos.core.model.health.ProtectionStateLoadResult
import app.lifeos.core.runtime.boot.BootReadSession
import app.lifeos.core.runtime.boot.BootSnapshotSource
import app.lifeos.core.runtime.boot.StoreProbe
import app.lifeos.core.runtime.boot.StoreState
import app.lifeos.core.runtime.boot.StoreStatus
import app.lifeos.core.runtime.extension.ExtensionRegistryRehydrator
import app.lifeos.core.runtime.learning.LearningWatermarkLoadResult

/** Boot integrity probes kept outside the process composition root. */
internal class KernelBootStoreProbes(
    private val bootReadSession: BootReadSession,
    private val goalPlanRepository: EncryptedGoalPlanRepository,
    private val learningAdaptationRepository: EncryptedLearningAdaptationRepository,
    private val learningWatermarks: EncryptedLearningWatermarkRepository,
    private val worldEquationHeads: EncryptedWorldEquationHeadRepository,
    private val worldEquationSpecs: EncryptedWorldEquationSpecRepository,
    private val worldEquationEvidence: EncryptedWorldEquationEvidenceRepository,
    private val thoughtMatrixStateRepository: EncryptedThoughtMatrixStateRepository,
    private val thoughtGraphDeltaRepository: EncryptedThoughtGraphDeltaRepository,
    private val fieldThoughtGraphProjectionOutbox: EncryptedFieldThoughtGraphProjectionOutboxRepository,
    private val protectionRepository: EncryptedProtectionStateRepository,
    private val worldFormulaSnapshotRepository: EncryptedWorldFormulaSnapshotRepository,
    private val productiveWorldHeadRepository: EncryptedProductiveWorldHeadRepository,
    private val bootEngineCycleRepository: EncryptedBootEngineCycleRepository,
    private val extensionRegistryRehydrator: ExtensionRegistryRehydrator,
    private val worldModelRepository: EncryptedWorldModelRepository,
    private val cognitiveModuleSnapshotRepository: EncryptedCognitiveModuleSnapshotRepository,
    private val evolutionStore: EncryptedEvolutionStore,
    private val privateGeneratedToolRuntime: PrivateGeneratedToolRuntimeResources,
) {
    fun create(): List<StoreProbe> = listOf(
    object : StoreProbe {
        override val storeId: String = "goal-plan-ledger"
        override suspend fun probe(): StoreStatus {
            val report = bootReadSession.readOnce("goal-plan-ledger") {
                goalPlanRepository.loadReport()
            }
            return StoreStatus(
                storeId = storeId,
                state = if (report.isCorrupted) StoreState.CORRUPTED else StoreState.HEALTHY,
                message = if (report.isCorrupted) "unreadable:${report.unreadableEntries.size}" else null,
            )
        }
    },
    object : StoreProbe {
        override val storeId: String = "photon-store"
        override suspend fun probe(): StoreStatus {
            val failures = bootReadSession.readFailures(BootSnapshotSource.PHOTON)
            return StoreStatus(
                storeId = storeId,
                state = if (failures.isEmpty()) StoreState.HEALTHY else StoreState.PARTIALLY_RECOVERABLE,
                message = if (failures.isEmpty()) null else "unreadable:${failures.size}",
            )
        }
    },
    object : StoreProbe {
        override val storeId: String = "learning-adaptation-ledger"
        override suspend fun probe(): StoreStatus {
            val report = bootReadSession.readOnce("learning-adaptation-ledger") {
                learningAdaptationRepository.loadReport()
            }
            return StoreStatus(
                storeId = storeId,
                state = if (report.unreadableEntries.isEmpty()) StoreState.HEALTHY else StoreState.CORRUPTED,
                message = if (report.unreadableEntries.isEmpty()) null else "unreadable:${report.unreadableEntries.size}",
            )
        }
    },
    object : StoreProbe {
        override val storeId: String = "continuous-learning-watermarks"
        override suspend fun probe(): StoreStatus =
            when (val loaded = learningWatermarks.load()) {
                LearningWatermarkLoadResult.Missing,
                is LearningWatermarkLoadResult.Loaded ->
                    StoreStatus(storeId, StoreState.HEALTHY)
                is LearningWatermarkLoadResult.Unreadable ->
                    StoreStatus(
                        storeId = storeId,
                        state = StoreState.CORRUPTED,
                        message = loaded.message,
                    )
            }
    },
    object : StoreProbe {
        override val storeId: String = "world-equation-head"
        override suspend fun probe(): StoreStatus {
            val report = bootReadSession.readOnce("world-equation-head") {
                worldEquationHeads.loadReport()
            }
            return StoreStatus(
                storeId = storeId,
                state = if (report.corrupted) StoreState.CORRUPTED else StoreState.HEALTHY,
                message = report.message,
            )
        }
    },
    object : StoreProbe {
        override val storeId: String = "world-equation-spec-store"
        override suspend fun probe(): StoreStatus {
            val report = bootReadSession.readOnce("world-equation-spec-store") {
                worldEquationSpecs.loadReport()
            }
            return StoreStatus(
                storeId = storeId,
                state = if (report.corrupted) StoreState.CORRUPTED else StoreState.HEALTHY,
                message = report.unreadableEntries
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString(","),
            )
        }
    },
    object : StoreProbe {
        override val storeId: String = "world-equation-evidence-store"
        override suspend fun probe(): StoreStatus {
            val report = bootReadSession.readOnce("world-equation-evidence-store") {
                worldEquationEvidence.loadReport()
            }
            return StoreStatus(
                storeId = storeId,
                state = if (report.corrupted) StoreState.CORRUPTED else StoreState.HEALTHY,
                message = report.unreadableEntries
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString(","),
            )
        }
    },
    object : StoreProbe {
        override val storeId: String = "thought-matrix-state-store"
        override suspend fun probe(): StoreStatus {
            bootReadSession.readOnce("thought-matrix-state-store") {
                thoughtMatrixStateRepository.load()
            }
            return StoreStatus(storeId, StoreState.HEALTHY)
        }
    },
    object : StoreProbe {
        override val storeId: String = "thought-graph-delta-store"
        override suspend fun probe(): StoreStatus {
            val report = bootReadSession.readOnce("thought-graph-delta-store") {
                thoughtGraphDeltaRepository.loadReport()
            }
            return StoreStatus(
                storeId = storeId,
                state = if (report.unreadableEntries.isEmpty()) StoreState.HEALTHY else StoreState.CORRUPTED,
                message = if (report.unreadableEntries.isEmpty()) null else "unreadable:${report.unreadableEntries.size}",
            )
        }
    },
    object : StoreProbe {
        override val storeId: String = "field-thought-graph-projection-outbox"
        override suspend fun probe(): StoreStatus {
            val report = bootReadSession.readOnce("field-thought-graph-projection-outbox") {
                fieldThoughtGraphProjectionOutbox.loadReport()
            }
            return StoreStatus(
                storeId = storeId,
                state = if (report.unreadableEntries.isEmpty()) StoreState.HEALTHY else StoreState.CORRUPTED,
                message = if (report.unreadableEntries.isEmpty()) null else "unreadable:${report.unreadableEntries.size}",
            )
        }
    },
    object : StoreProbe {
        override val storeId: String = "task-store"
        override suspend fun probe(): StoreStatus {
            val failures = bootReadSession.readFailures(BootSnapshotSource.TASK)
            return StoreStatus(
                storeId = storeId,
                state = if (failures.isEmpty()) StoreState.HEALTHY else StoreState.CORRUPTED,
                message = if (failures.isEmpty()) null else "unreadable:${failures.size}",
            )
        }
    },
    object : StoreProbe {
        override val storeId: String = "runtime-protection-store"
        override suspend fun probe(): StoreStatus = when (
            val protection = bootReadSession.readOnce("runtime-protection-store") {
                protectionRepository.load()
            }
        ) {
            ProtectionStateLoadResult.Missing -> StoreStatus(storeId = storeId, state = StoreState.HEALTHY)
            is ProtectionStateLoadResult.Loaded -> StoreStatus(
                storeId = storeId,
                state = if (protection.state.protected) StoreState.LOCKED else StoreState.HEALTHY,
                message = protection.state.takeIf { it.protected }?.let { "active:${it.mode.name.lowercase()}:generation-${it.generation}" },
            )
            is ProtectionStateLoadResult.Unreadable -> StoreStatus(storeId = storeId, state = StoreState.CORRUPTED, message = protection.message)
        }
    },
    object : StoreProbe {
        override val storeId: String = "field-snapshot-store"
        override suspend fun probe(): StoreStatus {
            val failures = bootReadSession.readFailures(BootSnapshotSource.FIELD)
            return StoreStatus(
                storeId = storeId,
                state = if (failures.isEmpty()) StoreState.HEALTHY else StoreState.PARTIALLY_RECOVERABLE,
                message = if (failures.isEmpty()) null else "unreadable:${failures.size}",
            )
        }
    },
    object : StoreProbe {
        override val storeId: String = "world-formula-snapshot-store"
        override suspend fun probe(): StoreStatus {
            val report = bootReadSession.readOnce("world-formula-snapshot-store") {
                worldFormulaSnapshotRepository.loadReport()
            }
            return StoreStatus(
                storeId = storeId,
                state = if (report.unreadableEntries.isEmpty()) StoreState.HEALTHY else StoreState.PARTIALLY_RECOVERABLE,
                message = if (report.unreadableEntries.isEmpty()) null else "unreadable:${report.unreadableEntries.size}",
            )
        }
    },
    object : StoreProbe {
        override val storeId: String = "productive-world-head-store"
        override suspend fun probe(): StoreStatus {
            val report = bootReadSession.readOnce("productive-world-head-store") {
                productiveWorldHeadRepository.loadReport()
            }
            return StoreStatus(
                storeId = storeId,
                state = if (report.corrupted) StoreState.CORRUPTED else StoreState.HEALTHY,
                message = report.message,
            )
        }
    },
    object : StoreProbe {
        override val storeId: String = "bootengine-cycle-store"
        override suspend fun probe(): StoreStatus {
            val report = bootReadSession.readOnce("bootengine-cycle-store") {
                bootEngineCycleRepository.loadReport()
            }
            return StoreStatus(
                storeId = storeId,
                state = if (report.corrupted) {
                    StoreState.PARTIALLY_RECOVERABLE
                } else {
                    StoreState.HEALTHY
                },
                message = report.message,
            )
        }
    },
    object : StoreProbe {
        override val storeId: String = "extension-registry-store"
        override suspend fun probe(): StoreStatus = try {
            extensionRegistryRehydrator.rehydrate()
            StoreStatus(storeId, StoreState.HEALTHY)
        } catch (error: Exception) {
            StoreStatus(
                storeId = storeId,
                state = StoreState.CORRUPTED,
                message = error.message ?: error::class.simpleName,
            )
        }
    },
    object : StoreProbe {
        override val storeId: String = "world-model-store"
        override suspend fun probe(): StoreStatus = try {
            val head = worldModelRepository.loadHead()
            if (head != null) {
                requireNotNull(worldModelRepository.loadSnapshot(head.activeSnapshotId))
            }
            StoreStatus(storeId, StoreState.HEALTHY)
        } catch (error: Exception) {
            StoreStatus(
                storeId = storeId,
                state = StoreState.CORRUPTED,
                message = error.message ?: error::class.simpleName,
            )
        }
    },
    object : StoreProbe {
        override val storeId: String = "cognitive-module-snapshot-store"
        override suspend fun probe(): StoreStatus = try {
            val head = cognitiveModuleSnapshotRepository.loadHead()
            if (head != null) {
                requireNotNull(
                    cognitiveModuleSnapshotRepository.load(head.activeSnapshotId)
                ) { "Cognitive module head points to missing snapshot" }
            }
            StoreStatus(storeId, StoreState.HEALTHY)
        } catch (error: Exception) {
            StoreStatus(
                storeId = storeId,
                state = StoreState.CORRUPTED,
                message = error.message ?: error::class.simpleName,
            )
        }
    },
    object : StoreProbe {
        override val storeId: String = "evolution-store"
        override suspend fun probe(): StoreStatus {
            bootReadSession.readOnce("evolution-store") {
                evolutionStore.killSwitch(BOOT_PROBE_ADOPTION_ID)
            }
            return StoreStatus(storeId, StoreState.HEALTHY)
        }
    },
    object : StoreProbe {
        override val storeId: String = "generated-tool-state-store"
        override suspend fun probe(): StoreStatus {
            bootReadSession.snapshot()
            val failures = bootReadSession.readFailures(BootSnapshotSource.TOOL)
            return StoreStatus(
                storeId = storeId,
                state = if (failures.isEmpty()) StoreState.HEALTHY else StoreState.CORRUPTED,
                message = if (failures.isEmpty()) null else "unreadable:${failures.size}",
            )
        }
    },
    object : StoreProbe {
        override val storeId: String = "generated-tool-artifact-store"
        override suspend fun probe(): StoreStatus {
            privateGeneratedToolRuntime.artifactBootVerifier.verify()
            return StoreStatus(storeId, StoreState.HEALTHY)
        }
    },

    )

    private companion object {
        const val BOOT_PROBE_ADOPTION_ID = "__lifeos_boot_integrity_probe__"
    }
}
