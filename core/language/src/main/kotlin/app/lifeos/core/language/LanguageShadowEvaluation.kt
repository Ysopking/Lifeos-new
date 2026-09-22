package app.lifeos.core.language

import app.lifeos.core.model.StableCognitiveIds

enum class LanguageShadowCandidateKind {
    LEXICAL,
    PHRASE_GRAMMAR,
    DISCOURSE,
    REFERENCE,
    PRAGMATIC,
    SEMANTIC_MAPPING,
}

data class LanguageShadowCaseResult(
    val caseId: String,
    val protectedCase: Boolean,
    val targetCase: Boolean,
    val baselineIntentName: String,
    val candidateIntentName: String,
    val baselineActionGraphFingerprint: String,
    val candidateActionGraphFingerprint: String,
    val baselineExternalEffectExecutable: Boolean,
    val candidateExternalEffectExecutable: Boolean,
    val expectedIntentName: String?,
    val expectedActionGraphFingerprint: String?,
    val fingerprint: String,
) {
    init {
        require(caseId.isNotBlank())
        require(protectedCase || targetCase)
        require(!(protectedCase && targetCase))
        require(baselineIntentName.isNotBlank())
        require(candidateIntentName.isNotBlank())
        require(baselineActionGraphFingerprint.matches(SHA_256_B428))
        require(candidateActionGraphFingerprint.matches(SHA_256_B428))
        require(expectedIntentName == null || expectedIntentName.isNotBlank())
        require(
            expectedActionGraphFingerprint == null ||
                expectedActionGraphFingerprint.matches(SHA_256_B428)
        )
        if (targetCase) {
            require(expectedIntentName != null) {
                "B428 target case requires an expected intent"
            }
        } else {
            require(expectedIntentName == null)
            require(expectedActionGraphFingerprint == null)
        }
        require(
            fingerprint == shadowCaseFingerprint(
                caseId,
                protectedCase,
                targetCase,
                baselineIntentName,
                candidateIntentName,
                baselineActionGraphFingerprint,
                candidateActionGraphFingerprint,
                baselineExternalEffectExecutable,
                candidateExternalEffectExecutable,
                expectedIntentName,
                expectedActionGraphFingerprint,
            )
        )
    }

    val protectedStable: Boolean
        get() = !protectedCase || (
            baselineIntentName == candidateIntentName &&
                baselineActionGraphFingerprint == candidateActionGraphFingerprint &&
                baselineExternalEffectExecutable == candidateExternalEffectExecutable
            )

    val targetMatched: Boolean
        get() = !targetCase || (
            candidateIntentName == expectedIntentName &&
                (
                    expectedActionGraphFingerprint == null ||
                        candidateActionGraphFingerprint == expectedActionGraphFingerprint
                    ) &&
                !newExternalEffectIntroduced
            )

    val newExternalEffectIntroduced: Boolean
        get() = !baselineExternalEffectExecutable && candidateExternalEffectExecutable

    companion object {
        fun create(
            caseId: String,
            protectedCase: Boolean,
            targetCase: Boolean,
            baselineIntentName: String,
            candidateIntentName: String,
            baselineActionGraphFingerprint: String,
            candidateActionGraphFingerprint: String,
            baselineExternalEffectExecutable: Boolean,
            candidateExternalEffectExecutable: Boolean,
            expectedIntentName: String? = null,
            expectedActionGraphFingerprint: String? = null,
        ): LanguageShadowCaseResult =
            LanguageShadowCaseResult(
                caseId = caseId,
                protectedCase = protectedCase,
                targetCase = targetCase,
                baselineIntentName = baselineIntentName,
                candidateIntentName = candidateIntentName,
                baselineActionGraphFingerprint = baselineActionGraphFingerprint,
                candidateActionGraphFingerprint = candidateActionGraphFingerprint,
                baselineExternalEffectExecutable = baselineExternalEffectExecutable,
                candidateExternalEffectExecutable = candidateExternalEffectExecutable,
                expectedIntentName = expectedIntentName,
                expectedActionGraphFingerprint = expectedActionGraphFingerprint,
                fingerprint = shadowCaseFingerprint(
                    caseId,
                    protectedCase,
                    targetCase,
                    baselineIntentName,
                    candidateIntentName,
                    baselineActionGraphFingerprint,
                    candidateActionGraphFingerprint,
                    baselineExternalEffectExecutable,
                    candidateExternalEffectExecutable,
                    expectedIntentName,
                    expectedActionGraphFingerprint,
                ),
            )
    }
}

