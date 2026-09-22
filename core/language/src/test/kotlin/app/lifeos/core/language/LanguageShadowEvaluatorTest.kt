package app.lifeos.core.language

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LanguageShadowEvaluatorTest {
    @Test
    fun matching_target_and_stable_protected_cases_pass_shadow_gate() {
        val baselineTarget = "1".repeat(64)
        val learnedTarget = "2".repeat(64)
        val protected = "3".repeat(64)
        val report = LanguageShadowEvaluator().evaluate(
            candidateKind = LanguageShadowCandidateKind.SEMANTIC_MAPPING,
            candidateFingerprint = "a".repeat(64),
            scope = LexicalLearningScope.OWNER_LANGUAGE,
            cases = listOf(
                LanguageShadowCaseResult.create(
                    caseId = "target-owner-phrase",
                    protectedCase = false,
                    targetCase = true,
                    baselineIntentName = "UNKNOWN",
                    candidateIntentName = "CONTINUE",
                    baselineActionGraphFingerprint = baselineTarget,
                    candidateActionGraphFingerprint = learnedTarget,
                    baselineExternalEffectExecutable = false,
                    candidateExternalEffectExecutable = false,
                    expectedIntentName = "CONTINUE",
                    expectedActionGraphFingerprint = learnedTarget,
                ),
                LanguageShadowCaseResult.create(
                    caseId = "protected-negation",
                    protectedCase = true,
                    targetCase = false,
                    baselineIntentName = "COMMUNICATE",
                    candidateIntentName = "COMMUNICATE",
                    baselineActionGraphFingerprint = protected,
                    candidateActionGraphFingerprint = protected,
                    baselineExternalEffectExecutable = false,
                    candidateExternalEffectExecutable = false,
                ),
            ),
        )

        assertTrue(report.passed)
        assertTrue(report.protectedCasesStable)
        assertTrue(report.targetCasesMatched)
        assertTrue(report.noNewExternalEffects)
        assertFalse(report.promotionAuthority)
        assertFalse(report.rollbackAuthority)
        assertFalse(report.executionAuthority)
    }

    @Test
    fun protected_semantic_change_fails_shadow_gate() {
        val report = LanguageShadowEvaluator().evaluate(
            LanguageShadowCandidateKind.PHRASE_GRAMMAR,
            "b".repeat(64),
            LexicalLearningScope.VERIFIED_GENERAL_LANGUAGE,
            listOf(
                targetCase("target", "4".repeat(64)),
                LanguageShadowCaseResult.create(
                    caseId = "protected",
                    protectedCase = true,
                    targetCase = false,
                    baselineIntentName = "QUERY",
                    candidateIntentName = "CONTINUE",
                    baselineActionGraphFingerprint = "5".repeat(64),
                    candidateActionGraphFingerprint = "6".repeat(64),
                    baselineExternalEffectExecutable = false,
                    candidateExternalEffectExecutable = false,
                ),
            ),
        )

        assertFalse(report.passed)
        assertFalse(report.protectedCasesStable)
    }

    @Test
    fun newly_introduced_external_effect_fails_even_on_target_case() {
        val graph = "7".repeat(64)
        val report = LanguageShadowEvaluator().evaluate(
            LanguageShadowCandidateKind.PRAGMATIC,
            "c".repeat(64),
            LexicalLearningScope.OWNER_LANGUAGE,
            listOf(
                LanguageShadowCaseResult.create(
                    caseId = "target",
                    protectedCase = false,
                    targetCase = true,
                    baselineIntentName = "UNKNOWN",
                    candidateIntentName = "COMMUNICATE",
                    baselineActionGraphFingerprint = "8".repeat(64),
                    candidateActionGraphFingerprint = graph,
                    baselineExternalEffectExecutable = false,
                    candidateExternalEffectExecutable = true,
                    expectedIntentName = "COMMUNICATE",
                    expectedActionGraphFingerprint = graph,
                ),
                protectedCase("protected"),
            ),
        )

        assertFalse(report.passed)
        assertFalse(report.noNewExternalEffects)
    }

    @Test
    fun target_mismatch_fails_shadow_gate() {
        val expected = "9".repeat(64)
        val report = LanguageShadowEvaluator().evaluate(
            LanguageShadowCandidateKind.REFERENCE,
            "d".repeat(64),
            LexicalLearningScope.OWNER_LANGUAGE,
            listOf(
                LanguageShadowCaseResult.create(
                    caseId = "target",
                    protectedCase = false,
                    targetCase = true,
                    baselineIntentName = "UNKNOWN",
                    candidateIntentName = "QUERY",
                    baselineActionGraphFingerprint = "a".repeat(64),
                    candidateActionGraphFingerprint = "b".repeat(64),
                    baselineExternalEffectExecutable = false,
                    candidateExternalEffectExecutable = false,
                    expectedIntentName = "CONTINUE",
                    expectedActionGraphFingerprint = expected,
                ),
                protectedCase("protected"),
            ),
        )

        assertFalse(report.passed)
        assertFalse(report.targetCasesMatched)
    }

    private fun targetCase(
        id: String,
        graph: String,
    ): LanguageShadowCaseResult =
        LanguageShadowCaseResult.create(
            caseId = id,
            protectedCase = false,
            targetCase = true,
            baselineIntentName = "UNKNOWN",
            candidateIntentName = "CONTINUE",
            baselineActionGraphFingerprint = "e".repeat(64),
            candidateActionGraphFingerprint = graph,
            baselineExternalEffectExecutable = false,
            candidateExternalEffectExecutable = false,
            expectedIntentName = "CONTINUE",
            expectedActionGraphFingerprint = graph,
        )

    private fun protectedCase(id: String): LanguageShadowCaseResult =
        LanguageShadowCaseResult.create(
            caseId = id,
            protectedCase = true,
            targetCase = false,
            baselineIntentName = "QUERY",
            candidateIntentName = "QUERY",
            baselineActionGraphFingerprint = "f".repeat(64),
            candidateActionGraphFingerprint = "f".repeat(64),
            baselineExternalEffectExecutable = false,
            candidateExternalEffectExecutable = false,
        )
}
