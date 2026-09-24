package app.lifeos.next.kernel

import app.lifeos.core.data.boot.EncryptedBootEngineCycleRepository
import app.lifeos.core.data.checkpoint.EncryptedCheckpointRepository
import app.lifeos.core.data.convergence.EncryptedConvergenceDecisionCheckpointRepository
import app.lifeos.core.data.evolution.EncryptedWorldEquationEvidenceRepository
import app.lifeos.core.data.extension.EncryptedExtensionRegistryHeadRepository
import app.lifeos.core.data.extension.EncryptedExtensionRegistrySnapshotRepository
import app.lifeos.core.data.field.EncryptedFieldSnapshotRepository
import app.lifeos.core.data.task.EncryptedTaskRepository
import app.lifeos.core.data.thought.EncryptedFieldThoughtGraphProjectionOutboxRepository
import app.lifeos.core.data.world.EncryptedProductiveWorldHeadRepository
import app.lifeos.core.data.world.EncryptedWorldEquationHeadRepository
import app.lifeos.core.data.world.EncryptedWorldEquationSpecRepository
import app.lifeos.core.data.world.EncryptedWorldFormulaSnapshotRepository
import app.lifeos.core.data.worldmodel.EncryptedWorldModelRepository
import app.lifeos.core.field.StableFieldIds
import java.time.Instant
import app.lifeos.core.runtime.world.PersonalContextSnapshot
import app.lifeos.core.runtime.world.PersonalContextBootBinding
import app.lifeos.core.runtime.policy.OwnerObservationPolicyLedger
import app.lifeos.core.runtime.life.AppSensorRegistrySnapshot
import app.lifeos.core.runtime.life.AppSensorRegistry
import app.lifeos.core.model.RevisionedPhotonRepository
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.PhotonIndexQuery
import app.lifeos.core.model.PhotonIndexOrder
import app.lifeos.core.runtime.boot.BootEngineFrozenInputs
import app.lifeos.core.runtime.boot.BootEngineRuntime
import app.lifeos.core.runtime.boot.BootReadSession
import app.lifeos.core.runtime.boot.BootSnapshotLoader
import app.lifeos.core.runtime.boot.CapabilityRegistryBootSource
import app.lifeos.core.runtime.boot.CheckpointRepositoryBootSource
import app.lifeos.core.runtime.boot.FieldSnapshotRepositoryBootSource
import app.lifeos.core.runtime.boot.GeneratedToolRegistryBootSource
import app.lifeos.core.runtime.boot.PhotonRepositoryBootSource
import app.lifeos.core.runtime.boot.TaskRepositoryBootSource
import app.lifeos.core.runtime.convergence.DefaultProductiveConvergenceAuthority
import app.lifeos.core.runtime.convergence.DurableConvergenceDecisionCoordinator
import app.lifeos.core.runtime.convergence.WorldFormulaBoundConvergenceService
import app.lifeos.core.runtime.evolution.WorldEquationAutoEvolutionCoordinator
import app.lifeos.core.runtime.evolution.WorldEquationEvidenceCoordinator
import app.lifeos.core.runtime.evolution.WorldEquationEvolutionAdmissionGate
import app.lifeos.core.runtime.evolution.WorldEquationPostActivationSafetyMonitor
import app.lifeos.core.runtime.evolution.WorldEquationPostActivationSafetyRuntimeRegistry
import app.lifeos.core.runtime.evolution.WorldEquationPromotionEvaluator
import app.lifeos.core.runtime.evolution.WorldEquationPromotionPolicy
import app.lifeos.core.runtime.evolution.WorldEquationShadowEvaluator
import app.lifeos.core.runtime.extension.ExtensionRegistryRehydrator
import app.lifeos.core.runtime.field.FieldThoughtGraphProjectionCoordinator
import app.lifeos.core.runtime.goal.GoalConvergenceDecisionProvider
import app.lifeos.core.runtime.goal.GoalCycleFrozenInputSource
import app.lifeos.core.runtime.world.CognitiveCycleId
import app.lifeos.core.runtime.world.CognitiveWorldEquationProfile
import app.lifeos.core.runtime.world.InMemoryWorldEquationRegistry
import app.lifeos.core.runtime.world.ProductiveWorldHeadCommitter
import app.lifeos.core.runtime.world.SelfStateWorldEquationProfile
import app.lifeos.core.runtime.world.SelfStateWorldFormulaEvaluator
import app.lifeos.core.runtime.world.SelfStateWorldFormulaRuntimeRegistry
import app.lifeos.core.runtime.world.SelfStateWorldFormulaSnapshotRepository
import app.lifeos.core.runtime.world.WorldEquationActivationAuthority
import app.lifeos.core.runtime.world.WorldFormulaCoordinator
import app.lifeos.core.runtime.world.WorldFormulaExecutionPolicy

