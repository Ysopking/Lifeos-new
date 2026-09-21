package app.lifeos.core.runtime.reasoning

import app.lifeos.core.field.world.WorldNodeKind
import app.lifeos.core.field.world.WorldSignalDimension
import app.lifeos.core.field.world.WorldTargetRef
import app.lifeos.core.runtime.learning.OutcomeSignal
import app.lifeos.core.runtime.level7.CausalDiscriminationRequest
import app.lifeos.core.runtime.level7.CausalEvidenceKind
import app.lifeos.core.runtime.level7.CausalObservation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CausalCreditAssignmentEngineTest {
    private val engine = CausalCreditAssignmentEngine()

    @Test
    fun `verified controlled interventions produce non authoritative causal candidates`() {
        val fixture = fixture(verified = true)
        val inputs = fixture.plan.items.mapIndexed { index, item ->
            causalInput(
                item = item,
                error = fixture.errorReport.entries.single {
                    it.evidenceActionId == item.evidenceAction.id
                },
                relationFlip = index % 2 == 1,
            )
        }

        val report = engine.assign(
            plan = fixture.plan,
            expectationModel = fixture.model,
            errorReport = fixture.errorReport,
            observations = inputs,
        )

        assertEquals(fixture.plan.items.size, report.assignments.size)
        assertFalse(report.causalAuthority)
        assertFalse(report.promotionAllowed)
        assertTrue(report.assignments.all { !it.causalAuthority && !it.promotionAllowed })
        assertTrue(report.assignments.all { !it.inductionCandidate.causalAuthority })
        assertTrue(report.assignments.all { !it.inductionCandidate.directWorldMutationAllowed })
        assertTrue(report.assignments.all { !it.inductionCandidate.directEquationMutationAllowed })
    }

    @Test
    fun `temporal correlation cannot be used for causal credit`() {
        val fixture = fixture(verified = true)
        val item = fixture.plan.items.first()
        val error = fixture.errorReport.entries.single {
            it.evidenceActionId == item.evidenceAction.id
        }
        val bad = causalInput(
            item = item,
            error = error,
            evidenceKind = CausalEvidenceKind.TEMPORAL_CORRELATION,
        )
        val remaining = fixture.plan.items.drop(1).map { other ->
            causalInput(
                item = other,
                error = fixture.errorReport.entries.single {
                    it.evidenceActionId == other.evidenceAction.id
                },
            )
        }

        assertFailsWith<IllegalArgumentException> {
            engine.assign(
                fixture.plan,
                fixture.model,
                fixture.errorReport,
                listOf(bad) + remaining,
            )
        }
    }

    @Test
    fun `causal observation provenance must match exact B372 observation input`() {
        val fixture = fixture(verified = true)
        val inputs = fixture.plan.items.map { item ->
            val error = fixture.errorReport.entries.single {
                it.evidenceActionId == item.evidenceAction.id
            }
            causalInput(
                item = item,
                error = error,
                overrideProvenance = "wrong-provenance",
            )
        }

        assertFailsWith<IllegalArgumentException> {
            engine.assign(
                fixture.plan,
                fixture.model,
                fixture.errorReport,
                inputs,
            )
        }
    }

    @Test
    fun `unverified prediction error report cannot enter causal credit`() {
        val fixture = fixture(verified = false)
        assertFalse(fixture.errorReport.verifiedComplete)

        val inputs = fixture.plan.items.map { item ->
            causalInput(
                item = item,
                error = fixture.errorReport.entries.single {
                    it.evidenceActionId == item.evidenceAction.id
                },
            )
        }

        assertFailsWith<IllegalArgumentException> {
            engine.assign(
                fixture.plan,
                fixture.model,
                fixture.errorReport,
                inputs,
            )
        }
    }

    @Test
    fun `candidate relations must cover exact competing candidate set`() {
        val fixture = fixture(verified = true)
        val item = fixture.plan.items.first()
        val error = fixture.errorReport.entries.single {
            it.evidenceActionId == item.evidenceAction.id
        }
        val incomplete = causalInput(
            item = item,
            error = error,
            relationOverride = mapOf(
                item.proposal.request.candidateIds.first() to CausalCreditRelation.SUPPORTS
            ),
        )
        val remaining = fixture.plan.items.drop(1).map { other ->
            causalInput(
                item = other,
                error = fixture.errorReport.entries.single {
                    it.evidenceActionId == other.evidenceAction.id
                },
            )
        }

        assertFailsWith<IllegalArgumentException> {
            engine.assign(
                fixture.plan,
                fixture.model,
                fixture.errorReport,
                listOf(incomplete) + remaining,
            )
        }
    }

    @Test
    fun `causal credit input order cannot change report identity`() {
        val fixture = fixture(verified = true)
        val inputs = fixture.plan.items.map { item ->
            causalInput(
                item = item,
                error = fixture.errorReport.entries.single {
                    it.evidenceActionId == item.evidenceAction.id
                },
            )
        }

        val first = engine.assign(
            fixture.plan,
            fixture.model,
            fixture.errorReport,
            inputs,
        )
        val second = engine.assign(
            fixture.plan,
            fixture.model,
            fixture.errorReport,
            inputs.reversed(),
        )

        assertEquals(first, second)
    }

    @Test
    fun `missing or unknown action coverage fails closed`() {
        val fixture = fixture(verified = true)
        val first = fixture.plan.items.first()
        val valid = causalInput(
            item = first,
            error = fixture.errorReport.entries.single {
                it.evidenceActionId == first.evidenceAction.id
            },
        )

        assertFailsWith<IllegalArgumentException> {
            engine.assign(
                fixture.plan,
                fixture.model,
                fixture.errorReport,
                listOf(valid),
            )
        }

        val unknown = valid.copy(evidenceActionId = "unknown-action")
        val allButLast = fixture.plan.items.dropLast(1).map { item ->
            causalInput(
                item = item,
                error = fixture.errorReport.entries.single {
                    it.evidenceActionId == item.evidenceAction.id
                },
            )
        }
        assertFailsWith<IllegalArgumentException> {
            engine.assign(
                fixture.plan,
                fixture.model,
                fixture.errorReport,
                allButLast + unknown,
            )
        }
    }

    private fun fixture(verified: Boolean): Fixture {
        val plan = ExperimentPlanner(
            ExperimentPlannerConfig(
                maxExperiments = 2,
                maxTotalResourceCost = 10.0,
            )
        ).plan(
            sourceCycleId = "cycle-b373",
            reasoningSearchFingerprint = "search-b373",
            counterfactualBatchFingerprint = "counterfactual-b373",
            budgetFingerprint = "budget-b373",
            requests = listOf(
                request("a", 1.0),
                request("b", 0.5),
            ),
        )

        val model = OutcomeExpectationModelBuilder().build(
            plan,
            plan.items.mapIndexed { index, item ->
                val point = 0.70 + index * 0.05
                OutcomeExpectationInput(
                    evidenceActionId = item.evidenceAction.id,
                    expected = ExpectedOutcomeSignal(
                        completion = ExpectedOutcomeBand(
                            lower = point - 0.10,
                            point = point,
                            upper = point + 0.10,
                        ),
                        correctness = ExpectedOutcomeBand(
                            lower = point - 0.10,
                            point = point,
                            upper = point + 0.10,
                        ),
                    ),
                    confidence = 0.8,
                    rationale = "B373 expectation fixture",
                )
            },
        )

        val observed = model.entries.mapIndexed { index, entry ->
            ObservedOutcomeInput(
                evidenceActionId = entry.evidenceActionId,
                observationRef = "observation:" + index,
                observationFingerprint = "observation-fingerprint:" + index,
                signal = OutcomeSignal(
                    completion = 0.72 + index * 0.03,
                    correctness = 0.69 + index * 0.03,
                ),
                confidence = 0.9,
                verified = verified,
            )
        }
        val errorReport = PredictionErrorEngine().compare(model, observed)
        return Fixture(
            plan = plan,
            model = model,
            observed = observed.associateBy { it.evidenceActionId },
            errorReport = errorReport,
        )
    }

    private fun causalInput(
        item: ExperimentPlanItem,
        error: PredictionErrorEntry,
        evidenceKind: CausalEvidenceKind = CausalEvidenceKind.CONTROLLED_INTERVENTION,
        relationFlip: Boolean = false,
        overrideProvenance: String? = null,
        relationOverride: Map<String, CausalCreditRelation>? = null,
    ): CausalCreditObservationInput {
        val request = item.proposal.request
        val candidates = request.candidateIds.sorted()
        val relations = relationOverride ?: candidates.mapIndexed { index, candidateId ->
            candidateId to when {
                index == 0 && !relationFlip -> CausalCreditRelation.SUPPORTS
                index == 0 && relationFlip -> CausalCreditRelation.CONTRADICTS
                index == 1 && !relationFlip -> CausalCreditRelation.CONTRADICTS
                index == 1 && relationFlip -> CausalCreditRelation.SUPPORTS
                else -> CausalCreditRelation.UNRESOLVED
            }
        }.toMap()
        val provenance = overrideProvenance ?: requireNotNull(error.observationInputFingerprint)
        val observation = CausalObservation(
            id = "causal-observation:" + item.evidenceAction.id,
            sourceSnapshotId = "before:" + item.evidenceAction.id,
            targetSnapshotId = "after:" + item.evidenceAction.id,
            sourceTarget = WorldTargetRef(
                WorldNodeKind.EXPERIMENT,
                request.interventionVariableId,
            ),
            targetTarget = WorldTargetRef(
                WorldNodeKind.OUTCOME,
                item.evidenceAction.id,
            ),
            sourceDimension = WorldSignalDimension.INFORMATION_GAIN,
            targetDimension = WorldSignalDimension.OUTCOME_ALIGNMENT,
            signedEffect = 0.25,
            confidence = 0.9,
            evidenceKind = evidenceKind,
            provenanceFingerprint = provenance,
        )
        return CausalCreditObservationInput(
            evidenceActionId = item.evidenceAction.id,
            observation = observation,
            candidateRelations = relations,
        )
    }

    private fun request(
        key: String,
        informationGain: Double,
    ): CausalDiscriminationRequest = CausalDiscriminationRequest(
        candidateIds = setOf(
            "candidate-" + key + "-a",
            "candidate-" + key + "-b",
        ),
        interventionVariableId = "variable-" + key,
        expectedInformationGain = informationGain,
        rationale = "distinguish-" + key,
    )

    private data class Fixture(
        val plan: ExperimentPlan,
        val model: OutcomeExpectationModel,
        val observed: Map<String, ObservedOutcomeInput>,
        val errorReport: PredictionErrorReport,
    )
}
