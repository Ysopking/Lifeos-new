package app.lifeos.next.ui

import app.lifeos.next.ui.layout.LifeOsWindowClass
import kotlin.test.Test
import kotlin.test.assertEquals

class LifeOsWindowClassTest {
    @Test
    fun thresholdsAreStable() {
        assertEquals(LifeOsWindowClass.COMPACT, LifeOsWindowClass.fromWidthDp(599f))
        assertEquals(LifeOsWindowClass.MEDIUM, LifeOsWindowClass.fromWidthDp(600f))
        assertEquals(LifeOsWindowClass.MEDIUM, LifeOsWindowClass.fromWidthDp(839f))
        assertEquals(LifeOsWindowClass.EXPANDED, LifeOsWindowClass.fromWidthDp(840f))
    }
}
