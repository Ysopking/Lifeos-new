package app.lifeos.core.language

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OwnerLanguageModelBuilderTest {
    @Test
    fun model_contains_only_promoted_owner_language_rules() {
        val runtime = VersionedLanguageRuleRuntime()
        val ownerReport = passedReport('a', LexicalLearningScope.OWNER_LANGUAGE)
        val generalReport = passedReport(
            'b',
            LexicalLearningScope.VERIFIED_GENERAL_LANGUAGE,
        )
        runtime.promote(ownerReport)
        runtime.promote(generalReport)
        val snapshot = runtime.current()

        val evidence = OwnerLanguageFeatureEvidence.create(
            candidateFingerprint = ownerReport.candidateFingerprint,
            featureKind = OwnerLanguageFeatureKind.PHRASE_PATTERN,
            featureKey = "mach {slot} weiter",
            valueFingerprint = "1".repeat(64),
            supportingEpisodeFingerprints = listOf(
                "2".repeat(64),
                "3".repeat(64),
            ),
            sourceEvidenceFingerprint = "4".repeat(64),
        )
        val model = OwnerLanguageModelBuilder().build(
            snapshot,
            listOf(evidence),
        )

        assertEquals(1, model.ownerRuleFingerprints.size)
        assertEquals(1, model.features.size)
        assertEquals(ownerReport.candidateFingerprint, model.features.single().candidateFingerprint)
        assertTrue(
            model.features.none {
                it.candidateFingerprint == generalReport.candidateFingerprint
            }
        )
        assertFalse(model.truthAuthority)
        assertFalse(model.grammarPromotionAuthority)
        assertFalse(model.ownerPolicyAuthority)
        assertFalse(model.executionAuthority)
    }

    @Test
    fun evidence_for_general_rule_is_rejected() {
        val runtime = VersionedLanguageRuleRuntime()
        val general = passedReport(
            'c',
            LexicalLearningScope.VERIFIED_GENERAL_LANGUAGE,
        )
        runtime.promote(general)

        val evidence = OwnerLanguageFeatureEvidence.create(
            candidateFingerprint = general.candidateFingerprint,
            featureKind = OwnerLanguageFeatureKind.LEXICAL_FORM,
            featureKey = "foo",
            valueFingerprint = "5".repeat(64),
            supportingEpisodeFingerprints = listOf("6".repeat(64)),
            sourceEvidenceFingerprint = "7".repeat(64),
        )

        assertFailsWith<IllegalArgumentException> {
            OwnerLanguageModelBuilder().build(runtime.current(), listOf(evidence))
        }
    }

    @Test
    fun every_promoted_owner_rule_requires_feature_evidence() {
        val runtime = VersionedLanguageRuleRuntime()
        runtime.promote(passedReport('d', LexicalLearningScope.OWNER_LANGUAGE))

        assertFailsWith<IllegalArgumentException> {
            OwnerLanguageModelBuilder().build(runtime.current(), emptyList())
        }
    }

    @Test
    fun evidence_order_and_exact_duplicates_are_deterministic() {
        val runtime = VersionedLanguageRuleRuntime()
        val owner = passedReport('e', LexicalLearningScope.OWNER_LANGUAGE)
        runtime.promote(owner)
        val a = OwnerLanguageFeatureEvidence.create(
            owner.candidateFingerprint,
            OwnerLanguageFeatureKind.PRAGMATIC_CUE,
            "könntest du",
            "8".repeat(64),
            listOf("9".repeat(64)),
            "a".repeat(64),
        )
        val b = OwnerLanguageFeatureEvidence.create(
            owner.candidateFingerprint,
            OwnerLanguageFeatureKind.REFERENCE_HABIT,
            "das->goal",
            "b".repeat(64),
            listOf("c".repeat(64)),
            "d".repeat(64),
        )
        val builder = OwnerLanguageModelBuilder()

        assertEquals(
            builder.build(runtime.current(), listOf(a, b)),
            builder.build(runtime.current(), listOf(b, a, a)),
        )
    }

    private fun passedReport(
        seed: Char,
        scope: LexicalLearningScope,
    ): LanguageShadowEvaluationReport {
        val graph = "e".repeat(64)
        return LanguageShadowEvaluator().evaluate(
            candidateKind = LanguageShadowCandidateKind.SEMANTIC_MAPPING,
            candidateFingerprint = seed.toString().repeat(64),
            scope = scope,
            cases = listOf(
                LanguageShadowCaseResult.create(
                    caseId = "target-$seed",
                    protectedCase = false,
                    targetCase = true,
                    baselineIntentName = "UNKNOWN",
                    candidateIntentName = "CONTINUE",
                    baselineActionGraphFingerprint = "f".repeat(64),
                    candidateActionGraphFingerprint = graph,
                    baselineExternalEffectExecutable = false,
                    candidateExternalEffectExecutable = false,
                    expectedIntentName = "CONTINUE",
                    expectedActionGraphFingerprint = graph,
                ),
                LanguageShadowCaseResult.create(
                    caseId = "protected-$seed",
                    protectedCase = true,
                    targetCase = false,
                    baselineIntentName = "QUERY",
                    candidateIntentName = "QUERY",
                    baselineActionGraphFingerprint = "1".repeat(64),
                    candidateActionGraphFingerprint = "1".repeat(64),
                    baselineExternalEffectExecutable = false,
                    candidateExternalEffectExecutable = false,
                ),
            ),
        )
    }
}
