package app.lifeos.core.image

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MmsiImageEngineTest {
    @Test
    fun mobileProfileUses31Bands() {
        val engine = MmsiImageEngine()
        assertEquals(31, engine.grid.bandCount)
        assertEquals(400, engine.grid.startNm)
        assertEquals(700, engine.grid.endNm)
        assertEquals(10, engine.grid.stepNm)
    }

    @Test
    fun inverseRadiometryRemovesDirectionalLightAndSpecularEstimate() {
        val pass = InverseRadiometryPass()
        val result = pass.execute(
            observation = MaterialObservation(
                srgb = RgbSample(0.6, 0.5, 0.4),
                normal = SurfaceNormal(0.0, 0.0, 1.0),
                roughness = 0.5,
            ),
            context = DeLightingContext(
                sunDirection = SolarVector(0.0, 0.0, 1.0),
                sunIntensity = 0.8,
                ambientIntensity = 0.2,
            ),
        )

        assertTrue(result.linearDiffuseAlbedo.r in 0.0..1.0)
        assertTrue(result.linearDiffuseAlbedo.g in 0.0..1.0)
        assertTrue(result.linearDiffuseAlbedo.b in 0.0..1.0)
        assertTrue(result.removedSpecular >= 0.0)
        assertTrue(result.effectiveLight > 0.0)
    }

    @Test
    fun antiparallelSunAndViewRemainNumericallyStable() {
        val pass = InverseRadiometryPass()
        val result = pass.execute(
            observation = MaterialObservation(
                srgb = RgbSample(0.25, 0.30, 0.35),
                normal = SurfaceNormal(0.0, 0.0, 1.0),
                roughness = 0.7,
            ),
            context = DeLightingContext(
                sunDirection = SolarVector(0.0, 0.0, -1.0),
                sunIntensity = 0.0,
                ambientIntensity = 0.2,
                viewDirection = SolarVector(0.0, 0.0, 1.0),
            ),
        )
        assertTrue(result.linearDiffuseAlbedo.r in 0.0..1.0)
        assertTrue(result.removedSpecular.isFinite())
    }

    @Test
    fun fullMmsiPipelineIsDeterministicAndBounded() {
        val engine = MmsiImageEngine()
        val request = MmsiImageEngine.Request(
            observation = MaterialObservation(
                srgb = RgbSample(0.70, 0.35, 0.20),
                normal = SurfaceNormal(0.0, 0.0, 1.0),
                roughness = 0.42,
            ),
            latitudeDeg = 52.52,
            longitudeDeg = 13.405,
            instant = Instant.parse("2026-09-07T15:30:00Z"),
            waveField = WaveFieldPhase(
                phaseX = 0.13,
                phaseY = 0.07,
                amplitude = 0.02,
                spatialFrequency = 2.0,
            ),
            pixelX = 123,
            pixelY = 456,
        )

        val first = engine.process(request)
        val second = engine.process(request)

        assertEquals(first.output, second.output)
        assertEquals(first.spectral.reflectance.values.toList(), second.spectral.reflectance.values.toList())
        assertEquals(MmsiBackend.CPU_REFERENCE, first.backend)
        assertEquals(31, first.spectral.reflectance.values.size)
        assertTrue(first.spectral.reflectance.values.all { it in 0.0..1.0 })
        assertTrue(first.output.r in 0.0..1.0)
        assertTrue(first.output.g in 0.0..1.0)
        assertTrue(first.output.b in 0.0..1.0)
    }

    @Test
    fun performanceBudgetIsEvaluatedAsMeasuredConstraintNotPromise() {
        val budget = MmsiPerformanceBudget()
        assertTrue(
            MmsiExecutionMetrics(
                elapsedMs = 32.0,
                estimatedPeakRamBytes = 80L * 1024L * 1024L,
                backend = MmsiBackend.NATIVE_SIMD,
            ).satisfies(budget),
        )
        assertTrue(
            !MmsiExecutionMetrics(
                elapsedMs = 55.0,
                estimatedPeakRamBytes = 80L * 1024L * 1024L,
                backend = MmsiBackend.CPU_REFERENCE,
            ).satisfies(budget),
        )
    }
}
