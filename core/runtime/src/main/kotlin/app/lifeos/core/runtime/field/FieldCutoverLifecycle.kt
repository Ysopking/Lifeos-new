package app.lifeos.core.runtime.field

import app.lifeos.core.field.ConvergenceStatus
import app.lifeos.core.field.FieldConvergenceEngine
import app.lifeos.core.field.FieldDomainId
import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.model.Photon
import app.lifeos.core.model.task.TaskId
import app.lifeos.core.model.task.TaskState
import app.lifeos.core.runtime.ThoughtMatrix
import java.time.Instant

/**
 * Productive M212 processor. Worker legacy-field execution is skipped once its durable domain state
 * is AUTHORITATIVE; the ThoughtMatrix compatibility read model is updated only after universal
 * convergence completed and therefore is not itself the cutover authority.
 */
class ThoughtMatrixAuthoritativeFieldProcessor(
    private val matrix: ThoughtMatrix,
    private val universal: FieldShadowProcessor,
) : AuthoritativeFieldProcessor {
    override val domainId: FieldDomainId = ThoughtMatrix.FIELD_DOMAIN_ID

    override fun accepts(photon: Photon): Boolean = true

    override suspend fun process(photon: Photon): FieldShadowExecution {
        val execution = universal.process(photon)
        if (execution.state == FieldShadowState.COMPLETED) {
            require(execution.domainId == domainId) {
                "ThoughtMatrix authoritative universal processor returned wrong domain"
            }
            matrix.indexFromAuthoritativeField(photon)
        }
        return execution
    }
}

data class FieldCutoverReplaySummary(
    val domainId: FieldDomainId,
    val caseCount: Int,
    val newlyRecordedCount: Int,
    val assessment: FieldCutoverAssessment,
) {
    init {
        require(caseCount >= 0)
        require(newlyRecordedCount in 0..caseCount)
        require(assessment.state.domainId == domainId)
    }
}

/**
 * Deterministic replay for the selected ThoughtMatrix cutover domain. The legacy side runs in an
 * isolated, non-durable ThoughtMatrix and the universal side runs a pure convergence engine. Only
 * validated outcome evidence is written to the durable shadow ledger.
 */
class ThoughtMatrixFieldCutoverReplayCoordinator(
    private val ledger: FieldShadowValidationLedger,
    private val validator: FieldShadowValidator,
    private val authority: FieldCutoverAuthority,
    private val requestFactory: ThoughtMatrixFieldRequestFactory =
        ThoughtMatrixFieldRequestFactory(),
    private val engine: FieldConvergenceEngine = FieldConvergenceEngine(),
    private val maxCasesPerRun: Int = MAX_CASES_PER_RUN,
) {
    init {
        require(maxCasesPerRun in 1..MAX_CASES_PER_RUN)
    }

    suspend fun replayAndAssess(
        photons: Collection<Photon>,
        at: Instant,
        provenance: String,
    ): FieldCutoverReplaySummary {
        require(provenance.isNotBlank())
        val canonical = photons
            .distinctBy { it.id to it.revision }
            .sortedWith(
                compareBy<Photon> { it.provenance.createdAt }
                    .thenBy { it.id.value }
                    .thenBy { it.revision }
            )
            .takeLast(maxCasesPerRun)
        val legacy = ThoughtMatrix()
        var recorded = 0

        canonical.forEach { photon ->
            val legacyInfluence = legacy.influence(photon)
            val legacyObservation = LegacyFieldObservation.capture(
                finalState = TaskState.COMPLETED,
                influences = listOfNotNull(legacyInfluence),
            )
            val universal = engine.converge(requestFactory.create(photon))
            val universalExecution = FieldShadowExecution(
                state = FieldShadowState.COMPLETED,
                domainId = ThoughtMatrix.FIELD_DOMAIN_ID,
                runId = universal.state.runId,
                snapshotId = universal.snapshot.id,
                convergenceStatus = universal.status,
                sourcePhotonId = photon.id,
                sourceRevision = photon.revision,
                sourceFingerprint = runtimePhotonFingerprint(photon),
            )
            val evidence = validator.validate(
                FieldShadowValidationInput(
                    taskId = TaskId(
                        "field-cutover-replay-" + StableFieldIds.fingerprint(
                            "thought-matrix-cutover-task/v1",
                            photon.id.value,
                            photon.revision.toString(),
                            runtimePhotonFingerprint(photon),
                        ).take(32)
                    ),
                    domainId = ThoughtMatrix.FIELD_DOMAIN_ID,
                    legacy = legacyObservation,
                    universal = UniversalFieldObservation.capture(
                        universalExecution,
                        universal.snapshot,
                    ),
                    sourceStatus = ShadowSourceStatus.PRESERVED,
                    taskOwnershipStatus = ShadowTaskOwnershipStatus.PRESERVED,
                    origin = FieldShadowValidationOrigin.DETERMINISTIC_REPLAY,
                    replayCaseId = StableFieldIds.fingerprint(
                        "thought-matrix-cutover-replay-case/v1",
                        photon.id.value,
                        photon.revision.toString(),
                        runtimePhotonFingerprint(photon),
                    ),
                )
            )
            if (ledger.record(evidence)) recorded += 1
        }

        val assessment = authority.assess(
            domainId = ThoughtMatrix.FIELD_DOMAIN_ID,
            at = at,
            provenance = provenance,
        )
        return FieldCutoverReplaySummary(
            domainId = ThoughtMatrix.FIELD_DOMAIN_ID,
            caseCount = canonical.size,
            newlyRecordedCount = recorded,
            assessment = assessment,
        )
    }

    private companion object {
        const val MAX_CASES_PER_RUN = 128
    }
}

