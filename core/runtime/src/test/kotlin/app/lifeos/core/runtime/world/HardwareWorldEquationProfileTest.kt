package app.lifeos.core.runtime.world

import app.lifeos.core.field.world.WorldNodeKind
import app.lifeos.core.field.world.WorldSignalDimension
import app.lifeos.core.runtime.resource.HardwareStateSnapshot
import app.lifeos.core.runtime.resource.HardwareThermalState
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HardwareWorldEquationProfileTest {
    @Test
    fun profileBindsMeasuredHardwareToResourceControllerReadiness() {
        val profile = HardwareWorldEquationProfile()
        val hardware = HardwareStateSnapshot(
            observedAt = Instant.parse("2026-09-11T12:00:00Z"),
            availableProcessors = 8,
            batteryFraction = 0.65,
            charging = false,
            thermalState = HardwareThermalState.NOMINAL,
            cpuLoadFraction = 0.20,
            availableMemoryBytes = 750,
            totalMemoryBytes = 1_000,
            availableStorageBytes = 850,
            totalStorageBytes = 1_000,
        )

        val request = profile.request(hardware)

        assertEquals(HardwareWorldEquationProfile.VERSION, request.equationVersion)
        assertEquals(2, request.inputs.size)
        assertEquals(2, request.interactions.size)
        assertTrue(request.inputs.any { it.target.kind == WorldNodeKind.HEALTH })
        val resourceTarget = request.inputs.single {
            it.target.key == HardwareWorldEquationProfile.RESOURCE_CONTROLLER_KEY
        }
        assertEquals(WorldNodeKind.CAPABILITY, resourceTarget.target.kind)
        assertTrue(request.interactions.all { interaction ->
            interaction.target == resourceTarget.target &&
                interaction.targetDimension == WorldSignalDimension.CAPABILITY_READINESS
        })
        request.interactions.forEach { interaction ->
            assertNotNull(profile.spec.coefficient(interaction.coefficientId))
        }
        assertEquals(hardware.observedAt, request.observedAt)
    }
}
