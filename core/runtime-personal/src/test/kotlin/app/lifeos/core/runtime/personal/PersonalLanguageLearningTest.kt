package app.lifeos.core.runtime.personal

import app.lifeos.core.language.IntentType
import app.lifeos.core.language.LanguageUnderstandingEngine
import app.lifeos.core.language.VersionedLanguageRuntime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PersonalLanguageLearningTest {
    @Test
    fun promotionRequiresRepeatedCrossConversationEvidenceAndOnlyAddsAlias() {
        val runtime = VersionedLanguageRuntime()
        val current = runtime.current()
        val understanding = LanguageUnderstandingEngine().understand("weiter")
        assertEquals(IntentType.CONTINUE, understanding.goal.intent)

        val target = current.lexicon.concepts.first { IntentType.CONTINUE in it.intentBias }
        val observations = (1..5).map { index ->
            PersonalLanguageObservation(
                conversationId = "c$index",
                surface = "weiterso",
                targetConceptId = target.id,
                accepted = true,
                sourceRef = null,
            )
        }
        val candidate = PersonalLanguageCandidate.create("weiterso", target.id, observations)
        val promoted = PersonalLanguagePromotionCoordinator(runtime).promote(candidate)

        assertTrue(promoted != null)
        assertTrue("weiterso" in promoted!!.byId(target.id)!!.variants)
        assertEquals(IntentType.CONTINUE, runtime.current().understanding.understand("weiterso").goal.intent)
    }

    @Test
    fun insufficientEvidenceDoesNotPromote() {
        val runtime = VersionedLanguageRuntime()
        val target = runtime.current().lexicon.concepts.first { IntentType.SEARCH in it.intentBias }
        val candidate = PersonalLanguageCandidate.create(
            "nachschauen",
            target.id,
            listOf(
                PersonalLanguageObservation("c1", "nachschauen", target.id, true, null),
                PersonalLanguageObservation("c2", "nachschauen", target.id, true, null),
            ),
        )

        assertNull(PersonalLanguagePromotionCoordinator(runtime).promote(candidate))
    }

    @Test
    fun correctionLanguageIsProjectedWithoutCreatingExecutionAuthority() {
        assertEquals(
            PersonalLanguageFeedbackKind.REFERENCE_CORRECTION,
            PersonalLanguageFeedbackProjector.classify("Nein, ich meinte das andere"),
        )
    }
}
