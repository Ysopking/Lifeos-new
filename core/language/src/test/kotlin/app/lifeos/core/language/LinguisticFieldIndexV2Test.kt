package app.lifeos.core.language

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LinguisticFieldIndexV2Test {
    private val lexicon = DeterministicLinguisticFieldLexicon()
    private val index = LinguisticFieldIndexV2(lexicon)
    private val compounds = CompoundFieldResolver(lexicon, index)

    @Test
    fun `candidate retrieval is structurally bounded`() {
        listOf(
            "erstelle",
            "gesendet",
            "Jobcenterbescheid",
            "Widerspruchsfrist",
            "Ratenzahlungsvereinbarung",
        ).forEach { token ->
            assertTrue(index.candidateConceptIds(token).size <= 12, token)
        }
    }

    @Test
    fun `German life compounds segment through indexed trie`() {
        val cases = listOf(
            "Jobcenterbescheid",
            "Widerspruchsfrist",
            "Ratenzahlungsvereinbarung",
            "Krankenversicherungsbeitrag",
        )

        cases.forEachIndexed { ordinal, raw ->
            val binding = assertNotNull(compounds.resolveToken(ordinal, raw), raw)
            assertTrue(binding.components.size >= 2, raw)
            assertTrue(binding.confidence >= 0.66, raw)
        }
    }

    @Test
    fun `morphology binds inflected communication verbs`() {
        val morphology = GermanMorphologyEngine()

        assertTrue(morphology.candidates("sendest").any { it == "sende" || it == "send" })
        assertTrue(morphology.candidates("gesendet").any { it == "sende" || it == "send" })
    }
}
