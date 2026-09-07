package app.lifeos.core.image

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp

interface SpectralBasisLibrary {
    fun mean(grid: SpectralGrid): DoubleArray
    fun components(grid: SpectralGrid): List<DoubleArray>
}

/**
 * Built-in smooth basis used as an offline fallback when no measured reflectance PCA library is installed.
 * The interface intentionally allows a measured Vrhel/PCA basis to replace this without changing the solver.
 */
class AnalyticSmoothReflectanceBasis(
    private val componentCount: Int = 6,
) : SpectralBasisLibrary {
    init { require(componentCount in 3..12) }

    override fun mean(grid: SpectralGrid): DoubleArray = DoubleArray(grid.bandCount) { 0.45 }

    override fun components(grid: SpectralGrid): List<DoubleArray> = (1..componentCount).map { harmonic ->
        DoubleArray(grid.bandCount) { index ->
            val t = index.toDouble() / (grid.bandCount - 1).coerceAtLeast(1)
            cos(harmonic * PI * t) * 0.35
        }
    }
}

/** Three-channel reference sensor response. Production camera profiles can replace these curves. */
class ReferenceSensorResponse {
    fun curves(grid: SpectralGrid): Array<DoubleArray> = arrayOf(
        gaussian(grid, centerNm = 610.0, sigmaNm = 45.0),
        gaussian(grid, centerNm = 545.0, sigmaNm = 38.0),
        gaussian(grid, centerNm = 455.0, sigmaNm = 32.0),
    )

    private fun gaussian(grid: SpectralGrid, centerNm: Double, sigmaNm: Double): DoubleArray {
        val curve = DoubleArray(grid.bandCount) { index ->
            val d = (grid.wavelengthNm(index) - centerNm) / sigmaNm
            exp(-0.5 * d * d)
        }
        val max = curve.maxOrNull()?.coerceAtLeast(1e-12) ?: 1.0
        for (i in curve.indices) curve[i] /= max
        return curve
    }
}

object ColorTransfer {
    fun srgbToLinear(value: Double): Double {
        require(value in 0.0..1.0)
        return if (value <= 0.04045) value / 12.92 else Math.pow((value + 0.055) / 1.055, 2.4)
    }

    fun linearToSrgb(value: Double): Double {
        val v = value.coerceIn(0.0, 1.0)
        return if (v <= 0.0031308) 12.92 * v else 1.055 * Math.pow(v, 1.0 / 2.4) - 0.055
    }
}
