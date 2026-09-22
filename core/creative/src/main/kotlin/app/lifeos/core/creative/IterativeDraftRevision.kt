package app.lifeos.core.creative

import app.lifeos.core.model.StableCognitiveIds

enum class DraftRevisionLoopState {
    CONVERGED,
    BLOCKED,
    STALLED,
    MAX_ROUNDS_REACHED,
}

data class DraftRevisionEvidence internal constructor(
    val documentGoalFingerprint: String,
    val draftFingerprint: String,
    val factualValidationFingerprint: String,
    val factualPassed: Boolean,
    val critiqueFingerprint: String,
    val critiqueStatus: DocumentCritiqueStatus,
    val errorCount: Int,
    val warningCount: Int,
    val findingFingerprints: List<String>,
    val fingerprint: String,
) {
    init {
        require(documentGoalFingerprint.matches(SHA_256_B440))
        require(draftFingerprint.matches(SHA_256_B440))
        require(factualValidationFingerprint.matches(SHA_256_B440))
        require(critiqueFingerprint.matches(SHA_256_B440))
        require(errorCount >= 0)
        require(warningCount >= 0)
        require(findingFingerprints == findingFingerprints.distinct().sorted())
        require(findingFingerprints.all { it.matches(SHA_256_B440) })
        require(errorCount + warningCount == findingFingerprints.size)
        require(
            critiqueStatus == when {
                errorCount > 0 -> DocumentCritiqueStatus.BLOCKED
                warningCount > 0 -> DocumentCritiqueStatus.REVISION_RECOMMENDED
                else -> DocumentCritiqueStatus.PASSED
            }
        )
        if (!factualPassed) {
            require(critiqueStatus == DocumentCritiqueStatus.BLOCKED)
        }
        require(
            fingerprint == revisionEvidenceFingerprint(
                documentGoalFingerprint,
                draftFingerprint,
                factualValidationFingerprint,
                factualPassed,
                critiqueFingerprint,
                critiqueStatus,
                errorCount,
                warningCount,
                findingFingerprints,
            )
        )
    }

    val rewriteAuthority: Boolean get() = false
    val finalizationAuthority: Boolean get() = false

    companion object {
        fun from(
            factualValidation: FactualDraftValidationReport,
            critique: DocumentCritiqueReport,
        ): DraftRevisionEvidence {
            require(critique.coherentDraftFingerprint == factualValidation.coherentDraftFingerprint)
            require(critique.factualValidationFingerprint == factualValidation.fingerprint)
            val errors = critique.findings.count {
                it.severity == DocumentCritiqueSeverity.ERROR
            }
            val warnings = critique.findings.count {
                it.severity == DocumentCritiqueSeverity.WARNING
            }
            return create(
                documentGoalFingerprint = critique.documentGoalFingerprint,
                draftFingerprint = critique.coherentDraftFingerprint,
                factualValidationFingerprint = factualValidation.fingerprint,
                factualPassed = factualValidation.passed,
                critiqueFingerprint = critique.fingerprint,
                critiqueStatus = critique.status,
                errorCount = errors,
                warningCount = warnings,
                findingFingerprints = critique.findings.map { it.fingerprint },
            )
        }

        internal fun fromSignals(
            documentGoalFingerprint: String,
            draftFingerprint: String,
            factualValidationFingerprint: String,
            factualPassed: Boolean,
            critiqueFingerprint: String,
            critiqueStatus: DocumentCritiqueStatus,
            errorCount: Int,
            warningCount: Int,
            findingFingerprints: List<String>,
        ): DraftRevisionEvidence = create(
            documentGoalFingerprint,
            draftFingerprint,
            factualValidationFingerprint,
            factualPassed,
            critiqueFingerprint,
            critiqueStatus,
            errorCount,
            warningCount,
            findingFingerprints,
        )

        private fun create(
            documentGoalFingerprint: String,
            draftFingerprint: String,
            factualValidationFingerprint: String,
            factualPassed: Boolean,
            critiqueFingerprint: String,
            critiqueStatus: DocumentCritiqueStatus,
            errorCount: Int,
            warningCount: Int,
            findingFingerprints: List<String>,
        ): DraftRevisionEvidence {
            val canonicalFindings = findingFingerprints.distinct().sorted()
            return DraftRevisionEvidence(
                documentGoalFingerprint = documentGoalFingerprint,
                draftFingerprint = draftFingerprint,
                factualValidationFingerprint = factualValidationFingerprint,
                factualPassed = factualPassed,
                critiqueFingerprint = critiqueFingerprint,
                critiqueStatus = critiqueStatus,
                errorCount = errorCount,
                warningCount = warningCount,
                findingFingerprints = canonicalFindings,
                fingerprint = revisionEvidenceFingerprint(
                    documentGoalFingerprint,
                    draftFingerprint,
                    factualValidationFingerprint,
                    factualPassed,
                    critiqueFingerprint,
                    critiqueStatus,
                    errorCount,
                    warningCount,
                    canonicalFindings,
                ),
            )
        }
    }
}

