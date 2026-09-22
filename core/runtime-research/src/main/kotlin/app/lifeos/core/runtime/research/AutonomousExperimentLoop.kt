package app.lifeos.core.runtime.research

import app.lifeos.core.runtime.reasoning.CausalCreditAssignmentEngine
import app.lifeos.core.runtime.reasoning.CausalCreditAssignmentReport
import app.lifeos.core.runtime.reasoning.CausalCreditObservationInput
import app.lifeos.core.runtime.reasoning.ExperimentPlan
import app.lifeos.core.runtime.reasoning.ObservedOutcomeInput
import app.lifeos.core.runtime.reasoning.OutcomeExpectationInput
import app.lifeos.core.runtime.reasoning.OutcomeExpectationModel
import app.lifeos.core.runtime.reasoning.OutcomeExpectationModelBuilder
import app.lifeos.core.runtime.reasoning.PredictionErrorEngine
import app.lifeos.core.runtime.reasoning.PredictionErrorEntry
import app.lifeos.core.runtime.reasoning.PredictionErrorReport
import app.lifeos.core.runtime.reasoning.PredictionErrorState
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

enum class AutonomousExperimentState {
    AWAITING_VERIFIED_OBSERVATION,
    VERIFIED_WITHIN_EXPECTATION,
    VERIFIED_OUTSIDE_EXPECTATION,
    VERIFIED_MIXED,
    CAUSAL_CREDIT_AVAILABLE,
}

data class AutonomousExperimentCycle(
    val experimentPlanFingerprint: String,
    val expectationModelFingerprint: String,
    val predictionErrorReportFingerprint: String,
    val causalCreditReportFingerprint: String?,
    val state: AutonomousExperimentState,
    val missingObservationActionIds: List<String>,
    val incompleteObservationActionIds: List<String>,
    val unverifiedObservationActionIds: List<String>,
    val fingerprint: String,
) {
    init {
        require(experimentPlanFingerprint.isNotBlank())
        require(expectationModelFingerprint.isNotBlank())
        require(predictionErrorReportFingerprint.isNotBlank())
        require(causalCreditReportFingerprint == null || causalCreditReportFingerprint.isNotBlank())
        require(missingObservationActionIds == missingObservationActionIds.distinct().sorted())
        require(incompleteObservationActionIds == incompleteObservationActionIds.distinct().sorted())
        require(unverifiedObservationActionIds == unverifiedObservationActionIds.distinct().sorted())
        if (state == AutonomousExperimentState.CAUSAL_CREDIT_AVAILABLE) {
            require(causalCreditReportFingerprint != null)
        }
        if (
            state == AutonomousExperimentState.VERIFIED_WITHIN_EXPECTATION ||
            state == AutonomousExperimentState.VERIFIED_OUTSIDE_EXPECTATION ||
            state == AutonomousExperimentState.VERIFIED_MIXED ||
            state == AutonomousExperimentState.CAUSAL_CREDIT_AVAILABLE
        ) {
            require(missingObservationActionIds.isEmpty())
            require(incompleteObservationActionIds.isEmpty())
            require(unverifiedObservationActionIds.isEmpty())
        }
        require(
            fingerprint == experimentCycleFingerprint(
                experimentPlanFingerprint = experimentPlanFingerprint,
                expectationModelFingerprint = expectationModelFingerprint,
                predictionErrorReportFingerprint = predictionErrorReportFingerprint,
                causalCreditReportFingerprint = causalCreditReportFingerprint,
                state = state,
                missingObservationActionIds = missingObservationActionIds,
                incompleteObservationActionIds = incompleteObservationActionIds,
                unverifiedObservationActionIds = unverifiedObservationActionIds,
            )
        )
    }

    val executionAuthority: Boolean
        get() = false

    val externalEffectAuthority: Boolean
        get() = false

    val causalAuthority: Boolean
        get() = false

    val worldMutationAuthority: Boolean
        get() = false

    val promotionAuthority: Boolean
        get() = false
}

