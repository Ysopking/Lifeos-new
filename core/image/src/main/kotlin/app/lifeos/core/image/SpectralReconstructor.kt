package app.lifeos.core.image

import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Regularized tristimulus inversion into a smooth reflectance spectrum.
 * The RGB input is interpreted as linear-light sensor values in [0,1].
 */
class SpectralReconstructor(
    private val grid: SpectralGrid = SpectralGrid(),
    private val basisLibrary: SpectralBasisLibrary = AnalyticSmoothReflectanceBasis(),
    private val sensorResponse: ReferenceSensorResponse = ReferenceSensorResponse(),
    private val smoothnessGamma: Double = 0.03,
    private val ridge: Double = 1e-6,
) {
    data class Result(
        val reflectance: SpectralCurve,
        val reconstructionError: Double,
        val confidence: Double,
        val coefficients: DoubleArray,
    )

    init {
        require(smoothnessGamma >= 0.0)
        require(ridge > 0.0)
    }

    fun reconstruct(linearRgb: RgbSample, incident: SpectralCurve): Result {
        require(incident.grid == grid)
        require(incident.values.any { it > 0.0 }) { "Incident illumination must contain energy" }

        val mean = basisLibrary.mean(grid)
        val basis = basisLibrary.components(grid)
        require(mean.size == grid.bandCount)
        require(basis.isNotEmpty() && basis.all { it.size == grid.bandCount })

        val sensors = sensorResponse.curves(grid)
        val channels = doubleArrayOf(linearRgb.r, linearRgb.g, linearRgb.b)
        val a = Array(3) { DoubleArray(basis.size) }
        val base = DoubleArray(3)

        for (channel in 0..2) {
            val normalization = integrateProduct(sensors[channel], incident.values, null).coerceAtLeast(1e-12)
            base[channel] = integrateProduct(sensors[channel], incident.values, mean) / normalization
            for (component in basis.indices) {
                a[channel][component] = integrateProduct(sensors[channel], incident.values, basis[component]) / normalization
            }
        }

        val rhs = DoubleArray(3) { channels[it] - base[it] }
        val normal = Array(basis.size) { DoubleArray(basis.size) }
        val projected = DoubleArray(basis.size)

        for (i in basis.indices) {
            for (channel in 0..2) projected[i] += a[channel][i] * rhs[channel]
            for (j in basis.indices) {
                var value = 0.0
                for (channel in 0..2) value += a[channel][i] * a[channel][j]
                value += smoothnessGamma * curvatureInnerProduct(basis[i], basis[j])
                if (i == j) value += ridge
                normal[i][j] = value
            }
        }

        val coefficients = solveLinearSystem(normal, projected)
        val raw = DoubleArray(grid.bandCount) { band ->
            var value = mean[band]
            for (component in basis.indices) value += coefficients[component] * basis[component][band]
            value
        }
        var clipped = 0
        val reflectanceValues = DoubleArray(raw.size) { i ->
            val clamped = raw[i].coerceIn(0.0, 1.0)
            if (clamped != raw[i]) clipped++
            clamped
        }
        val predicted = predictRgb(reflectanceValues, incident, sensors)
        val error = sqrt(
            ((predicted.r - linearRgb.r) * (predicted.r - linearRgb.r) +
                (predicted.g - linearRgb.g) * (predicted.g - linearRgb.g) +
                (predicted.b - linearRgb.b) * (predicted.b - linearRgb.b)) / 3.0,
        )
        val clipFraction = clipped.toDouble() / raw.size
        val confidence = (exp(-5.0 * error) * (1.0 - 0.5 * clipFraction)).coerceIn(0.0, 1.0)

        return Result(
            reflectance = SpectralCurve(grid, reflectanceValues),
            reconstructionError = error,
            confidence = confidence,
            coefficients = coefficients,
        )
    }

    fun predictRgb(reflectance: DoubleArray, incident: SpectralCurve): RgbSample =
        predictRgb(reflectance, incident, sensorResponse.curves(grid))

    private fun predictRgb(
        reflectance: DoubleArray,
        incident: SpectralCurve,
        sensors: Array<DoubleArray>,
    ): RgbSample {
        val values = DoubleArray(3)
        for (channel in 0..2) {
            val normalization = integrateProduct(sensors[channel], incident.values, null).coerceAtLeast(1e-12)
            values[channel] = (integrateProduct(sensors[channel], incident.values, reflectance) / normalization).coerceIn(0.0, 1.0)
        }
        return RgbSample(values[0], values[1], values[2])
    }

    private fun integrateProduct(sensor: DoubleArray, illuminant: DoubleArray, reflectance: DoubleArray?): Double {
        var sum = 0.0
        for (i in 0 until grid.bandCount) {
            sum += sensor[i] * illuminant[i] * (reflectance?.get(i) ?: 1.0) * grid.stepNm
        }
        return sum
    }

    private fun curvatureInnerProduct(a: DoubleArray, b: DoubleArray): Double {
        if (a.size < 3) return 0.0
        var sum = 0.0
        for (i in 1 until a.lastIndex) {
            val d2a = a[i - 1] - 2.0 * a[i] + a[i + 1]
            val d2b = b[i - 1] - 2.0 * b[i] + b[i + 1]
            sum += d2a * d2b
        }
        return sum
    }

    private fun solveLinearSystem(matrix: Array<DoubleArray>, rhs: DoubleArray): DoubleArray {
        val n = rhs.size
        val augmented = Array(n) { row -> DoubleArray(n + 1) { column -> if (column < n) matrix[row][column] else rhs[row] } }
        for (pivot in 0 until n) {
            var best = pivot
            for (row in pivot + 1 until n) {
                if (kotlin.math.abs(augmented[row][pivot]) > kotlin.math.abs(augmented[best][pivot])) best = row
            }
            require(kotlin.math.abs(augmented[best][pivot]) > 1e-12) { "Spectral inversion matrix is singular" }
            if (best != pivot) {
                val tmp = augmented[pivot]
                augmented[pivot] = augmented[best]
                augmented[best] = tmp
            }
            val divisor = augmented[pivot][pivot]
            for (column in pivot until n + 1) augmented[pivot][column] /= divisor
            for (row in 0 until n) {
                if (row == pivot) continue
                val factor = augmented[row][pivot]
                if (factor == 0.0) continue
                for (column in pivot until n + 1) augmented[row][column] -= factor * augmented[pivot][column]
            }
        }
        return DoubleArray(n) { augmented[it][n] }
    }
}