data class DraftRevisionAttempt(
    val ordinal: Int,
    val parentEvidenceFingerprint: String,
    val evidence: DraftRevisionEvidence,
    val changedSurfaceFingerprints: List<String>,
    val reasonCodes: List<String>,
    val fingerprint: String,
) {
    init {
        require(ordinal > 0)
        require(parentEvidenceFingerprint.matches(SHA_256_B440))
        require(changedSurfaceFingerprints.isNotEmpty())
        require(
            changedSurfaceFingerprints ==
                changedSurfaceFingerprints.distinct().sorted()
        )
        require(changedSurfaceFingerprints.all { it.matches(SHA_256_B440) })
        require(reasonCodes.isNotEmpty())
        require(reasonCodes == reasonCodes.distinct().sorted())
        require(reasonCodes.none(String::isBlank))
        require(
            fingerprint == revisionAttemptFingerprint(
                ordinal,
                parentEvidenceFingerprint,
                evidence.fingerprint,
                changedSurfaceFingerprints,
                reasonCodes,
            )
        )
    }

    val automaticAcceptanceAuthority: Boolean get() = false

    companion object {
        fun create(
            ordinal: Int,
            parentEvidence: DraftRevisionEvidence,
            evidence: DraftRevisionEvidence,
            changedSurfaceFingerprints: Collection<String>,
            reasonCodes: Collection<String>,
        ): DraftRevisionAttempt {
            require(evidence.documentGoalFingerprint == parentEvidence.documentGoalFingerprint)
            require(evidence.draftFingerprint != parentEvidence.draftFingerprint)
            val surfaces = changedSurfaceFingerprints.distinct().sorted()
            val reasons = reasonCodes
                .map(String::trim)
                .filter(String::isNotBlank)
                .distinct()
                .sorted()
            return DraftRevisionAttempt(
                ordinal = ordinal,
                parentEvidenceFingerprint = parentEvidence.fingerprint,
                evidence = evidence,
                changedSurfaceFingerprints = surfaces,
                reasonCodes = reasons,
                fingerprint = revisionAttemptFingerprint(
                    ordinal,
                    parentEvidence.fingerprint,
                    evidence.fingerprint,
                    surfaces,
                    reasons,
                ),
            )
        }
    }
}

data class IterativeDraftRevisionReport(
    val initialEvidenceFingerprint: String,
    val evaluatedAttempts: List<DraftRevisionAttempt>,
    val acceptedRevisionCount: Int,
    val finalEvidence: DraftRevisionEvidence,
    val state: DraftRevisionLoopState,
    val reasonCode: String,
    val fingerprint: String,
) {
    init {
        require(initialEvidenceFingerprint.matches(SHA_256_B440))
        require(evaluatedAttempts.map { it.ordinal } == (1..evaluatedAttempts.size).toList())
        require(acceptedRevisionCount in 0..evaluatedAttempts.size)
        require(reasonCode.isNotBlank())
        require(
            fingerprint == revisionReportFingerprint(
                initialEvidenceFingerprint,
                evaluatedAttempts,
                acceptedRevisionCount,
                finalEvidence.fingerprint,
                state,
                reasonCode,
            )
        )
    }

    val finalizationAuthority: Boolean get() = false
    val factualAuthority: Boolean get() = false
    val ownerStyleAuthority: Boolean get() = false
}

