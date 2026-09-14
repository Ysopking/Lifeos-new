package app.lifeos.next.ui

import app.lifeos.next.ui.accessibility.LifeOsSemantics
import app.lifeos.next.ui.components.LifeOsStateKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LifeOsAccessibilityContractTest {
    @Test
    fun destinationsHaveStableReadableLabels() {
        val labels = LifeOsDestination.ordered.map { LifeOsSemantics.navigationLabel(it.label) }
        assertTrue(labels.all { it.isNotBlank() })
        assertEquals(labels.size, labels.distinct().size)
    }

    @Test
    fun stateKindsAlwaysExposeText() {
        assertTrue(LifeOsStateKind.entries.all { it.visibleLabel.isNotBlank() })
    }
}
