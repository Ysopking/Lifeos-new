package app.lifeos.core.runtime.goal

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.language.GoalFrame
import app.lifeos.core.language.IntentType
import app.lifeos.core.language.LanguageCode
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonIndexQuery
import app.lifeos.core.model.PhotonIndexReport
import app.lifeos.core.model.PhotonLoadReport
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.PhotonRevisionWriteResult
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.RevisionedPhotonRepository
import app.lifeos.core.runtime.boot.BootEngineCycle
import app.lifeos.core.runtime.boot.BootEngineCycleLoadReport
import app.lifeos.core.runtime.boot.BootEngineCycleRepository
import app.lifeos.core.runtime.boot.BootEngineFrozenInputs
import app.lifeos.core.runtime.boot.BootEngineRuntime
import app.lifeos.core.runtime.capability.CapabilityGap
import app.lifeos.core.runtime.capability.CapabilityGapType
import app.lifeos.core.runtime.capability.GoalCapabilityResolution
import app.lifeos.core.runtime.capability.LanguageGoalCapabilityMapper
import app.lifeos.core.runtime.convergence.ConvergenceCoordinator
import app.lifeos.core.runtime.convergence.ConvergenceDecisionCheckpoint
import app.lifeos.core.runtime.convergence.ConvergenceDecisionCheckpointId
import app.lifeos.core.runtime.convergence.ConvergenceDecisionCheckpointLoadReport
import app.lifeos.core.runtime.convergence.ConvergenceDecisionCheckpointRepository
import app.lifeos.core.runtime.convergence.ConvergenceDecisionCheckpointWriteResult
import app.lifeos.core.runtime.convergence.ConvergenceDecisionRequest
import app.lifeos.core.runtime.convergence.ConvergenceDecisionState
import app.lifeos.core.runtime.convergence.DurableConvergenceDecisionCoordinator
import app.lifeos.core.runtime.convergence.ProductiveConvergenceAuthority
import app.lifeos.core.runtime.convergence.ProductiveConvergenceInput
import app.lifeos.core.runtime.convergence.ProductiveConvergenceResult
import app.lifeos.core.runtime.convergence.WorldFormulaBoundConvergenceRequest
import app.lifeos.core.runtime.world.CognitiveCycleId
import app.lifeos.core.runtime.world.CognitiveWorldEquationProfile
import app.lifeos.core.runtime.world.InMemoryWorldEquationRegistry
import app.lifeos.core.runtime.world.ProductiveWorldHead
import app.lifeos.core.runtime.world.ProductiveWorldHeadCommitter
import app.lifeos.core.runtime.world.ProductiveWorldHeadRepository
import app.lifeos.core.runtime.world.WorldFormulaCoordinator
import app.lifeos.core.runtime.world.WorldFormulaSnapshot
import app.lifeos.core.runtime.world.WorldFormulaSnapshotLoadReport
import app.lifeos.core.runtime.world.WorldFormulaSnapshotNamespace
import app.lifeos.core.runtime.world.WorldFormulaSnapshotRef
import app.lifeos.core.runtime.world.WorldFormulaSnapshotRepository
import app.lifeos.core.runtime.world.WorldFormulaStatus
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GoalConvergenceDecisionProviderTest {
    private val at = Instant.parse("2026-09-11T12:05:00Z")

    @Test
    fun `persisted high confidence goal uses productive authority and checkpoints before exposure`() = runBlocking {
        val checkpoints = MemoryCheckpointRepository()
        val photons = MemoryPhotonRepository()
        val boot = bootEngine()
        boot.startCycle(frozenInputs())
        val provider = GoalConvergenceDecisionProvider(
            productiveConvergence = TestProductiveConvergenceAuthority(
                DurableConvergenceDecisionCoordinator(checkpoints)
            ),
            bootEngine = boot,
            photons = photons,
        )
        val goal = goal(IntentType.QUERY)
        val source = sourcePhoton()
        val goalId = PhotonId("goal-v5")
        photons.save(goalPhoton(goalId, goal))
        val routing = GoalCapabilityResolution(
            plan = LanguageGoalCapabilityMapper().plan(goal),
            selectedProviders = emptyMap(),
            gaps = emptyList(),
        )

        val first = provider.decide(
            goal = goal,
            routing = routing,
            sourcePhoton = source,
            goalPhotonId = goalId,
            at = at,
        )
        val second = provider.decide(
            goal = goal,
            routing = routing,
            sourcePhoton = source,
            goalPhotonId = goalId,
            at = at,
        )

        assertEquals(ConvergenceDecisionState.ACTIONABLE, first.decision.state)
        assertEquals(first, second)
        assertEquals(1, checkpoints.checkpoints.size)
        assertEquals(first, checkpoints.checkpoints.values.single())
        assertTrue(first.decision.reasons.contains("all-convergence-action-gates-satisfied"))
    }

    @Test
    fun `blocking routed capability gap remains capability required through productive authority`() = runBlocking {
        val checkpoints = MemoryCheckpointRepository()
        val photons = MemoryPhotonRepository()
        val boot = bootEngine()
        boot.startCycle(frozenInputs())
        val provider = GoalConvergenceDecisionProvider(
            productiveConvergence = TestProductiveConvergenceAuthority(
                DurableConvergenceDecisionCoordinator(checkpoints)
            ),
            bootEngine = boot,
            photons = photons,
        )
        val goal = goal(IntentType.QUERY)
        val goalId = PhotonId("goal-gap")
        photons.save(goalPhoton(goalId, goal))
        val plan = LanguageGoalCapabilityMapper().plan(goal)
        val gap = CapabilityGap(
            requirement = plan.requirements.single(),
            type = CapabilityGapType.CAPABILITY_MISSING,
            candidateProviderIds = emptyList(),
        )
        val routing = GoalCapabilityResolution(
            plan = plan,
            selectedProviders = emptyMap(),
            gaps = listOf(gap),
        )

        val checkpoint = provider.decide(
            goal = goal,
            routing = routing,
            sourcePhoton = sourcePhoton(),
            goalPhotonId = goalId,
            at = at,
        )

        assertEquals(ConvergenceDecisionState.CAPABILITY_REQUIRED, checkpoint.decision.state)
        assertEquals(listOf(gap), checkpoint.decision.capabilityGaps)
        assertTrue(checkpoint.decision.selectedHypothesisIds.isEmpty())
        assertEquals(checkpoint, checkpoints.checkpoints.values.single())
    }

    private fun bootEngine(): BootEngineRuntime {
        val cycles = MemoryCycleRepository()
        val heads = MemoryWorldHeadRepository()
        val snapshots = MemoryWorldSnapshotRepository()
        val profile = CognitiveWorldEquationProfile()
        val coordinator = WorldFormulaCoordinator(
            equations = InMemoryWorldEquationRegistry(listOf(profile.spec)),
            snapshots = snapshots,
        )
        return BootEngineRuntime(
            cycles = cycles,
            worldHeads = heads,
            worldCoordinator = coordinator,
            worldCommitter = ProductiveWorldHeadCommitter(snapshots, heads),
            newCycleId = { CognitiveCycleId("goal-test-cycle") },
        )
    }

    private fun frozenInputs() = BootEngineFrozenInputs(
        representationSnapshotId = "representation-test",
        strategySnapshotId = "strategy-test",
        equationVersion = CognitiveWorldEquationProfile.VERSION,
        resourceSnapshotId = "resource-test",
    )

    private fun goal(intent: IntentType) = GoalFrame(
        intent = intent,
        objective = "Resolve persisted user goal",
        entities = emptyList(),
        references = emptyList(),
        constraints = emptyList(),
        ambiguities = emptyList(),
        confidence = 1.0,
        language = LanguageCode.EN,
    )

    private fun sourcePhoton() = Photon(
        id = PhotonId("source-v5"),
        revision = 1,
        content = "What is already known?",
        semanticMass = 1.0,
        energy = 1.0,
        confidence = 1.0,
        provenance = Provenance(
            source = "unit-test",
            actor = "user",
            createdAt = at,
        ),
        tags = setOf("chat"),
    )

    private fun goalPhoton(id: PhotonId, goal: GoalFrame) = Photon(
        id = id,
        revision = 1,
        content = goal.objective,
        semanticMass = 1.0,
        energy = 1.0,
        confidence = goal.confidence,
        provenance = Provenance(
            source = "unit-test-goal",
            actor = "system",
            createdAt = at,
        ),
        tags = setOf("goal"),
    )

    private class TestProductiveConvergenceAuthority(
        private val decisions: DurableConvergenceDecisionCoordinator,
    ) : ProductiveConvergenceAuthority {
        override suspend fun decide(input: ProductiveConvergenceInput): ProductiveConvergenceResult {
            val convergence = ConvergenceCoordinator().coordinate(input.source)
            val checkpoint = decisions.decide(
                ConvergenceDecisionRequest(
                    source = input.source,
                    convergence = convergence,
                    capabilityGaps = input.capabilityGaps,
                    workingSetFingerprint = input.workingSet.fingerprint,
                )
            )
            val snapshotId = "test-world-snapshot"
            val ref = WorldFormulaSnapshotRef(
                namespace = WorldFormulaSnapshotNamespace.PRODUCTIVE,
                snapshotId = snapshotId,
                equationVersion = input.cycle.context.equationVersion,
                cycleId = input.cycle.cycleId,
            )
            val binding = WorldFormulaBoundConvergenceRequest(
                workingSetFingerprint = input.workingSet.fingerprint,
                sourceRequestId = input.source.id,
                fieldConvergenceFingerprint = StableFieldIds.fingerprint(
                    "goal-test-field-convergence",
                    input.source.id,
                ),
                productiveRequestId = StableFieldIds.fingerprint(
                    "goal-test-productive-request",
                    input.source.id,
                ),
                cycleId = input.cycle.cycleId.value,
                cycleContextFingerprint = input.cycle.context.fingerprint(),
                worldSnapshotRef = ref,
                worldSnapshotFingerprint = StableFieldIds.fingerprint(
                    "goal-test-world-snapshot",
                    snapshotId,
                ),
                worldStatus = WorldFormulaStatus.CONVERGED,
                source = input.source,
            )
            return ProductiveConvergenceResult.Decided(
                binding = binding,
                checkpoint = checkpoint,
                worldSnapshotId = snapshotId,
            )
        }
    }

    private class MemoryCheckpointRepository : ConvergenceDecisionCheckpointRepository {
        val checkpoints = linkedMapOf<ConvergenceDecisionCheckpointId, ConvergenceDecisionCheckpoint>()

        override suspend fun save(
            checkpoint: ConvergenceDecisionCheckpoint,
        ): ConvergenceDecisionCheckpointWriteResult {
            val previous = checkpoints.putIfAbsent(checkpoint.id, checkpoint)
            return if (previous == null) {
                ConvergenceDecisionCheckpointWriteResult.Stored(checkpoint)
            } else {
                require(previous == checkpoint)
                ConvergenceDecisionCheckpointWriteResult.Duplicate(previous)
            }
        }

        override suspend fun load(
            id: ConvergenceDecisionCheckpointId,
        ): ConvergenceDecisionCheckpoint? = checkpoints[id]

        override suspend fun loadReport(): ConvergenceDecisionCheckpointLoadReport =
            ConvergenceDecisionCheckpointLoadReport(
                checkpoints = checkpoints.values.toList(),
                unreadableEntries = emptyList(),
            )
    }

    private class MemoryPhotonRepository : RevisionedPhotonRepository {
        private val values = linkedMapOf<PhotonRevisionRef, Photon>()

        override suspend fun save(photon: Photon) {
            values[PhotonRevisionRef(photon.id, photon.revision)] = photon
        }

        override suspend fun load(id: PhotonId): Photon? =
            values.values.filter { it.id == id }.maxByOrNull { it.revision }

        override suspend fun load(ref: PhotonRevisionRef): Photon? = values[ref]

        override suspend fun latestRef(id: PhotonId): PhotonRevisionRef? =
            values.keys.filter { it.photonId == id }.maxByOrNull { it.revision }

        override suspend fun saveRevision(
            photon: Photon,
            expectedPreviousRevision: Long?,
        ): PhotonRevisionWriteResult {
            val previous = load(photon.id)
            if (previous?.revision != expectedPreviousRevision) {
                return PhotonRevisionWriteResult.Conflict(photon, previous, "test-conflict")
            }
            save(photon)
            return if (previous == null) {
                PhotonRevisionWriteResult.Created(photon)
            } else {
                PhotonRevisionWriteResult.Advanced(photon, previous)
            }
        }

        override suspend fun query(query: PhotonIndexQuery): List<PhotonRevisionRef> =
            values.keys
                .filter { query.ids.isEmpty() || it.photonId in query.ids }
                .sortedWith(compareBy({ it.photonId.value }, { it.revision }))
                .take(query.limit)

        override suspend fun indexReport(): PhotonIndexReport {
            val latest = values.keys.groupBy { it.photonId }
                .mapValues { (_, refs) -> refs.maxBy { it.revision } }
            return PhotonIndexReport(
                formatVersion = 1,
                entryCount = values.size,
                livePhotonCount = latest.size,
                tombstonedPhotonCount = 0,
                latestRefs = latest,
            )
        }

        override suspend fun loadReport(): PhotonLoadReport =
            PhotonLoadReport(values.values.toList(), emptyList())

        override suspend fun loadAll(): List<Photon> = values.values.toList()

        override suspend fun delete(id: PhotonId) {
            values.keys.filter { it.photonId == id }.toList().forEach(values::remove)
        }
    }

    private class MemoryCycleRepository : BootEngineCycleRepository {
        private var cycle: BootEngineCycle? = null

        override suspend fun create(cycle: BootEngineCycle): Boolean {
            if (this.cycle != null) return false
            this.cycle = cycle
            return true
        }

        override suspend fun load(cycleId: CognitiveCycleId): BootEngineCycle? =
            cycle?.takeIf { it.cycleId == cycleId }

        override suspend fun loadActive(): BootEngineCycle? =
            cycle?.takeIf { !it.terminal }

        override suspend fun loadLatestCommitted(): BootEngineCycle? =
            cycle?.takeIf { it.state.name == "COMMITTED" }

        override suspend fun compareAndSet(
            expectedFingerprint: String,
            next: BootEngineCycle,
        ): Boolean {
            val current = cycle ?: return false
            if (current.fingerprint != expectedFingerprint) return false
            cycle = next
            return true
        }

        override suspend fun loadReport(): BootEngineCycleLoadReport =
            BootEngineCycleLoadReport(
                activeCycle = loadActive(),
                latestCommitted = loadLatestCommitted(),
                corrupted = false,
                message = null,
            )
    }

    private class MemoryWorldHeadRepository : ProductiveWorldHeadRepository {
        private var head: ProductiveWorldHead? = null

        override suspend fun load(): ProductiveWorldHead? = head

        override suspend fun compareAndSet(
            expectedRevision: Long?,
            next: ProductiveWorldHead,
        ): Boolean {
            if (head?.revision != expectedRevision) return false
            head = next
            return true
        }
    }

    private class MemoryWorldSnapshotRepository : WorldFormulaSnapshotRepository {
        private val values = linkedMapOf<String, WorldFormulaSnapshot>()

        override suspend fun save(snapshot: WorldFormulaSnapshot) {
            values[snapshot.id] = snapshot
        }

        override suspend fun load(id: String): WorldFormulaSnapshot? = values[id]

        override suspend fun loadLatest(): WorldFormulaSnapshot? = values.values.lastOrNull()

        override suspend fun loadReport(): WorldFormulaSnapshotLoadReport =
            WorldFormulaSnapshotLoadReport(values.values.toList(), emptyList())

        override suspend fun delete(id: String) {
            values.remove(id)
        }
    }
}