/**
 * B440 closes the critique/revision control loop without inventing a second prose generator.
 *
 * A revision producer may submit a new draft, but B440 accepts progression only when the candidate
 * carries fresh B438 factual-validation evidence and fresh B439 critique evidence, remains on the
 * same B431 DocumentGoal and strictly improves the critique vector. B440 itself does not rewrite.
 */
class IterativeDraftRevisionEngine(
    private val maxRounds: Int = DEFAULT_MAX_REVISION_ROUNDS_B440,
) {
    init {
        require(maxRounds in 1..MAX_REVISION_ROUNDS_B440)
    }

    fun converge(
        initial: DraftRevisionEvidence,
        attempts: Collection<DraftRevisionAttempt>,
    ): IterativeDraftRevisionReport {
        val ordered = attempts.sortedBy { it.ordinal }
        require(ordered.map { it.ordinal } == (1..ordered.size).toList()) {
            "B440 revision ordinals must be contiguous from one"
        }
        require(ordered.size <= MAX_REVISION_ATTEMPTS_B440)

        if (!initial.factualPassed || initial.critiqueStatus == DocumentCritiqueStatus.BLOCKED) {
            return report(
                initial,
                emptyList(),
                0,
                initial,
                DraftRevisionLoopState.BLOCKED,
                "initial-draft-blocked",
            )
        }
        if (initial.critiqueStatus == DocumentCritiqueStatus.PASSED) {
            return report(
                initial,
                emptyList(),
                0,
                initial,
                DraftRevisionLoopState.CONVERGED,
                "initial-draft-converged",
            )
        }

        var current = initial
        var accepted = 0
        val evaluated = mutableListOf<DraftRevisionAttempt>()

        for (attempt in ordered) {
            if (evaluated.size >= maxRounds) {
                return report(
                    initial,
                    evaluated,
                    accepted,
                    current,
                    DraftRevisionLoopState.MAX_ROUNDS_REACHED,
                    "max-revision-rounds-reached",
                )
            }
            require(attempt.parentEvidenceFingerprint == current.fingerprint) {
                "B440 revision chain is not contiguous"
            }
            require(attempt.evidence.documentGoalFingerprint == initial.documentGoalFingerprint) {
                "B440 revision changed DocumentGoal"
            }
            require(attempt.evidence.draftFingerprint != current.draftFingerprint) {
                "B440 revision must produce a distinct draft fingerprint"
            }
            evaluated += attempt

            val candidate = attempt.evidence
            if (!candidate.factualPassed ||
                candidate.critiqueStatus == DocumentCritiqueStatus.BLOCKED
            ) {
                return report(
                    initial,
                    evaluated,
                    accepted,
                    current,
                    DraftRevisionLoopState.BLOCKED,
                    "candidate-failed-factual-or-blocking-critique",
                )
            }

            if (!strictlyImproves(current, candidate)) {
                return report(
                    initial,
                    evaluated,
                    accepted,
                    current,
                    DraftRevisionLoopState.STALLED,
                    "candidate-did-not-improve-critique",
                )
            }

            current = candidate
            accepted += 1
            if (current.critiqueStatus == DocumentCritiqueStatus.PASSED) {
                return report(
                    initial,
                    evaluated,
                    accepted,
                    current,
                    DraftRevisionLoopState.CONVERGED,
                    "revision-converged",
                )
            }
        }

        val state = if (evaluated.size >= maxRounds) {
            DraftRevisionLoopState.MAX_ROUNDS_REACHED
        } else {
            DraftRevisionLoopState.STALLED
        }
        val reason = if (state == DraftRevisionLoopState.MAX_ROUNDS_REACHED) {
            "max-revision-rounds-reached"
        } else {
            "no-further-revision-candidate"
        }
        return report(initial, evaluated, accepted, current, state, reason)
    }

    private fun strictlyImproves(
        previous: DraftRevisionEvidence,
        candidate: DraftRevisionEvidence,
    ): Boolean {
        val previousVector = Triple(
            previous.errorCount,
            previous.warningCount,
            previous.findingFingerprints.size,
        )
        val candidateVector = Triple(
            candidate.errorCount,
            candidate.warningCount,
            candidate.findingFingerprints.size,
        )
        return when {
            candidateVector.first != previousVector.first ->
                candidateVector.first < previousVector.first
            candidateVector.second != previousVector.second ->
                candidateVector.second < previousVector.second
            else ->
                candidateVector.third < previousVector.third
        }
    }

    private fun report(
        initial: DraftRevisionEvidence,
        evaluated: List<DraftRevisionAttempt>,
        accepted: Int,
        finalEvidence: DraftRevisionEvidence,
        state: DraftRevisionLoopState,
        reason: String,
    ): IterativeDraftRevisionReport =
        IterativeDraftRevisionReport(
            initialEvidenceFingerprint = initial.fingerprint,
            evaluatedAttempts = evaluated.toList(),
            acceptedRevisionCount = accepted,
            finalEvidence = finalEvidence,
            state = state,
            reasonCode = reason,
            fingerprint = revisionReportFingerprint(
                initial.fingerprint,
                evaluated,
                accepted,
                finalEvidence.fingerprint,
                state,
                reason,
            ),
        )
}

