package app.lifeos.core.language

import kotlin.test.Test
import kotlin.test.assertEquals

class SpeechActParserTest {
    private val normalizer = UtteranceNormalizer()
    private val graphExtractor = LanguageSemanticGraphExtractor()
    private val parser = SpeechActParser()

    @Test
    fun `question inversion differs from declarative auxiliary placement`() {
        assertEquals(SpeechActType.QUESTION, speechAct("Ist die Mail gesendet?").type)
        assertEquals(SpeechActType.ASSERTION, speechAct("Die Mail ist gesendet.").type)
    }

    @Test
    fun `addressed modal request differs from first person assertion`() {
        assertEquals(SpeechActType.REQUEST, speechAct("Kannst du die Mail senden?").type)
        assertEquals(SpeechActType.ASSERTION, speechAct("Ich kann die Mail senden.").type)
    }

    @Test
    fun `third person modal question differs from formal addressed request`() {
        assertEquals(SpeechActType.QUESTION, speechAct("Kann sie die Mail senden?").type)
        assertEquals(SpeechActType.REQUEST, speechAct("Können Sie die Mail senden?").type)
    }

    @Test
    fun `first person modal question differs from second person assertion`() {
        assertEquals(SpeechActType.QUESTION, speechAct("Soll ich die Mail senden?").type)
        assertEquals(SpeechActType.ASSERTION, speechAct("Du sollst die Mail senden.").type)
    }

    @Test
    fun `polite imperative request differs from direct command`() {
        assertEquals(SpeechActType.REQUEST, speechAct("Bitte sende die Mail.").type)
        assertEquals(SpeechActType.COMMAND, speechAct("Sende die Mail.").type)
    }

    @Test
    fun `terminal question mark outside clause span remains syntax evidence`() {
        val utterance = normalizer.normalize("Ist die Mail gesendet?")
        val graph = graphExtractor.extract(utterance, emptyList())
        val clause = graph.clauses.single()

        assertEquals("Ist die Mail gesendet", clause.text)
        assertEquals("?", utterance.tokens[clause.tokenEndExclusive].original)
        assertEquals(
            SpeechActType.QUESTION,
            parser.parse(utterance, graph).getValue(clause.id).type,
        )
    }

    private fun speechAct(text: String): SpeechAct {
        val utterance = normalizer.normalize(text)
        val graph = graphExtractor.extract(utterance, emptyList())
        return parser.parse(utterance, graph).values.single()
    }
}
