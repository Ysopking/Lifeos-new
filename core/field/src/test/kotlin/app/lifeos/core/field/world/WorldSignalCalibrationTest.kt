package app.lifeos.core.field.world

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WorldSignalCalibrationTest {
    @Test
    fun `default profile explicitly covers every dimension and is deterministic`() {
        val first = WorldSignalCalibrationProfile.V1
        val second = WorldSignalCalibrationProfile.V1.copy(rules = WorldSignalCalibrationProfile.V1.rules.reversed())

        assertEquals(WorldSignalDimension.entries.toSet(), first.rules.map { it.dimension }.toSet())
        assertEquals(first.fingerprint(), second.fingerprint())
    }

    @Test
    fun `goal relevance saturation stays bounded and preserves ordering`() {
        val rule = WorldSignalCalibrationProfile.V1.rule(WorldSignalDimension.GOAL_RELEVANCE)

        val low = rule.normalize(0.25)
        val medium = rule.normalize(1.0)
        val high = rule.normalize(1000.0)

        assertTrue(low in 0.0..1.0)
        assertTrue(medium in 0.0..1.0)
        assertTrue(high in 0.0..1.0)
        assertTrue(low < medium)
        assertTrue(medium < high)
        assertTrue(high < 1.0)
    }

    @Test
    fun `calibration version and rule changes alter provenance fingerprint`() {
        val base = WorldSignalCalibrationProfile.V1
        val changed = base.copy(
            version = "world-signals-v2-test",
            rules = base.rules.map { rule ->
                if (rule.dimension == WorldSignalDimension.RELIABILITY) rule.copy(scale = 0.9) else rule
            },
        )

        assertNotEquals(base.fingerprint(), changed.fingerprint())
    }
}
