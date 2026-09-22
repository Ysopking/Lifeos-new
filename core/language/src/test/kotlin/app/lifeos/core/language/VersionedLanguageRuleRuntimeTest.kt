package app.lifeos.core.language

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class VersionedLanguageRuleRuntimeTest {
    @Test
    fun passed_shadow_report_promotes_versioned_rule_without_execution_authority() {
        val runtime = VersionedLanguageRuleRuntime()
        val before = runtime.current()
        val report = passedReport(
            fingerprintSeed = 'a',
            scope = LexicalLearningScope.OWNER_LANGUAGE,
        )

        val result = assertIs<LanguageRulePromotionResult.Promoted>(
            runtime.promote(report)
        )

        assertEquals(before.revision + 1L, result.snapshot.revision)
        assertEquals(before.fingerprint, result.snapshot.predecessorFingerprint)
        assertTrue(result.snapshot.contains(report.candidateFingerprint))
        assertFalse(result.rule.executionAuthority)
        assertFalse(result.rule.ownerPolicyAuthority)
    }

    @Test
    fun failed_shadow_report_is_rejected() {
        val runtime = VersionedLanguageRuleRuntime()
        val report = failedReport('b')

        val result = assertIs<LanguageRulePromotionResult.Rejected>(
            runtime.promote(report)
        )

        assertEquals("shadow-not-passed", result.reason)
        assertEquals(1L, runtime.current().revision)
    }

    @Test
    fun external_observation_cannot_be_promoted_even_when_shadow_stable() {
        val runtime = VersionedLanguageRuleRuntime()
        val report = passedReport(
            fingerprintSeed = 'c',
            scope = LexicalLearningScope.EXTERNAL_LANGUAGE_OBSERVATION,
        )

        val result = assertIs<LanguageRulePromotionResult.Rejected>(
            runtime.promote(report)
        )

        assertEquals("external-observation-not-promotable", result.reason)
    }

    @Test
    fun rollback_restores_exact_known_snapshot() {
        val runtime = VersionedLanguageRuleRuntime()
        val initial = runtime.current()
        val first = assertIs<LanguageRulePromotionResult.Promoted>(
            runtime.promote(
                passedReport('d', LexicalLearningScope.OWNER_LANGUAGE)
            )
        ).snapshot
        assertIs<LanguageRulePromotionResult.Promoted>(
            runtime.promote(
                passedReport('e', LexicalLearningScope.VERIFIED_GENERAL_LANGUAGE)
            )
        )

        val rollback = runtime.rollback(first.fingerprint)

        assertEquals(first, rollback.snapshot)
        assertEquals(first, runtime.current())
        assertSame(first, runtime.knownSnapshot(first.fingerprint))
        assertTrue(runtime.knownSnapshot(initial.fingerprint) != null)
        assertFalse(rollback.executionAuthority)
    }

    @Test
    fun promoting_same_candidate_is_idempotent() {
        val runtime = VersionedLanguageRuleRuntime()
        val report = passedReport('f', LexicalLearningScope.OWNER_LANGUAGE)
        assertIs<LanguageRulePromotionResult.Promoted>(runtime.promote(report))

        val second = assertIs<LanguageRulePromotionResult.AlreadyActive>(
            runtime.promote(report)
        )

        assertEquals(2L, second.snapshot.revision)
    }

    private fun passedReport(
        fingerprintSeed: Char,
        scope: LexicalLearningScope,
    ): LanguageShadowEvaluationReport {
        val candidate = fingerprintSeed.toString().repeat(64)
        return LanguageShadowEvaluator().evaluate(
            candidateKind = LanguageShadowCandidateKind.SEMANTIC_MAPPING,
            candidateFingerprint = candidate,
            scope = scope,
            cases = listOf(
                LanguageShadowCaseResult.create(
                    caseId = "target-$fingerprintSeed",
                    protectedCase = false,
                    targetCase = true,
                    baselineIntentName = "UNKNOWN",
                    candidateIntentName = "CONTINUE",
                    baselineActionGraphFingerprint = "1".repeat(64),
                    candidateActionGraphFingerprint = "2".repeat(64),
                    baselineExternalEffectExecutable = false,
                    candidateExternalEffectExecutable = false,
                    expectedIntentName = "CONTINUE",
                    expectedActionGraphFingerprint = "2".repeat(64),
                ),
                LanguageShadowCaseResult.create(
                    caseId = "protected-$fingerprintSeed",
                    protectedCase = true,
                    targetCase = false,
                    baselineIntentName = "QUERY",
                    candidateIntentName = "QUERY",
                    baselineActionGraphFingerprint = "3".repeat(64),
                    candidateActionGraphFingerprint = "3".repeat(64),
                    baselineExternalEffectExecutable = false,
                    candidateExternalEffectExecutable = false,
                ),
            ),
        )
    }

    private fun failedReport(seed: Char): LanguageShadowEvaluationReport =
        LanguageShadowEvaluator().evaluate(
            candidateKind = LanguageShadowCandidateKind.PHRASE_GRAMMAR,
            candidateFingerprint = seed.toString().repeat(64),
            scope = LexicalLearningScope.OWNER_LANGUAGE,
            cases = listOf(
                LanguageShadowCaseResult.create(
                    caseId = "target-fail",
                    protectedCase = false,
                    targetCase = true,
                    baselineIntentName = "UNKNOWN",
                    candidateIntentName = "QUERY",
                    baselineActionGraphFingerprint = "4".repeat(64),
                    candidateActionGraphFingerprint = "5".repeat(64),
                    baselineExternalEffectExecutable = false,
                    candidateExternalEffectExecutable = false,
                    expectedIntentName = "CONTINUE",
                    expectedActionGraphFingerprint = "6".repeat(64),
                ),
                LanguageShadowCaseResult.create(
                    caseId = "protected-fail",
                    protectedCase = true,
                    targetCase = false,
                    baselineIntentName = "QUERY",
                    candidateIntentName = "QUERY",
                    baselineActionGraphFingerprint = "7".repeat(64),
                    candidateActionGraphFingerprint = "7".repeat(64),
                    baselineExternalEffectExecutable = false,
                    candidateExternalEffectExecutable = false,
                ),
            ),
        )
}
