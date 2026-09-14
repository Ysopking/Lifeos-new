package app.lifeos.next

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.lifeos.next.ui.LifeOsDestination
import app.lifeos.next.ui.accessibility.LifeOsSemantics
import app.lifeos.next.ui.components.LifeOsStateKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LifeOsFrontendAccessibilityDeviceTest {
    @Test
    fun launcherAndReadableContractsRemainAvailable() {
        val scenario = ActivityScenario.launch(ChatMainActivity::class.java)
        try {
            scenario.onActivity { activity ->
                assertFalse(activity.isFinishing)
            }
            assertTrue(
                LifeOsDestination.ordered.all { destination ->
                    LifeOsSemantics.navigationLabel(destination.label).isNotBlank()
                }
            )
            assertTrue(LifeOsStateKind.entries.all { it.visibleLabel.isNotBlank() })
            assertTrue(LifeOsSemantics.stateText("Runtime", "Bereit").isNotBlank())
        } finally {
            scenario.close()
        }
    }
}
