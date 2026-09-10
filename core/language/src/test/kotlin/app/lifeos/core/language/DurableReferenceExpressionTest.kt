package app.lifeos.core.language

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DurableReferenceExpressionTest {
    private val normalizer = UtteranceNormalizer()
    private val extractor = ReferenceExpressionExtractor()

    @Test
    fun `weiter becomes previous goal reference`() {
        val expressions = extractor.extract(normalizer.normalize("Weiter"), IntentType.CONTINUE)

        val reference = expressions.single()
        assertEquals(ReferenceKind.PREVIOUS, reference.kind)
        assertEquals(setOf("goal"), reference.preferredKinds)
    }

    @Test
    fun `das Bild parses image reference`() {
        val reference = extractor.extract(normalizer.normalize("Das Bild"), IntentType.QUERY).single()

        assertEquals(ReferenceKind.THAT, reference.kind)
        assertEquals(setOf("image"), reference.preferredKinds)
    }

    @Test
    fun `die APK parses apk reference`() {
        val reference = extractor.extract(normalizer.normalize("Die APK"), IntentType.QUERY).single()

        assertEquals(ReferenceKind.THAT, reference.kind)
        assertEquals(setOf("apk"), reference.preferredKinds)
    }

    @Test
    fun `das andere Modul parses contrast reference`() {
        val reference = extractor.extract(normalizer.normalize("Das andere Modul"), IntentType.QUERY).single()

        assertEquals(ReferenceKind.OTHER, reference.kind)
        assertEquals(setOf("module"), reference.preferredKinds)
    }

    @Test
    fun `wie gestern yields temporal reference without explicit object kind`() {
        val reference = extractor.extract(normalizer.normalize("Wie gestern"), IntentType.QUERY).single()

        assertEquals(ReferenceKind.YESTERDAY, reference.kind)
        assertTrue(reference.preferredKinds.isEmpty())
    }
}
