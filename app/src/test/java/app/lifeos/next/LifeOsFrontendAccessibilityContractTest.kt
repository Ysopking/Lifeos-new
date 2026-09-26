package app.lifeos.next

import app.lifeos.next.ui.LifeOsDestination
import app.lifeos.next.ui.accessibility.LifeOsSemantics
import app.lifeos.next.ui.components.LifeOsStateKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic accessibility semantics contract.
 *
 * Chat remains the single default surface. Secondary productive workspaces live behind one hub and
 * must still expose distinct readable labels to accessibility services.
 */
class LifeOsFrontendAccessibilityContractTest {
    @Test
    fun readableContractsRemainStable() {
        assertEquals(
            listOf(
                LifeOsDestination.CHAT,
                LifeOsDestination.PROJECTS,
                LifeOsDestination.WEEK,
                LifeOsDestination.ACTIONS,
                LifeOsDestination.ARTIFACTS,
            ),
            LifeOsDestination.ordered,
        )
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
        assertEquals(
            "LIFEOS",
            LifeOsSemantics.navigationLabel(LifeOsDestination.CHAT.label),
        )
        assertTrue(LifeOsStateKind.entries.all { it.visibleLabel.isNotBlank() })
        assertEquals("Runtime: Bereit", LifeOsSemantics.stateText("Runtime", "Bereit"))
    }
}
