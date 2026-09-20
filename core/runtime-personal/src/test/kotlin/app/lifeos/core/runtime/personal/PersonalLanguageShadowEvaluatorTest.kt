package app.lifeos.core.runtime.personal

import app.lifeos.core.language.IntentType
import app.lifeos.core.language.LinguisticLexiconSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PersonalLanguageShadowEvaluatorTest {
    @Test
    fun safeAliasMustResolveTargetAndPreserveProtectedActionSemantics() {
        val lexicon = LinguisticLexiconSnapshot.builtin()
        val target = lexicon.concepts.first { IntentType.CONTINUE in it.intentBias }
        val observations = (1..5).map { index ->
            PersonalLanguageObservation(
                conversationId = "conversation-$index",
                surface = "weiterso",
                targetConceptId = target.id,
                accepted = true,
                sourceRef = null,
            )
        }
        val candidate = PersonalLanguageCandidate.create(
            surface = "weiterso",
            targetConceptId = target.id,
            observations = observations,
        )

        val report = PersonalLanguageShadowEvaluator().evaluate(lexicon, candidate)

        assertEquals(IntentType.CONTINUE, report.candidateIntent)
        assertTrue(report.candidateRecognized)
        assertTrue(report.protectedCasesStable)
        assertTrue(report.passed)
    }
}