internal data class FrozenProductivePersonalContext(
    val snapshot: PersonalContextSnapshot,
    val sensorRegistry: AppSensorRegistrySnapshot,
    val binding: PersonalContextBootBinding,
)

internal class ProductivePersonalContextBindingSource(
    private val photons: RevisionedPhotonRepository,
    private val sensors: AppSensorRegistry,
    private val ownerObservationPolicy: OwnerObservationPolicyLedger,
) {
    suspend fun freeze(): FrozenProductivePersonalContext {
        val sensorSnapshot = sensors.snapshot()
        val policySnapshot = ownerObservationPolicy.snapshot()
        val observationRefs = queryRefs(
            PhotonIndexQuery(
                allTags = setOf("information-observation"),
                latestOnly = true,
                order = PhotonIndexOrder.NEWEST_FIRST,
                limit = MAX_OBSERVATION_REFS,
            )
        )
        val evidenceRefs = queryRefs(
            PhotonIndexQuery(
                anyTags = setOf(
                    "information-observation",
                    "perception",
                    "evidence",
                    "result",
                ),
                latestOnly = true,
                order = PhotonIndexOrder.NEWEST_FIRST,
                limit = MAX_EVIDENCE_REFS,
            )
        )
        val conversationRefs = queryRefs(
            PhotonIndexQuery(
                allTags = setOf("chat"),
                latestOnly = true,
                order = PhotonIndexOrder.NEWEST_FIRST,
                limit = MAX_CONVERSATION_REFS,
            )
        )

        val snapshot = PersonalContextSnapshot.create(
            appObservationHeadFingerprint = refSetFingerprint(
                namespace = "app-observation-head/v1",
                refs = observationRefs,
            ),
            sensorProjectionFingerprint = sensorSnapshot.fingerprint(),
            conversationStateFingerprint = conversationRefs
                .takeIf { it.isNotEmpty() }
                ?.let { refs ->
                    refSetFingerprint(
                        namespace = "conversation-state-head/v1",
                        refs = refs,
                    )
                },
            evidenceHeadFingerprint = refSetFingerprint(
                namespace = "canonical-evidence-head/v1",
                refs = evidenceRefs,
            ),
            ownerObservationPolicyRevision = policySnapshot.revision,
            createdAt = stableContextTime(
                refs = observationRefs + evidenceRefs + conversationRefs,
                sensorSnapshot = sensorSnapshot,
            ),
        )
        return FrozenProductivePersonalContext(
            snapshot = snapshot,
            sensorRegistry = sensorSnapshot,
            binding = PersonalContextBootBinding(
                personalContextSnapshotId = snapshot.id,
                sensorRegistryFingerprint = sensorSnapshot.fingerprint(),
                ownerObservationPolicyRevision = policySnapshot.revision,
            ),
        )
    }

    private suspend fun queryRefs(
        query: PhotonIndexQuery,
    ): List<PhotonRevisionRef> = photons.query(query)
        .distinct()
        .sortedWith(
            compareBy<PhotonRevisionRef> { it.photonId.value }
                .thenBy { it.revision }
        )

    private fun refSetFingerprint(
        namespace: String,
        refs: List<PhotonRevisionRef>,
    ): String = StableFieldIds.fingerprint(
        namespace,
        *refs.map { it.stableKey }.toTypedArray(),
    )

    private suspend fun stableContextTime(
        refs: List<PhotonRevisionRef>,
        sensorSnapshot: AppSensorRegistrySnapshot,
    ): Instant {
        val photonTimes = refs
            .distinct()
            .mapNotNull { ref -> photons.load(ref)?.provenance?.createdAt }
        val sensorTimes = sensorSnapshot.sensors
            .mapNotNull { it.checkpoint?.committedAt }
        return (photonTimes + sensorTimes).maxOrNull() ?: Instant.EPOCH
    }

    private companion object {
        const val MAX_OBSERVATION_REFS = 256
        const val MAX_EVIDENCE_REFS = 256
        const val MAX_CONVERSATION_REFS = 128
    }
}