data class ExperimentLearningCandidate(
    val id: String,
    val evidenceActionId: String,
    val experimentPlanFingerprint: String,
    val expectationModelFingerprint: String,
    val predictionErrorEntryFingerprint: String,
    val predictionErrorState: PredictionErrorState,
    val observationFingerprint: String,
    val causalCreditAssignmentFingerprint: String?,
    val entersNextCycleOnly: Boolean,
    val fingerprint: String,
) {
    init {
        require(id.startsWith(ID_PREFIX))
        require(evidenceActionId.isNotBlank())
        require(experimentPlanFingerprint.isNotBlank())
        require(expectationModelFingerprint.isNotBlank())
        require(predictionErrorEntryFingerprint.isNotBlank())
        require(observationFingerprint.isNotBlank())
        require(
            predictionErrorState == PredictionErrorState.WITHIN_EXPECTED_BAND ||
                predictionErrorState == PredictionErrorState.OUTSIDE_EXPECTED_BAND
        )
        require(entersNextCycleOnly)
        require(
            fingerprint == experimentLearningFingerprint(
                evidenceActionId = evidenceActionId,
                experimentPlanFingerprint = experimentPlanFingerprint,
                expectationModelFingerprint = expectationModelFingerprint,
                predictionErrorEntryFingerprint = predictionErrorEntryFingerprint,
                predictionErrorState = predictionErrorState,
                observationFingerprint = observationFingerprint,
                causalCreditAssignmentFingerprint = causalCreditAssignmentFingerprint,
                entersNextCycleOnly = entersNextCycleOnly,
            )
        )
        require(id == ID_PREFIX + fingerprint)
    }

    val truthAuthority: Boolean
        get() = false

    val causalAuthority: Boolean
        get() = false

    val executionAuthority: Boolean
        get() = false

    val worldMutationAuthority: Boolean
        get() = false

    val promotionAuthority: Boolean
        get() = false

    companion object {
        const val ID_PREFIX = "experiment-learning-candidate:"
    }
}

data class AutonomousExperimentAssessment(
    val cycle: AutonomousExperimentCycle,
    val predictionErrors: PredictionErrorReport,
    val causalCredit: CausalCreditAssignmentReport?,
    val learningCandidates: List<ExperimentLearningCandidate>,
) {
    init {
        require(cycle.predictionErrorReportFingerprint == predictionErrors.fingerprint)
        require(cycle.causalCreditReportFingerprint == causalCredit?.fingerprint)
        require(learningCandidates == learningCandidates.distinctBy { it.id }.sortedBy { it.id })
    }
}

/**
 * B389 composes the existing B370-B373 experiment pipeline:
 *
 * ExperimentPlan -> OutcomeExpectationModel -> supplied observation -> PredictionErrorReport
 * -> optional controlled-intervention causal credit -> next-cycle learning candidate.
 *
 * It deliberately owns no experiment executor. SAFE_SANDBOX_EXPERIMENT execution remains in the
 * existing evidence/capability authority path; observations must be supplied after execution.
 */
class AutonomousExperimentLoop(
    private val expectationBuilder: OutcomeExpectationModelBuilder =
        OutcomeExpectationModelBuilder(),
    private val predictionErrorEngine: PredictionErrorEngine =
        PredictionErrorEngine(),
    private val causalCreditEngine: CausalCreditAssignmentEngine =
        CausalCreditAssignmentEngine(),
) {
    fun prepare(
        plan: ExperimentPlan,
        expectations: Collection<OutcomeExpectationInput>,
    ): OutcomeExpectationModel =
        expectationBuilder.build(plan, expectations)

    fun assess(
        plan: ExperimentPlan,
        expectationModel: OutcomeExpectationModel,
        observations: Collection<ObservedOutcomeInput>,
        causalObservations: Collection<CausalCreditObservationInput> = emptyList(),
    ): AutonomousExperimentAssessment {
        require(expectationModel.experimentPlanFingerprint == plan.fingerprint) {
            "B389 expectation model belongs to another experiment plan"
        }

        val predictionErrors = predictionErrorEngine.compare(
            expectationModel,
            observations,
        )

        val causalCredit = if (causalObservations.isEmpty()) {
            null
        } else {
            require(predictionErrors.verifiedComplete) {
                "B389 causal credit requires verified-complete observations"
            }
            causalCreditEngine.assign(
                plan = plan,
                expectationModel = expectationModel,
                errorReport = predictionErrors,
                observations = causalObservations,
            )
        }

        val state = deriveState(predictionErrors, causalCredit)
        val cycle = AutonomousExperimentCycle(
            experimentPlanFingerprint = plan.fingerprint,
            expectationModelFingerprint = expectationModel.fingerprint,
            predictionErrorReportFingerprint = predictionErrors.fingerprint,
            causalCreditReportFingerprint = causalCredit?.fingerprint,
            state = state,
            missingObservationActionIds = predictionErrors.missingObservationActionIds,
            incompleteObservationActionIds = predictionErrors.incompleteObservationActionIds,
            unverifiedObservationActionIds = predictionErrors.unverifiedObservationActionIds,
            fingerprint = experimentCycleFingerprint(
                experimentPlanFingerprint = plan.fingerprint,
                expectationModelFingerprint = expectationModel.fingerprint,
                predictionErrorReportFingerprint = predictionErrors.fingerprint,
                causalCreditReportFingerprint = causalCredit?.fingerprint,
                state = state,
                missingObservationActionIds = predictionErrors.missingObservationActionIds,
                incompleteObservationActionIds = predictionErrors.incompleteObservationActionIds,
                unverifiedObservationActionIds = predictionErrors.unverifiedObservationActionIds,
            ),
        )

        val observationByAction = observations.associateBy { it.evidenceActionId }
        val creditByAction = causalCredit
            ?.assignments
            ?.associateBy { it.evidenceActionId }
            .orEmpty()
        val learning = predictionErrors.entries
            .asSequence()
            .filter(PredictionErrorEntry::learningEligible)
            .map { error ->
                val observation = requireNotNull(observationByAction[error.evidenceActionId]) {
                    "Learning-eligible prediction error lacks exact observation"
                }
                require(observation.verified) {
                    "Learning-eligible B389 candidate requires verified observation"
                }
                val fingerprint = experimentLearningFingerprint(
                    evidenceActionId = error.evidenceActionId,
                    experimentPlanFingerprint = plan.fingerprint,
                    expectationModelFingerprint = expectationModel.fingerprint,
                    predictionErrorEntryFingerprint = error.fingerprint,
                    predictionErrorState = error.state,
                    observationFingerprint = observation.observationFingerprint,
                    causalCreditAssignmentFingerprint =
                        creditByAction[error.evidenceActionId]?.fingerprint,
                    entersNextCycleOnly = true,
                )
                ExperimentLearningCandidate(
                    id = ExperimentLearningCandidate.ID_PREFIX + fingerprint,
                    evidenceActionId = error.evidenceActionId,
                    experimentPlanFingerprint = plan.fingerprint,
                    expectationModelFingerprint = expectationModel.fingerprint,
                    predictionErrorEntryFingerprint = error.fingerprint,
                    predictionErrorState = error.state,
                    observationFingerprint = observation.observationFingerprint,
                    causalCreditAssignmentFingerprint =
                        creditByAction[error.evidenceActionId]?.fingerprint,
                    entersNextCycleOnly = true,
                    fingerprint = fingerprint,
                )
            }
            .distinctBy { it.id }
            .sortedBy { it.id }
            .toList()

        return AutonomousExperimentAssessment(
            cycle = cycle,
            predictionErrors = predictionErrors,
            causalCredit = causalCredit,
            learningCandidates = learning,
        )
    }

    private fun deriveState(
        report: PredictionErrorReport,
        causalCredit: CausalCreditAssignmentReport?,
    ): AutonomousExperimentState {
        if (!report.verifiedComplete) {
            return AutonomousExperimentState.AWAITING_VERIFIED_OBSERVATION
        }
        if (causalCredit != null) {
            return AutonomousExperimentState.CAUSAL_CREDIT_AVAILABLE
        }
        val states = report.entries.map { it.state }.toSet()
        return when {
            states == setOf(PredictionErrorState.WITHIN_EXPECTED_BAND) ->
                AutonomousExperimentState.VERIFIED_WITHIN_EXPECTATION
            states == setOf(PredictionErrorState.OUTSIDE_EXPECTED_BAND) ->
                AutonomousExperimentState.VERIFIED_OUTSIDE_EXPECTATION
            else ->
                AutonomousExperimentState.VERIFIED_MIXED
        }
    }
}

