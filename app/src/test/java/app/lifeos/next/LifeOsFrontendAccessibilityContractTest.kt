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
 * These assertions are pure Kotlin and intentionally run on the JVM. The F11 workflow separately
 * cold-starts the real ChatMainActivity/LifeOsApplication on an Android emulator, so this contract
 * does not need to boot the productive kernel a second time through instrumentation.
 */
class LifeOsFrontendAccessibilityContractTest {
    @Test
    fun readableContractsRemainStable() {
        assertEquals(
            listOf(
                LifeOsDestination.CHAT,
                LifeOsDestination.GOALS,
                LifeOsDestination.MEMORY,
                LifeOsDestination.SYSTEM,
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
        assertTrue(LifeOsStateKind.entries.all { it.visibleLabel.isNotBlank() })
        assertEquals("Runtime: Bereit", LifeOsSemantics.stateText("Runtime", "Bereit"))
    }
}
