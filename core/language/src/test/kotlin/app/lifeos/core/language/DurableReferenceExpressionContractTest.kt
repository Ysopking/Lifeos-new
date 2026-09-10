package app.lifeos.core.language

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DurableReferenceExpressionContractTest {
    private val engine = LanguageUnderstandingEngine()

    @Test
    fun `continue produces previous goal reference`() {
        val expression = expression("Weiter", ReferenceKind.PREVIOUS)
        assertEquals(setOf("goal"), expression.preferredKinds)
    }

    @Test
    fun `german image article produces image reference`() {
        val expression = expression("das Bild", ReferenceKind.THAT)
        assertEquals(setOf("image"), expression.preferredKinds)
    }

    @Test
    fun `german apk article produces apk reference`() {
        val expression = expression("die APK", ReferenceKind.THAT)
        assertEquals(setOf("apk"), expression.preferredKinds)
    }

    @Test
    fun `other module is represented as contrast reference`() {
        val expression = expression("das andere Modul", ReferenceKind.OTHER)
        assertEquals(setOf("module"), expression.preferredKinds)
        assertTrue(
            engine.understand("das andere Modul").goal.references.none {
                it.expression.kind == ReferenceKind.THAT
            }
        )
    }

    @Test
    fun `yesterday reference does not require an explicit object kind`() {
        val expression = expression("wie gestern", ReferenceKind.YESTERDAY)
        assertTrue(expression.preferredKinds.isEmpty())
    }

    private fun expression(text: String, kind: ReferenceKind): ReferenceExpression =
        engine.understand(text).goal.references.single { it.expression.kind == kind }.expression
}
