package app.lifeos.next

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lifeos.core.data.boot.EncryptedBootEngineCycleRepository
import app.lifeos.core.data.goal.EncryptedGoalCognitiveCycleBindingRepository
import app.lifeos.core.data.evolution.EncryptedWorldEquationEvidenceRepository
import app.lifeos.core.data.learning.EncryptedLearningWatermarkRepository
import app.lifeos.core.data.world.EncryptedProductiveWorldHeadRepository
import app.lifeos.core.data.world.EncryptedWorldEquationHeadRepository
import app.lifeos.core.data.world.EncryptedWorldEquationSpecRepository
import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.Provenance
import app.lifeos.core.runtime.evolution.WorldEquationEvidenceCoordinator
import app.lifeos.core.runtime.evolution.WorldEquationEvidencePartition
import app.lifeos.core.runtime.evolution.WorldEquationEvaluationProtocol
import app.lifeos.core.runtime.evolution.WorldEquationEvolutionAdmissionGate
import app.lifeos.core.runtime.evolution.WorldEquationPrimaryMetric
import app.lifeos.core.runtime.evolution.WorldEquationPromotionEvaluator
import app.lifeos.core.runtime.evolution.WorldEquationPromotionPolicy
import app.lifeos.core.runtime.evolution.WorldEquationRunMetrics
import app.lifeos.core.runtime.evolution.WorldEquationShadowObservation
import app.lifeos.core.runtime.goal.GoalCognitiveCycleBindingRecord
import app.lifeos.core.runtime.goal.GoalCognitiveCycleBindingState
import app.lifeos.core.runtime.goal.GoalConvergenceCycleBinding
import app.lifeos.core.runtime.goal.GoalPlanId
import app.lifeos.core.runtime.learning.LearningSourceId
import app.lifeos.core.runtime.learning.LearningWatermarkLoadResult
import app.lifeos.core.runtime.learning.LearningWatermarkState
import app.lifeos.core.runtime.learning.LearningWatermarkWriteResult
import app.lifeos.core.runtime.world.CognitiveWorldEquationProfile
import app.lifeos.core.runtime.world.InMemoryWorldEquationRegistry
import app.lifeos.core.runtime.world.WorldEquationActivationAuthority
import app.lifeos.core.runtime.world.WorldFormulaStatus
import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Level7TruthClosureGoldDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val root: File
        get() = instrumentation.targetContext.filesDir.resolve("level7-truth-closure-gold")
    private val marker: File
        get() = root.resolve("truth-closure-checkpoint.txt")

    @Test
    fun seedExactAuthorityChainBeforeProcessDeath() = runBlocking {
        root.deleteRecursively()
        assertTrue(root.mkdirs())
        val context = Level7DeviceFixtures.context(instrumentation.targetContext, root)

        val baselineSpec = CognitiveWorldEquationProfile().spec
        val firstCoefficient = baselineSpec.stableCoefficients().first()
        val candidateSpec = baselineSpec.copy(
            version = "lifeos-world-cognitive-v2",
            coefficients = baselineSpec.coefficients.map {
                if (it.id == firstCoefficient.id) {
                    it.copy(
                        multiplier = if (it.multiplier < 0.9) {
                            it.multiplier + 0.05
                        } else {
                            it.multiplier - 0.05
                        },
                    )
                } else {
                    it
                }
            },
        )
        val equationRepo = EncryptedWorldEquationHeadRepository(context)
        val equationSpecs = EncryptedWorldEquationSpecRepository(context)
        val equationEvidence = EncryptedWorldEquationEvidenceRepository(context)
        val promotionEvaluator = WorldEquationPromotionEvaluator(
            WorldEquationPromotionPolicy.V1
        )
        val evidenceCoordinator = WorldEquationEvidenceCoordinator(
            repository = equationEvidence,
            evaluator = promotionEvaluator,
        )
        val protocol = WorldEquationEvaluationProtocol(
            version = "b200-truth-closure-protocol-v1",
            primaryMetric = WorldEquationPrimaryMetric.STABILIZATION_ITERATIONS,
            minimumIndependentRuns = 2,
            minimumDistinctWorkloads = 1,
            minimumActiveObservationsPerChangedCoefficient = 1,
        )
        evidenceCoordinator.beginShadow(candidateSpec, baselineSpec, protocol)
        evidenceCoordinator.recordObservation(
            candidateSpec,
            baselineSpec,
            goldObservation(
                baselineSpec = baselineSpec,
                candidateSpec = candidateSpec,
                activeCoefficientId = firstCoefficient.id,
                runId = "b200-run-1",
            ),
        )
        evidenceCoordinator.recordObservation(
            candidateSpec,
            baselineSpec,
            goldObservation(
                baselineSpec = baselineSpec,
                candidateSpec = candidateSpec,
                activeCoefficientId = firstCoefficient.id,
                runId = "b200-run-2",
                partition = WorldEquationEvidencePartition.HOLDOUT,
            ),
        )
        val admissionGate = WorldEquationEvolutionAdmissionGate(
            evidence = equationEvidence,
            evaluator = promotionEvaluator,
        )
        val equations = InMemoryWorldEquationRegistry(listOf(baselineSpec))
        val equationAuthority = WorldEquationActivationAuthority(
            equations = equations,
            heads = equationRepo,
            baseline = baselineSpec,
            specs = equationSpecs,
            admissionVerifier = admissionGate,
        )
        assertEquals(baselineSpec.version, equationAuthority.activeVersion())
        val baselineEquation = requireNotNull(equationRepo.load())

        val admission = admissionGate.admit(
            candidate = candidateSpec,
            baseline = baselineSpec,
        )
        val validation = admission.validation
        val promotedEquation = equationAuthority.promote(
            candidate = candidateSpec,
            admission = admission,
            expectedHeadFingerprint = baselineEquation.fingerprint,
        )
        assertEquals(candidateSpec.version, promotedEquation.activeEquationVersion)
        assertEquals(baselineSpec.version, promotedEquation.predecessorEquationVersion)
        assertEquals(validation.promotionDecisionId, promotedEquation.sourcePromotionId)
        assertNotEquals(baselineEquation.fingerprint, promotedEquation.fingerprint)

        val worldRepo = EncryptedProductiveWorldHeadRepository(context)
        val baselineWorld = Level7DeviceFixtures.worldHead(
            revision = 1L,
            snapshotId = "world-b200-v1",
            predecessor = null,
            cycle = "cycle-b200-v1",
            equationVersion = baselineSpec.version,
        )
        assertTrue(worldRepo.compareAndSet(null, baselineWorld))
        val promotedWorld = Level7DeviceFixtures.worldHead(
            revision = 2L,
            snapshotId = "world-b200-v2",
            predecessor = baselineWorld.activeSnapshot.snapshotId,
            cycle = "cycle-b200-v2",
            equationVersion = candidateSpec.version,
        )
        assertTrue(worldRepo.compareAndSet(1L, promotedWorld))

        val cycleRepo = EncryptedBootEngineCycleRepository(context)
        val prepared = Level7DeviceFixtures.preparedCycle(
            cycle = "cycle-b200-v2",
            equationVersion = candidateSpec.version,
            previousSnapshotId = baselineWorld.activeSnapshot.snapshotId,
        )
        assertTrue(cycleRepo.create(prepared))
        val evaluated = prepared.evaluated(
            requestId = "world-request-b200-v2",
            snapshotId = promotedWorld.activeSnapshot.snapshotId,
        )
        assertTrue(cycleRepo.compareAndSet(prepared.fingerprint, evaluated))
        val committed = evaluated.committed(promotedWorld.revision)
        assertTrue(cycleRepo.compareAndSet(evaluated.fingerprint, committed))

        val planId = GoalPlanId(
            GoalPlanId.PREFIX + StableFieldIds.fingerprint(
                "level7-b200-plan/v1",
                candidateSpec.version,
                promotedWorld.fingerprint,
            )
        )
        val sourceGoalId = PhotonId("goal-b200-truth-closure")
        val cycleBinding = GoalConvergenceCycleBinding(
            cycleId = committed.cycleId,
            sourceWorldSnapshotId = promotedWorld.activeSnapshot.snapshotId,
            equationVersion = committed.context.equationVersion,
        )
        val bindingRepo = EncryptedGoalCognitiveCycleBindingRepository(context)
        val converged = GoalCognitiveCycleBindingRecord.converged(
            planId = planId,
            sourceGoalPhotonId = sourceGoalId,
            sourceGoalPhotonRevision = 1L,
            cycleBinding = cycleBinding,
        )
        assertTrue(bindingRepo.compareAndSet(planId, null, converged))

        val outcome = Photon(
            id = PhotonId("outcome-b200-truth-closure"),
            content = "B200 verified outcome",
            semanticMass = 1.0,
            energy = 1.0,
            confidence = 1.0,
            provenance = Provenance(
                source = "level7-b200-gold",
                actor = "Level7TruthClosureGoldDeviceTest",
                createdAt = Instant.parse("2026-09-19T01:55:00Z"),
                parentIds = setOf(sourceGoalId),
            ),
            tags = setOf("outcome", "verified", "level7-gold"),
        )
        val recorded = converged.recordOutcome(outcome)
        assertTrue(bindingRepo.compareAndSet(planId, converged.revision, recorded))

        val learningSource = LearningSourceId("level7-b200-goal-outcome")
        val learningEventFingerprint = StableFieldIds.fingerprint(
            "level7-b200-learning-event/v1",
            outcome.id.value,
            outcome.revision.toString(),
            committed.fingerprint,
        )
        val learningState = LearningWatermarkState.empty().advance(
            sourceId = learningSource,
            sequence = 1L,
            eventId = "level7-b200-outcome-event",
            eventFingerprint = learningEventFingerprint,
        )
        val learningRepo = EncryptedLearningWatermarkRepository(context)
        assertTrue(
            learningRepo.compareAndSet(null, learningState) is LearningWatermarkWriteResult.Saved
        )

        val learned = recorded.markLearned(
            outcomeWorldSnapshotId = "world-b200-outcome-v2",
            learningWatermarkRevision = learningState.revision,
        )
        assertTrue(bindingRepo.compareAndSet(planId, recorded.revision, learned))
        assertEquals(GoalCognitiveCycleBindingState.LEARNED, learned.state)

        marker.writeText(
            listOf(
                baselineEquation.fingerprint,
                promotedEquation.fingerprint,
                baselineWorld.fingerprint,
                promotedWorld.fingerprint,
                committed.fingerprint,
                learned.fingerprint,
                baselineSpec.version,
                candidateSpec.version,
                promotedWorld.activeSnapshot.snapshotId,
                learned.outcomeWorldSnapshotId.orEmpty(),
                learningState.revision.toString(),
                validation.evidenceRecordFingerprint,
                validation.protocolFingerprint,
                validation.policyFingerprint,
                validation.promotionDecisionId,
                planId.value,
                outcome.id.value,
                sourceGoalId.value,
                "rollback-b200-v2-to-v1",
            ).joinToString("\n")
        )
        assertTrue(marker.isFile)
    }

    @Test
    fun recoverExactAuthorityChainRollbackAndSealGold() = runBlocking {
        assertTrue(marker.isFile)
        val expected = marker.readLines()
        assertEquals(19, expected.size)
        val context = Level7DeviceFixtures.context(instrumentation.targetContext, root)

        val baselineSpec = CognitiveWorldEquationProfile().spec
        val equationRepo = EncryptedWorldEquationHeadRepository(context)
        val equationSpecs = EncryptedWorldEquationSpecRepository(context)
        val candidateSpec = requireNotNull(equationSpecs.load(expected[7]))
        val promotedEquation = requireNotNull(equationRepo.load())
        assertEquals(expected[1], promotedEquation.fingerprint)
        assertEquals(expected[7], promotedEquation.activeEquationVersion)
        assertEquals(expected[6], promotedEquation.predecessorEquationVersion)
        assertEquals(expected[14], promotedEquation.sourcePromotionId)

        val worldRepo = EncryptedProductiveWorldHeadRepository(context)
        val promotedWorld = requireNotNull(worldRepo.load())
        assertEquals(expected[3], promotedWorld.fingerprint)
        assertEquals(expected[8], promotedWorld.activeSnapshot.snapshotId)

        val cycle = requireNotNull(
            EncryptedBootEngineCycleRepository(context).loadLatestCommitted()
        )
        assertEquals(expected[4], cycle.fingerprint)
        assertEquals(promotedWorld.activeSnapshot.snapshotId, cycle.worldSnapshotId)
        assertEquals(promotedWorld.revision, cycle.productiveHeadRevision)
        assertEquals(promotedEquation.activeEquationVersion, cycle.context.equationVersion)

        val planId = GoalPlanId(expected[15])
        val bindingRepo = EncryptedGoalCognitiveCycleBindingRepository(context)
        val learned = requireNotNull(bindingRepo.load(planId))
        assertEquals(expected[5], learned.fingerprint)
        assertEquals(GoalCognitiveCycleBindingState.LEARNED, learned.state)
        assertEquals(cycle.cycleId, learned.cycleBinding.cycleId)
        assertEquals(cycle.worldSnapshotId, learned.cycleBinding.sourceWorldSnapshotId)
        assertEquals(cycle.context.equationVersion, learned.cycleBinding.equationVersion)
        assertEquals(expected[16], learned.outcomePhotonId?.value)
        assertEquals(expected[9], learned.outcomeWorldSnapshotId)
        assertEquals(expected[10].toLong(), learned.learningWatermarkRevision)

        val learningLoaded = EncryptedLearningWatermarkRepository(context).load()
        assertTrue(learningLoaded is LearningWatermarkLoadResult.Loaded)
        val learningState = (learningLoaded as LearningWatermarkLoadResult.Loaded).state
        assertEquals(expected[10].toLong(), learningState.revision)
        assertEquals(1, learningState.sources.size)

        val replay = learned.markLearned(
            outcomeWorldSnapshotId = expected[9],
            learningWatermarkRevision = expected[10].toLong(),
        )
        assertEquals(learned, replay)

        val equationAuthority = WorldEquationActivationAuthority(
            equations = InMemoryWorldEquationRegistry(listOf(baselineSpec)),
            heads = equationRepo,
            baseline = baselineSpec,
            specs = equationSpecs,
        )
        assertEquals(candidateSpec.version, equationAuthority.activeVersion())
        val restoredEquation = equationAuthority.rollbackToPredecessor(
            expectedCurrentVersion = candidateSpec.version,
            rollbackDecisionId = expected[18],
        )
        assertEquals(baselineSpec.version, restoredEquation.activeEquationVersion)
        assertEquals(candidateSpec.version, restoredEquation.predecessorEquationVersion)

        val rollbackWorld = Level7DeviceFixtures.worldHead(
            revision = promotedWorld.revision + 1L,
            snapshotId = "world-b200-v1",
            predecessor = promotedWorld.activeSnapshot.snapshotId,
            cycle = "cycle-b200-rollback",
            equationVersion = restoredEquation.activeEquationVersion,
        )
        assertTrue(worldRepo.compareAndSet(promotedWorld.revision, rollbackWorld))
        val durableRollbackWorld = requireNotNull(
            EncryptedProductiveWorldHeadRepository(context).load()
        )
        assertEquals(restoredEquation.activeEquationVersion, durableRollbackWorld.equationVersion)
        assertEquals("world-b200-v1", durableRollbackWorld.activeSnapshot.snapshotId)

        val seal = StableFieldIds.fingerprint(
            "level7-truth-closure-gold/v1",
            expected[0],
            expected[1],
            restoredEquation.fingerprint,
            expected[2],
            expected[3],
            durableRollbackWorld.fingerprint,
            expected[4],
            expected[5],
            expected[11],
            expected[12],
            expected[13],
            expected[14],
            expected[9],
            expected[10],
        )
        println("LEVEL7_TRUTH_CLOSURE_GOLD=$seal")
        assertTrue(seal.isNotBlank())
    }
    private fun goldObservation(
        baselineSpec: app.lifeos.core.field.world.WorldEquationSpec,
        candidateSpec: app.lifeos.core.field.world.WorldEquationSpec,
        activeCoefficientId: app.lifeos.core.field.world.WorldCoefficientId,
        runId: String,
        partition: WorldEquationEvidencePartition = WorldEquationEvidencePartition.SHADOW,
    ) = WorldEquationShadowObservation(
        caseFingerprint = StableFieldIds.fingerprint("b200-shadow-case", runId),
        runId = runId,
        workloadId = "b200-truth-closure-workload",
        partition = partition,
        baselineEquationFingerprint = baselineSpec.fingerprint(),
        candidateEquationFingerprint = candidateSpec.fingerprint(),
        baseline = WorldEquationRunMetrics(
            status = WorldFormulaStatus.CONVERGED,
            iterationCount = 5,
            conflictCount = 0,
            anomalyCount = 0,
            terminalDelta = 0.0,
            activeCoefficientIds = setOf(activeCoefficientId),
        ),
        candidate = WorldEquationRunMetrics(
            status = WorldFormulaStatus.CONVERGED,
            iterationCount = 3,
            conflictCount = 0,
            anomalyCount = 0,
            terminalDelta = 0.0,
            activeCoefficientIds = setOf(activeCoefficientId),
        ),
    )

}