private fun revisionEvidenceFingerprint(
    documentGoalFingerprint: String,
    draftFingerprint: String,
    factualValidationFingerprint: String,
    factualPassed: Boolean,
    critiqueFingerprint: String,
    critiqueStatus: DocumentCritiqueStatus,
    errorCount: Int,
    warningCount: Int,
    findingFingerprints: List<String>,
): String = StableCognitiveIds.fingerprint(
    "draft-revision-evidence/v1",
    documentGoalFingerprint,
    draftFingerprint,
    factualValidationFingerprint,
    factualPassed.toString(),
    critiqueFingerprint,
    critiqueStatus.name,
    errorCount.toString(),
    warningCount.toString(),
    findingFingerprints.joinToString("\u001f"),
)

private fun revisionAttemptFingerprint(
    ordinal: Int,
    parentEvidenceFingerprint: String,
    evidenceFingerprint: String,
    changedSurfaceFingerprints: List<String>,
    reasonCodes: List<String>,
): String = StableCognitiveIds.fingerprint(
    "draft-revision-attempt/v1",
    ordinal.toString(),
    parentEvidenceFingerprint,
    evidenceFingerprint,
    changedSurfaceFingerprints.joinToString("\u001f"),
    reasonCodes.joinToString("\u001f"),
)

private fun revisionReportFingerprint(
    initialEvidenceFingerprint: String,
    evaluatedAttempts: List<DraftRevisionAttempt>,
    acceptedRevisionCount: Int,
    finalEvidenceFingerprint: String,
    state: DraftRevisionLoopState,
    reasonCode: String,
): String = StableCognitiveIds.fingerprint(
    "iterative-draft-revision-report/v1",
    initialEvidenceFingerprint,
    acceptedRevisionCount.toString(),
    finalEvidenceFingerprint,
    state.name,
    reasonCode,
    *evaluatedAttempts.map { it.fingerprint }.toTypedArray(),
)

private val SHA_256_B440 = Regex("[0-9a-f]{64}")
private const val DEFAULT_MAX_REVISION_ROUNDS_B440 = 6
private const val MAX_REVISION_ROUNDS_B440 = 12
private const val MAX_REVISION_ATTEMPTS_B440 = 12
