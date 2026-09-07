package app.lifeos.core.image

import java.time.Instant
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IntrinsicSpectralImageEngineTest {
    @Test
    fun solarVectorIsNormalizedAndDayNightGeometryChanges() {
        val engine = SolarEphemerisEngine()
        val midday = engine.position(52.52, 13.405, Instant.parse("2026-09-07T12:00:00Z"))
        val midnight = engine.position(52.52, 13.405, Instant.parse("2026-09-07T00:00:00Z"))

        val length = sqrt(
            midday.vector.x * midday.vector.x +
                midday.vector.y * midday.vector.y +
                midday.vector.z * midday.vector.z,
        )
        assertTrue(abs(length - 1.0) < 1e-9)
        assertTrue(midday.elevationDeg > midnight.elevationDeg)
        assertTrue(midday.azimuthDeg in 0.0..360.0)
    }

    @Test
    fun atmosphericAirMassGrowsTowardHorizon() {
        val engine = AtmosphericRadiometryEngine()
        val overhead = engine.relativeAirMass(5.0)
        val lowSun = engine.relativeAirMass(80.0)
        assertTrue(overhead >= 1.0)
        assertTrue(lowSun > overhead)
    }

    @Test
    fun basisMeanRoundTripsThroughTristimulusInversion() {
        val grid = SpectralGrid()
        val reconstructor = SpectralReconstructor(grid)
        val incident = SpectralCurve(grid, DoubleArray(grid.bandCount) { 1.0 })
        val expectedReflectance = DoubleArray(grid.bandCount) { 0.45 }
        val measured = reconstructor.predictRgb(expectedReflectance, incident)
        val reconstructed = reconstructor.reconstruct(measured, incident)
        val predicted = reconstructor.predictRgb(reconstructed.reflectance.values, incident)

        assertTrue(reconstructed.reconstructionError < 1e-6)
        assertTrue(reconstructed.confidence > 0.99)
        assertEquals(measured.r, predicted.r, 1e-6)
        assertEquals(measured.g, predicted.g, 1e-6)
        assertEquals(measured.b, predicted.b, 1e-6)
    }

    @Test
    fun fullOfflineReconstructionProducesBoundedSpectrumAndResynthesis() {
        val engine = IntrinsicSpectralImageEngine()
        val reconstruction = engine.reconstruct(
            IntrinsicSpectralImageEngine.ReconstructionRequest(
                srgb = RgbSample(0.72, 0.31, 0.18),
                normal = SurfaceNormal(0.0, 0.0, 1.0),
                latitudeDeg = 52.52,
                longitudeDeg = 13.405,
                instant = Instant.parse("2026-09-07T15:30:00Z"),
            ),
        )

        assertEquals(81, reconstruction.pixel.reflectance.values.size)
        assertTrue(reconstruction.pixel.reflectance.values.all { it in 0.0..1.0 })
        assertTrue(reconstruction.pixel.confidence in 0.0..1.0)
        val rendered = engine.resynthesizeIsotropic(reconstruction.pixel, engine.normalizedDaylight())
        assertTrue(rendered.r in 0.0..1.0)
        assertTrue(rendered.g in 0.0..1.0)
        assertTrue(rendered.b in 0.0..1.0)
    }
}