internal data class KernelWorldGraph(
    val taskRepository: EncryptedTaskRepository,
    val checkpointRepository: EncryptedCheckpointRepository,
    val fieldSnapshotRepository: EncryptedFieldSnapshotRepository,
    val bootReadSession: BootReadSession,
    val fieldThoughtGraphProjectionOutbox: EncryptedFieldThoughtGraphProjectionOutboxRepository,
    val fieldThoughtGraphProjection: FieldThoughtGraphProjectionCoordinator,
    val worldFormulaSnapshotRepository: EncryptedWorldFormulaSnapshotRepository,
    val productiveWorldHeadRepository: EncryptedProductiveWorldHeadRepository,
    val bootEngineCycleRepository: EncryptedBootEngineCycleRepository,
    val extensionRegistryHeadRepository: EncryptedExtensionRegistryHeadRepository,
    val extensionRegistryRehydrator: ExtensionRegistryRehydrator,
    val worldModelRepository: EncryptedWorldModelRepository,
    val worldEquationHeads: EncryptedWorldEquationHeadRepository,
    val worldEquationSpecs: EncryptedWorldEquationSpecRepository,
    val worldEquationEvidence: EncryptedWorldEquationEvidenceRepository,
    val worldEquationAuthority: WorldEquationActivationAuthority,
    val worldEquationAutoEvolution: WorldEquationAutoEvolutionCoordinator,
    val worldEquationSafetyMonitor: WorldEquationPostActivationSafetyMonitor,
    val worldFormulaCoordinator: WorldFormulaCoordinator,
    val bootEngineRuntime: BootEngineRuntime,
    val productiveGoalConvergence: GoalConvergenceDecisionProvider,
)

/**
 * Durable world/equation composition. Owns boot-frozen world inputs, equation authority,
 * productive world persistence/convergence and the self-observation world equation lane.
 */
