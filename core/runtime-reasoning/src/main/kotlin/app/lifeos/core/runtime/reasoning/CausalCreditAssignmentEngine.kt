package app.lifeos.core.runtime.reasoning

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.runtime.level7.CausalEvidenceKind
import app.lifeos.core.runtime.level7.CausalInductionCandidate
import app.lifeos.core.runtime.level7.CausalObservation

enum class CausalCreditRelation {
    SUPPORTS,
    CONTRADICTS,
    UNRESOLVED,
}

data class CausalCreditObservationInput(
    val evidenceActionId: String,
    val observation: CausalObservation,
    val candidateRelations: Map<String, CausalCreditRelation>,
) {
    init {
        require(evidenceActionId.isNotBlank())
        require(candidateRelations.isNotEmpty())
        require(candidateRelations.keys.none { it.isBlank() })
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "causal-credit-observation-input/v1",
        evidenceActionId,
        observation.fingerprint(),
        *candidateRelations.toSortedMap().flatMap { (candidateId, relation) ->
            listOf("candidate:" + candidateId, relation.name)
        }.toTypedArray(),
    )
}

data class CausalCreditAssignment(
    val evidenceActionId: String,
    val discriminationRequestFingerprint: String,
    val predictionErrorEntryFingerprint: String,
    val interventionVariableId: String,
    val candidateRelations: Map<String, CausalCreditRelation>,
    val causalObservationFingerprint: String,
    val inductionCandidate: CausalInductionCandidate,
    val fingerprint: String,
) {
    init {
        require(evidenceActionId.isNotBlank())
        require(discriminationRequestFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(predictionErrorEntryFingerprint.isNotBlank())
        require(interventionVariableId.isNotBlank())
        require(candidateRelations.isNotEmpty())
        require(candidateRelations.keys.none { it.isBlank() })
        require(causalObservationFingerprint.isNotBlank())
        require(!inductionCandidate.causalAuthority)
        require(!inductionCandidate.directWorldMutationAllowed)
        require(!inductionCandidate.directEquationMutationAllowed)
        require(
            fingerprint == assignmentFingerprint(
                evidenceActionId = evidenceActionId,
                discriminationRequestFingerprint = discriminationRequestFingerprint,
                predictionErrorEntryFingerprint = predictionErrorEntryFingerprint,
                interventionVariableId = interventionVariableId,
                candidateRelations = candidateRelations,
                causalObservationFingerprint = causalObservationFingerprint,
                inductionCandidate = inductionCandidate,
            )
        )
    }

    val causalAuthority: Boolean
        get() = false

    val promotionAllowed: Boolean
        get() = false
}

data class CausalCreditAssignmentReport(
    val experimentPlanFingerprint: String,
    val expectationModelFingerprint: String,
    val predictionErrorReportFingerprint: String,
    val assignments: List<CausalCreditAssignment>,
    val fingerprint: String,
) {
    init {
        require(experimentPlanFingerprint.isNotBlank())
        require(expectationModelFingerprint.isNotBlank())
        require(predictionErrorReportFingerprint.isNotBlank())
        require(assignments.isNotEmpty())
        require(assignments == assignments.sortedBy { it.evidenceActionId })
        require(assignments.map { it.evidenceActionId }.distinct().size == assignments.size)
        require(
            fingerprint == reportFingerprint(
                experimentPlanFingerprint = experimentPlanFingerprint,
                expectationModelFingerprint = expectationModelFingerprint,
                predictionErrorReportFingerprint = predictionErrorReportFingerprint,
                assignments = assignments,
            )
        )
    }

    val causalAuthority: Boolean
        get() = false

    val promotionAllowed: Boolean
        get() = false
}

/**
 * B373 assigns provenance-safe causal credit candidates from controlled interventions.
 *
 * A prediction error alone is never causal evidence. Credit requires an exact B370 experiment
 * action, the exact B371 expectation model, a verified-complete B372 report, and a controlled
 * CausalObservation whose provenance fingerprint is the exact B372 observation-input fingerprint.
 * Output remains a CausalInductionCandidate with no causal or mutation authority.
 */
class CausalCreditAssignmentEngine {
    fun assign(
        plan: ExperimentPlan,
        expectationModel: OutcomeExpectationModel,
        errorReport: PredictionErrorReport,
        observations: Collection<CausalCreditObservationInput>,
    ): CausalCreditAssignmentReport {
        require(expectationModel.experimentPlanFingerprint == plan.fingerprint) {
            "Outcome expectation model does not belong to the supplied experiment plan"
        }
        require(errorReport.expectationModelFingerprint == expectationModel.fingerprint) {
            "Prediction error report does not belong to the supplied expectation model"
        }
        require(errorReport.verifiedComplete) {
            "Causal credit requires a verified-complete prediction error report"
        }
        require(errorReport.entries.all { it.learningEligible }) {
            "Every causal-credit error entry must be verified and complete"
        }
        require(plan.items.isNotEmpty()) {
            "Causal credit requires admitted experiment actions"
        }

        val inputByAction = observations.associateBy { it.evidenceActionId }
        require(inputByAction.size == observations.size) {
            "Each experiment action may have at most one causal credit observation"
        }
        val plannedIds = plan.items.mapTo(linkedSetOf()) { it.evidenceAction.id }
        require(inputByAction.keys == plannedIds) {
            val missing = plannedIds - inputByAction.keys
            val unknown = inputByAction.keys - plannedIds
            "Causal credit observations must cover experiment actions exactly; " +
                "missing=" + missing.sorted().joinToString(",") +
                ";unknown=" + unknown.sorted().joinToString(",")
        }

        val errorByAction = errorReport.entries.associateBy { it.evidenceActionId }
        require(errorByAction.keys == plannedIds) {
            "Prediction error report action set does not match experiment plan"
        }

        val assignments = plan.items
            .map { item ->
                val input = inputByAction.getValue(item.evidenceAction.id)
                val error = errorByAction.getValue(item.evidenceAction.id)
                val request = item.proposal.request

                require(input.observation.evidenceKind == CausalEvidenceKind.CONTROLLED_INTERVENTION) {
                    "Causal credit from a B370 experiment requires CONTROLLED_INTERVENTION evidence"
                }
                require(error.observationInputFingerprint == input.observation.provenanceFingerprint) {
                    "Causal observation provenance does not match the B372 observed outcome"
                }
                require(input.candidateRelations.keys == request.candidateIds) {
                    "Causal credit relations must cover the exact competing candidate set"
                }

                val canonicalRelations = input.candidateRelations.toSortedMap()
                val inductionCandidate = CausalInductionCandidate.create(
                    listOf(input.observation)
                )
                CausalCreditAssignment(
                    evidenceActionId = item.evidenceAction.id,
                    discriminationRequestFingerprint = item.requestFingerprint,
                    predictionErrorEntryFingerprint = error.fingerprint,
                    interventionVariableId = request.interventionVariableId,
                    candidateRelations = canonicalRelations,
                    causalObservationFingerprint = input.observation.fingerprint(),
                    inductionCandidate = inductionCandidate,
                    fingerprint = assignmentFingerprint(
                        evidenceActionId = item.evidenceAction.id,
                        discriminationRequestFingerprint = item.requestFingerprint,
                        predictionErrorEntryFingerprint = error.fingerprint,
                        interventionVariableId = request.interventionVariableId,
                        candidateRelations = canonicalRelations,
                        causalObservationFingerprint = input.observation.fingerprint(),
                        inductionCandidate = inductionCandidate,
                    ),
                )
            }
            .sortedBy { it.evidenceActionId }

        return CausalCreditAssignmentReport(
            experimentPlanFingerprint = plan.fingerprint,
            expectationModelFingerprint = expectationModel.fingerprint,
            predictionErrorReportFingerprint = errorReport.fingerprint,
            assignments = assignments,
            fingerprint = reportFingerprint(
                experimentPlanFingerprint = plan.fingerprint,
                expectationModelFingerprint = expectationModel.fingerprint,
                predictionErrorReportFingerprint = errorReport.fingerprint,
                assignments = assignments,
            ),
        )
    }
}

private fun assignmentFingerprint(
    evidenceActionId: String,
    discriminationRequestFingerprint: String,
    predictionErrorEntryFingerprint: String,
    interventionVariableId: String,
    candidateRelations: Map<String, CausalCreditRelation>,
    causalObservationFingerprint: String,
    inductionCandidate: CausalInductionCandidate,
): String = StableFieldIds.fingerprint(
    "causal-credit-assignment/v1",
    evidenceActionId,
    discriminationRequestFingerprint,
    predictionErrorEntryFingerprint,
    interventionVariableId,
    causalObservationFingerprint,
    inductionCandidate.fingerprint(),
    *candidateRelations.toSortedMap().flatMap { (candidateId, relation) ->
        listOf("candidate:" + candidateId, relation.name)
    }.toTypedArray(),
)

private fun reportFingerprint(
    experimentPlanFingerprint: String,
    expectationModelFingerprint: String,
    predictionErrorReportFingerprint: String,
    assignments: List<CausalCreditAssignment>,
): String = StableFieldIds.fingerprint(
    "causal-credit-assignment-report/v1",
    experimentPlanFingerprint,
    expectationModelFingerprint,
    predictionErrorReportFingerprint,
    *assignments.sortedBy { it.evidenceActionId }
        .map { "assignment:" + it.fingerprint }
        .toTypedArray(),
)
