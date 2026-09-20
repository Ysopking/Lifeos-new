package app.lifeos.core.language

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class LinguisticLexiconSnapshotTest {
    @Test
    fun runtimePromotionRebuildsCompleteLanguageStackAndCanRollBack() {
        val runtime = VersionedLanguageRuntime()
        val baseline = runtime.current()
        val promotedConcepts = baseline.lexicon.concepts.map { concept ->
            if (concept.id == "continue") {
                concept.copy(variants = concept.variants + "weiterso")
            } else {
                concept
            }
        }

        val promoted = runtime.promote(
            concepts = promotedConcepts,
            promotionEvidenceFingerprint = "test-evidence",
        )

        assertEquals(2L, promoted.lexicon.revision)
        assertEquals(baseline.lexicon.fingerprint, promoted.lexicon.predecessorFingerprint)
        assertNotEquals(baseline.lexicon.fingerprint, promoted.lexicon.fingerprint)
        assertEquals(IntentType.CONTINUE, promoted.understanding.understand("weiterso").goal.intent)

        val restored = runtime.rollback(baseline.lexicon.fingerprint)
        assertEquals(baseline.lexicon.fingerprint, restored.lexicon.fingerprint)
    }
}