data class LanguageShadowEvaluationReport(
    val candidateKind: LanguageShadowCandidateKind,
    val candidateFingerprint: String,
    val scope: LexicalLearningScope,
    val caseFingerprints: List<String>,
    val protectedCaseCount: Int,
    val targetCaseCount: Int,
    val protectedCasesStable: Boolean,
    val targetCasesMatched: Boolean,
    val noNewExternalEffects: Boolean,
    val passed: Boolean,
    val fingerprint: String,
) {
    init {
        require(candidateFingerprint.matches(SHA_256_B428))
        require(caseFingerprints.isNotEmpty())
        require(caseFingerprints == caseFingerprints.distinct().sorted())
        require(protectedCaseCount > 0)
        require(targetCaseCount > 0)
        require(
            passed == (
                protectedCasesStable &&
                    targetCasesMatched &&
                    noNewExternalEffects
                )
        )
        require(
            fingerprint == shadowReportFingerprint(
                candidateKind,
                candidateFingerprint,
                scope,
                caseFingerprints,
                protectedCaseCount,
                targetCaseCount,
                protectedCasesStable,
                targetCasesMatched,
                noNewExternalEffects,
                passed,
            )
        )
    }

    val promotionAuthority: Boolean get() = false
    val rollbackAuthority: Boolean get() = false
    val executionAuthority: Boolean get() = false
}

/**
 * B428 is a deterministic shadow gate for learned-language candidates.
 *
 * Candidate-specific shadow runtimes provide exact baseline/candidate case results. B428 itself
 * never mutates the productive parser. It requires target recognition, protected-case semantic
 * stability, and forbids any newly introduced executable external side effect.
 */
class LanguageShadowEvaluator {
    fun evaluate(
        candidateKind: LanguageShadowCandidateKind,
        candidateFingerprint: String,
        scope: LexicalLearningScope,
        cases: Collection<LanguageShadowCaseResult>,
    ): LanguageShadowEvaluationReport {
        require(candidateFingerprint.matches(SHA_256_B428))
        require(cases.isNotEmpty())

        val canonical = cases
            .groupBy { it.fingerprint }
            .map { (_, same) ->
                require(same.all { it == same.first() }) {
                    "Conflicting B428 shadow case identity"
                }
                same.first()
            }
            .sortedBy { it.fingerprint }

        require(canonical.any { it.protectedCase }) {
            "B428 requires protected regression cases"
        }
        require(canonical.any { it.targetCase }) {
            "B428 requires target recognition cases"
        }
        require(canonical.map { it.caseId }.distinct().size == canonical.size) {
            "B428 shadow case ids must be unique"
        }

        val protectedStable =
            canonical.filter { it.protectedCase }.all { it.protectedStable }
        val targetsMatched =
            canonical.filter { it.targetCase }.all { it.targetMatched }
        val noNewExternalEffects =
            canonical.none { it.newExternalEffectIntroduced }
        val passed =
            protectedStable && targetsMatched && noNewExternalEffects
        val fingerprints = canonical.map { it.fingerprint }.sorted()
        val protectedCount = canonical.count { it.protectedCase }
        val targetCount = canonical.count { it.targetCase }

        return LanguageShadowEvaluationReport(
            candidateKind = candidateKind,
            candidateFingerprint = candidateFingerprint,
            scope = scope,
            caseFingerprints = fingerprints,
            protectedCaseCount = protectedCount,
            targetCaseCount = targetCount,
            protectedCasesStable = protectedStable,
            targetCasesMatched = targetsMatched,
            noNewExternalEffects = noNewExternalEffects,
            passed = passed,
            fingerprint = shadowReportFingerprint(
                candidateKind,
                candidateFingerprint,
                scope,
                fingerprints,
                protectedCount,
                targetCount,
                protectedStable,
                targetsMatched,
                noNewExternalEffects,
                passed,
            ),
        )
    }
}

private fun shadowCaseFingerprint(
    caseId: String,
    protectedCase: Boolean,
    targetCase: Boolean,
    baselineIntentName: String,
    candidateIntentName: String,
    baselineActionGraphFingerprint: String,
    candidateActionGraphFingerprint: String,
    baselineExternalEffectExecutable: Boolean,
    candidateExternalEffectExecutable: Boolean,
    expectedIntentName: String?,
    expectedActionGraphFingerprint: String?,
): String = StableCognitiveIds.fingerprint(
    "language-shadow-case/v1",
    caseId,
    protectedCase.toString(),
    targetCase.toString(),
    baselineIntentName,
    candidateIntentName,
    baselineActionGraphFingerprint,
    candidateActionGraphFingerprint,
    baselineExternalEffectExecutable.toString(),
    candidateExternalEffectExecutable.toString(),
    expectedIntentName.orEmpty(),
    expectedActionGraphFingerprint.orEmpty(),
)

private fun shadowReportFingerprint(
    candidateKind: LanguageShadowCandidateKind,
    candidateFingerprint: String,
    scope: LexicalLearningScope,
    caseFingerprints: List<String>,
    protectedCaseCount: Int,
    targetCaseCount: Int,
    protectedCasesStable: Boolean,
    targetCasesMatched: Boolean,
    noNewExternalEffects: Boolean,
    passed: Boolean,
): String = StableCognitiveIds.fingerprint(
    "language-shadow-evaluation-report/v1",
    candidateKind.name,
    candidateFingerprint,
    scope.name,
    protectedCaseCount.toString(),
    targetCaseCount.toString(),
    protectedCasesStable.toString(),
    targetCasesMatched.toString(),
    noNewExternalEffects.toString(),
    passed.toString(),
    *caseFingerprints.toTypedArray(),
)

private val SHA_256_B428 = Regex("[0-9a-f]{64}")