private fun experimentCycleFingerprint(
    experimentPlanFingerprint: String,
    expectationModelFingerprint: String,
    predictionErrorReportFingerprint: String,
    causalCreditReportFingerprint: String?,
    state: AutonomousExperimentState,
    missingObservationActionIds: List<String>,
    incompleteObservationActionIds: List<String>,
    unverifiedObservationActionIds: List<String>,
): String = autonomousExperimentFingerprint(
    "autonomous-experiment-cycle/v1",
    experimentPlanFingerprint,
    expectationModelFingerprint,
    predictionErrorReportFingerprint,
    causalCreditReportFingerprint.orEmpty(),
    state.name,
    missingObservationActionIds.joinToString("\u001f"),
    incompleteObservationActionIds.joinToString("\u001f"),
    unverifiedObservationActionIds.joinToString("\u001f"),
)

private fun experimentLearningFingerprint(
    evidenceActionId: String,
    experimentPlanFingerprint: String,
    expectationModelFingerprint: String,
    predictionErrorEntryFingerprint: String,
    predictionErrorState: PredictionErrorState,
    observationFingerprint: String,
    causalCreditAssignmentFingerprint: String?,
    entersNextCycleOnly: Boolean,
): String = autonomousExperimentFingerprint(
    "experiment-learning-candidate/v1",
    evidenceActionId,
    experimentPlanFingerprint,
    expectationModelFingerprint,
    predictionErrorEntryFingerprint,
    predictionErrorState.name,
    observationFingerprint,
    causalCreditAssignmentFingerprint.orEmpty(),
    entersNextCycleOnly.toString(),
)

private fun autonomousExperimentFingerprint(
    domain: String,
    vararg parts: String,
): String {
    val digest = MessageDigest.getInstance("SHA-256")
    fun update(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        digest.update(
            byteArrayOf(
                (bytes.size ushr 24).toByte(),
                (bytes.size ushr 16).toByte(),
                (bytes.size ushr 8).toByte(),
                bytes.size.toByte(),
            )
        )
        digest.update(bytes)
    }
    update(domain)
    parts.forEach(::update)
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
