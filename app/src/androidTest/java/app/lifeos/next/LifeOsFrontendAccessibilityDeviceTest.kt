package app.lifeos.next

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.lifeos.next.ui.LifeOsDestination
import app.lifeos.next.ui.accessibility.LifeOsSemantics
import app.lifeos.next.ui.components.LifeOsStateKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LifeOsFrontendAccessibilityDeviceTest {
    @Test
    fun readableContractsRemainAvailableOnDevice() {
        assertEquals(
            LifeOsDestination.ordered.size,
            LifeOsDestination.ordered
                .map { destination -> LifeOsSemantics.navigationLabel(destination.label) }
                .toSet()
                .size,
        )
        assertTrue(
            LifeOsDestination.ordered.all { destination ->
                LifeOsSemantics.navigationLabel(destination.label).isNotBlank()
            }
        )
        assertTrue(LifeOsStateKind.entries.all { it.visibleLabel.isNotBlank() })
        assertEquals("Runtime: Bereit", LifeOsSemantics.stateText("Runtime", "Bereit"))
    }
}
