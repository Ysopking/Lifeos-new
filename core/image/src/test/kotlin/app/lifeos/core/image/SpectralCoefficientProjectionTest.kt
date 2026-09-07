package app.lifeos.core.image

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpectralCoefficientProjectionTest {
    private val grid = SpectralGrid(startNm = 400, endNm = 700, stepNm = 10)

    @Test
    fun coefficientProjectionMatchesRegularizedSolver() {
        val reconstructor = SpectralReconstructor(grid)
        val incident = smoothIlluminant(centerNm = 550.0, widthNm = 170.0)
        val projection = reconstructor.coefficientProjection(incident)
        val rgb = RgbSample(0.34, 0.52, 0.27)

        val direct = reconstructor.reconstruct(rgb, incident).coefficients
        val projected = projection.project(rgb)

        assertEquals(direct.size, projection.componentCount)
        assertEquals(direct.size, projected.size)
        direct.indices.forEach { index ->
            assertTrue(abs(direct[index] - projected[index]) < 1e-9)
        }
    }

    @Test
    fun rgbProjectionMatchesExplicitBasisIntegration() {
        val basisLibrary = AnalyticSmoothReflectanceBasis()
        val reconstructor = SpectralReconstructor(grid, basisLibrary = basisLibrary)
        val target = smoothIlluminant(centerNm = 590.0, widthNm = 130.0)
        val projection = reconstructor.rgbProjection(target)
        val coefficients = doubleArrayOf(0.04, -0.03, 0.025, -0.02, 0.015, -0.01)

        val mean = basisLibrary.mean(grid)
        val basis = basisLibrary.components(grid)
        val reflectance = DoubleArray(grid.bandCount) { band ->
            var value = mean[band]
            for (component in coefficients.indices) {
                value += coefficients[component] * basis[component][band]
            }
            value.coerceIn(0.0, 1.0)
        }

        val expected = reconstructor.predictRgb(reflectance, target)
        val projected = projection.project(coefficients)

        assertTrue(abs(expected.r - projected.r) < 1e-9)
        assertTrue(abs(expected.g - projected.g) < 1e-9)
        assertTrue(abs(expected.b - projected.b) < 1e-9)
    }

    @Test
    fun mobileProjectionUsesSixCoefficientsInsteadOfThirtyOneBandsPerPixel() {
        val reconstructor = SpectralReconstructor(grid)
        val projection = reconstructor.coefficientProjection(smoothIlluminant(560.0, 160.0))

        assertEquals(31, grid.bandCount)
        assertEquals(6, projection.componentCount)
        assertTrue(projection.bias.all { it.isFinite() })
        assertTrue(projection.rgbToCoefficients.all { row -> row.all { it.isFinite() } })
    }

    private fun smoothIlluminant(centerNm: Double, widthNm: Double): SpectralCurve {
        val values = DoubleArray(grid.bandCount) { index ->
            val delta = (grid.wavelengthNm(index) - centerNm) / widthNm
            kotlin.math.exp(-0.5 * delta * delta)
        }
        return SpectralCurve(grid, values)
    }
}