data class FieldCutoverReconciliation(
    val assessments: List<FieldCutoverAssessment>,
) {
    val authoritativeDomains: Set<FieldDomainId> = assessments
        .filter { it.state.mode == FieldCutoverMode.AUTHORITATIVE }
        .mapTo(linkedSetOf()) { it.state.domainId }

    val eligibleDomains: Set<FieldDomainId> = assessments
        .filter { it.state.mode == FieldCutoverMode.ELIGIBLE }
        .mapTo(linkedSetOf()) { it.state.domainId }
}

/**
 * Single lifecycle authority for selected productive domains. Reconciliation is fail-closed:
 * changed or insufficient replay evidence demotes an old authoritative state through assess().
 * Activation remains an explicit second operation bound to the exact evidence fingerprint.
 */
class FieldCutoverLifecycleCoordinator(
    private val authority: FieldCutoverAuthority,
    selectedDomains: Collection<FieldDomainId>,
) {
    private val selectedDomains = selectedDomains.toSortedSet(compareBy { it.value })

    init {
        require(DefaultPhotonFieldRequestFactory.DOMAIN_ID !in this.selectedDomains) {
            "Generic runtime shadow domain cannot be selected for productive cutover"
        }
    }

    suspend fun reconcile(
        at: Instant,
        provenance: String,
    ): FieldCutoverReconciliation {
        require(provenance.isNotBlank())
        val assessments = selectedDomains.map { domainId ->
            authority.assess(domainId, at, provenance)
        }
        return FieldCutoverReconciliation(assessments)
    }

    suspend fun activate(
        domainId: FieldDomainId,
        expectedEvidenceFingerprint: String,
        at: Instant,
        provenance: String,
    ): FieldCutoverState {
        require(domainId in selectedDomains) {
            "Field domain is not selected for productive cutover: " + domainId.value
        }
        val assessment = authority.assess(
            domainId = domainId,
            at = at,
            provenance = provenance + ":pre-activation",
        )
        require(assessment.state.mode == FieldCutoverMode.ELIGIBLE) {
            "Field domain is not eligible after fresh evidence assessment"
        }
        require(assessment.evidenceFingerprint == expectedEvidenceFingerprint) {
            "Field cutover evidence fingerprint changed before activation"
        }
        return authority.activate(
            domainId = domainId,
            expectedEvidenceFingerprint = expectedEvidenceFingerprint,
            at = at,
            provenance = provenance,
        )
    }

    fun selectedDomains(): Set<FieldDomainId> = selectedDomains.toSet()
}