internal class KernelWorldComposition(
    private val foundation: KernelFoundationGraph,
    private val evolution: KernelEvolutionGraph,
    private val ownerObservationPolicy: OwnerObservationPolicyLedger? = null,
    private val appSensorRegistry: AppSensorRegistry? = null,
) {
    fun compose(): KernelWorldGraph {
        val appContext = foundation.appContext
        val taskRepository = EncryptedTaskRepository(appContext)
        val checkpointRepository = EncryptedCheckpointRepository(appContext)
        val fieldSnapshotRepository = EncryptedFieldSnapshotRepository(appContext)
        val bootReadSession = BootReadSession(
            BootSnapshotLoader(
                photons = PhotonRepositoryBootSource(foundation.store),
                tasks = TaskRepositoryBootSource(taskRepository),
                checkpoints = CheckpointRepositoryBootSource(checkpointRepository),
                capabilities = CapabilityRegistryBootSource(foundation.capabilityRegistry),
                tools = GeneratedToolRegistryBootSource(evolution.generatedTools),
                fieldSnapshots = FieldSnapshotRepositoryBootSource(fieldSnapshotRepository),
            )
        )
        val fieldThoughtGraphProjectionOutbox =
            EncryptedFieldThoughtGraphProjectionOutboxRepository(appContext)
        val fieldThoughtGraphProjection = FieldThoughtGraphProjectionCoordinator(
            outbox = fieldThoughtGraphProjectionOutbox,
            snapshots = fieldSnapshotRepository,
            graph = foundation.thoughtGraph,
        )
        val worldFormulaSnapshotRepository = EncryptedWorldFormulaSnapshotRepository(appContext)
        val productiveWorldHeadRepository = EncryptedProductiveWorldHeadRepository(appContext)
        val bootEngineCycleRepository = EncryptedBootEngineCycleRepository(appContext)
        val extensionRegistrySnapshotRepository =
            EncryptedExtensionRegistrySnapshotRepository(appContext)
        val extensionRegistryHeadRepository =
            EncryptedExtensionRegistryHeadRepository(appContext)
        val extensionRegistryRehydrator = ExtensionRegistryRehydrator(
            heads = extensionRegistryHeadRepository,
            snapshots = extensionRegistrySnapshotRepository,
        )
        val worldModelRepository = EncryptedWorldModelRepository(appContext)
        val cognitiveWorldEquationProfile = CognitiveWorldEquationProfile()
        val worldEquationRegistry = InMemoryWorldEquationRegistry(
            listOf(cognitiveWorldEquationProfile.spec)
        )
        val worldEquationHeads = EncryptedWorldEquationHeadRepository(appContext)
        val worldEquationSpecs = EncryptedWorldEquationSpecRepository(appContext)
        val worldEquationEvidence = EncryptedWorldEquationEvidenceRepository(appContext)
        val worldEquationPromotionEvaluator = WorldEquationPromotionEvaluator(
            WorldEquationPromotionPolicy.V1
        )
        val worldEquationEvidenceCoordinator = WorldEquationEvidenceCoordinator(
            repository = worldEquationEvidence,
            evaluator = worldEquationPromotionEvaluator,
        )
        val worldEquationAdmissionGate = WorldEquationEvolutionAdmissionGate(
            evidence = worldEquationEvidence,
            evaluator = worldEquationPromotionEvaluator,
        )
        val worldEquationAuthority = WorldEquationActivationAuthority(
            equations = worldEquationRegistry,
            heads = worldEquationHeads,
            baseline = cognitiveWorldEquationProfile.spec,
            specs = worldEquationSpecs,
            admissionVerifier = worldEquationAdmissionGate,
        )
        val worldEquationAutoEvolution = WorldEquationAutoEvolutionCoordinator(
            evidence = worldEquationEvidence,
            evidenceCoordinator = worldEquationEvidenceCoordinator,
            shadow = WorldEquationShadowEvaluator(),
            admissionGate = worldEquationAdmissionGate,
            authority = worldEquationAuthority,
        )
        val worldEquationSafetyMonitor = WorldEquationPostActivationSafetyMonitor(
            evidence = worldEquationEvidence,
            evidenceCoordinator = worldEquationEvidenceCoordinator,
            authority = worldEquationAuthority,
        )
        WorldEquationPostActivationSafetyRuntimeRegistry.install(worldEquationSafetyMonitor)
        val worldFormulaCoordinator = WorldFormulaCoordinator(
            equations = worldEquationRegistry,
            snapshots = worldFormulaSnapshotRepository,
        )
        val selfStateWorldEquationProfile = SelfStateWorldEquationProfile()
        SelfStateWorldFormulaRuntimeRegistry.install(
            SelfStateWorldFormulaEvaluator(
                profile = selfStateWorldEquationProfile,
                coordinator = WorldFormulaCoordinator(
                    equations = InMemoryWorldEquationRegistry(
                        listOf(selfStateWorldEquationProfile.spec)
                    ),
                    snapshots = SelfStateWorldFormulaSnapshotRepository(),
                    executionPolicy = WorldFormulaExecutionPolicy.SELF_OBSERVATION,
                ),
            )
        )
        val productiveWorldHeadCommitter = ProductiveWorldHeadCommitter(
            snapshots = worldFormulaSnapshotRepository,
            heads = productiveWorldHeadRepository,
        )
        val personalContextBindingSource = if (
            ownerObservationPolicy != null && appSensorRegistry != null
        ) {
            ProductivePersonalContextBindingSource(
                photons = foundation.store,
                sensors = appSensorRegistry,
                ownerObservationPolicy = ownerObservationPolicy,
            )
        } else {
            null
        }
        val bootEngineRuntime = BootEngineRuntime(
            cycles = bootEngineCycleRepository,
            worldHeads = productiveWorldHeadRepository,
            worldCoordinator = worldFormulaCoordinator,
            worldCommitter = productiveWorldHeadCommitter,
            newCycleId = {
                CognitiveCycleId("cycle:${java.util.UUID.randomUUID()}")
            },
        )
        val productiveDecisionCoordinator = DurableConvergenceDecisionCoordinator(
            EncryptedConvergenceDecisionCheckpointRepository(appContext),
        )
        val productiveWorldConvergence = WorldFormulaBoundConvergenceService(
            bootEngine = bootEngineRuntime,
            worldSnapshots = worldFormulaSnapshotRepository,
            decisions = productiveDecisionCoordinator,
        )
        val productiveGoalConvergence = GoalConvergenceDecisionProvider(
            productiveConvergence =
                DefaultProductiveConvergenceAuthority(productiveWorldConvergence),
            bootEngine = bootEngineRuntime,
            photons = foundation.store,
            cycleInputs = GoalCycleFrozenInputSource { workingSet, routing ->
                val hardware = foundation.cycleResourceIntelligence.currentHardwareSnapshot()
                val calibration = foundation.learnedFieldCalibration.profile()
                val strategyFingerprint = StableFieldIds.fingerprint(
                    "productive-goal-strategy-snapshot/v1",
                    calibration.fingerprint,
                    routing.plan.goal.intent.name,
                    *buildList {
                        routing.selectedProviders.entries
                            .sortedBy { it.key.value }
                            .forEach { (capabilityId, provider) ->
                                add(
                                    "provider:${capabilityId.value}:${provider.providerId}:" +
                                        "${provider.state.name}:${provider.trustLevel.name}:" +
                                        "${java.lang.Double.toHexString(provider.reliability)}:" +
                                        java.lang.Double.toHexString(provider.cost)
                                )
                            }
                        routing.blockingGaps
                            .sortedBy { it.requirement.capabilityId.value }
                            .forEach { gap ->
                                add(
                                    "gap:${gap.requirement.capabilityId.value}:${gap.type.name}:" +
                                        gap.requirement.severity.name
                                )
                                gap.candidateProviderIds.sorted().forEach { candidate ->
                                    add("gap-candidate:${gap.requirement.capabilityId.value}:$candidate")
                                }
                            }
                    }.toTypedArray(),
                )
                BootEngineFrozenInputs(
                    representationSnapshotId = workingSet.sourceSnapshotId,
                    strategySnapshotId = "goal-strategy:$strategyFingerprint",
                    equationVersion = worldEquationAuthority.activeVersion(),
                    resourceSnapshotId = "hardware-state:${hardware.fingerprint()}",
                    perceptionBinding = personalContextBindingSource
                        ?.freeze()
                        ?.binding,
                )
            },
        )

        return KernelWorldGraph(
            taskRepository = taskRepository,
            checkpointRepository = checkpointRepository,
            fieldSnapshotRepository = fieldSnapshotRepository,
            bootReadSession = bootReadSession,
            fieldThoughtGraphProjectionOutbox = fieldThoughtGraphProjectionOutbox,
            fieldThoughtGraphProjection = fieldThoughtGraphProjection,
            worldFormulaSnapshotRepository = worldFormulaSnapshotRepository,
            productiveWorldHeadRepository = productiveWorldHeadRepository,
            bootEngineCycleRepository = bootEngineCycleRepository,
            extensionRegistryHeadRepository = extensionRegistryHeadRepository,
            extensionRegistryRehydrator = extensionRegistryRehydrator,
            worldModelRepository = worldModelRepository,
            worldEquationHeads = worldEquationHeads,
            worldEquationSpecs = worldEquationSpecs,
            worldEquationEvidence = worldEquationEvidence,
            worldEquationAuthority = worldEquationAuthority,
            worldEquationAutoEvolution = worldEquationAutoEvolution,
            worldEquationSafetyMonitor = worldEquationSafetyMonitor,
            worldFormulaCoordinator = worldFormulaCoordinator,
            bootEngineRuntime = bootEngineRuntime,
            productiveGoalConvergence = productiveGoalConvergence,
        )
    }
}
