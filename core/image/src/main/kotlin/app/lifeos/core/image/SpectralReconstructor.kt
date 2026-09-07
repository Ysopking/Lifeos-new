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

    /** Affine RGB -> regularized spectral-basis coefficient transform for one incident spectrum. */
    data class CoefficientProjection(
        val bias: DoubleArray,
        val rgbToCoefficients: Array<DoubleArray>,
    ) {
        val componentCount: Int get() = bias.size

        init {
            require(bias.isNotEmpty())
            require(rgbToCoefficients.size == bias.size)
            require(rgbToCoefficients.all { it.size == 3 })
            require(bias.all { it.isFinite() })
            require(rgbToCoefficients.all { row -> row.all { it.isFinite() } })
        }

        fun project(rgb: RgbSample): DoubleArray = DoubleArray(componentCount) { component ->
            bias[component] +
                rgbToCoefficients[component][0] * rgb.r +
                rgbToCoefficients[component][1] * rgb.g +
                rgbToCoefficients[component][2] * rgb.b
        }
    }

    /** Affine spectral-basis coefficient -> linear RGB transform for one target illuminant. */
    data class RgbProjection(
        val meanRgb: DoubleArray,
        val coefficientsToRgb: Array<DoubleArray>,
    ) {
        val componentCount: Int get() = coefficientsToRgb.firstOrNull()?.size ?: 0

        init {
            require(meanRgb.size == 3)
            require(coefficientsToRgb.size == 3)
            require(componentCount > 0)
            require(coefficientsToRgb.all { it.size == componentCount })
            require(meanRgb.all { it.isFinite() })
            require(coefficientsToRgb.all { row -> row.all { it.isFinite() } })
        }

        fun project(coefficients: DoubleArray): RgbSample {
            require(coefficients.size == componentCount)
            val rgb = DoubleArray(3) { channel ->
                var value = meanRgb[channel]
                for (component in coefficients.indices) {
                    value += coefficientsToRgb[channel][component] * coefficients[component]
                }
                value.coerceIn(0.0, 1.0)
            }
            return RgbSample(rgb[0], rgb[1], rgb[2])
        }
    }

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
        val a = responseMatrix(incident, mean, basis, sensors)
        val base = meanResponse(incident, mean, sensors)
        val rhs = DoubleArray(3) { channels[it] - base[it] }
        val normal = regularizedNormalMatrix(a, basis)
        val projected = DoubleArray(basis.size)

        for (i in basis.indices) {
            for (channel in 0..2) projected[i] += a[channel][i] * rhs[channel]
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

    /**
     * Precomputes the exact affine coefficient transform of this regularized solver for a fixed
     * incident spectrum. Four solver evaluations are enough because the inverse system is linear
     * in RGB after the regularization matrix has been fixed.
     */
    fun coefficientProjection(incident: SpectralCurve): CoefficientProjection {
        require(incident.grid == grid)
        val zero = reconstruct(RgbSample(0.0, 0.0, 0.0), incident).coefficients
        val unitR = reconstruct(RgbSample(1.0, 0.0, 0.0), incident).coefficients
        val unitG = reconstruct(RgbSample(0.0, 1.0, 0.0), incident).coefficients
        val unitB = reconstruct(RgbSample(0.0, 0.0, 1.0), incident).coefficients
        val matrix = Array(zero.size) { component ->
            doubleArrayOf(
                unitR[component] - zero[component],
                unitG[component] - zero[component],
                unitB[component] - zero[component],
            )
        }
        return CoefficientProjection(zero.copyOf(), matrix)
    }

    /**
     * Precomputes the spectral-basis -> linear RGB transform for a target illuminant. This lets
     * the GPU carry compact basis coefficients instead of all sampled wavelength bands.
     */
    fun rgbProjection(illuminant: SpectralCurve): RgbProjection {
        require(illuminant.grid == grid)
        require(illuminant.values.any { it > 0.0 }) { "Illuminant must contain energy" }
        val mean = basisLibrary.mean(grid)
        val basis = basisLibrary.components(grid)
        val sensors = sensorResponse.curves(grid)
        val base = meanResponse(illuminant, mean, sensors)
        val matrix = responseMatrix(illuminant, mean, basis, sensors)
        return RgbProjection(
            meanRgb = base,
            coefficientsToRgb = Array(3) { channel -> matrix[channel].copyOf() },
        )
    }

    fun predictRgb(reflectance: DoubleArray, incident: SpectralCurve): RgbSample =
        predictRgb(reflectance, incident, sensorResponse.curves(grid))

    private fun responseMatrix(
        incident: SpectralCurve,
        mean: DoubleArray,
        basis: List<DoubleArray>,
        sensors: Array<DoubleArray>,
    ): Array<DoubleArray> {
        require(mean.size == grid.bandCount)
        val matrix = Array(3) { DoubleArray(basis.size) }
        for (channel in 0..2) {
            val normalization = integrateProduct(sensors[channel], incident.values, null).coerceAtLeast(1e-12)
            for (component in basis.indices) {
                matrix[channel][component] = integrateProduct(
                    sensors[channel],
                    incident.values,
                    basis[component],
                ) / normalization
            }
        }
        return matrix
    }

    private fun meanResponse(
        incident: SpectralCurve,
        mean: DoubleArray,
        sensors: Array<DoubleArray>,
    ): DoubleArray = DoubleArray(3) { channel ->
        val normalization = integrateProduct(sensors[channel], incident.values, null).coerceAtLeast(1e-12)
        integrateProduct(sensors[channel], incident.values, mean) / normalization
    }

    private fun regularizedNormalMatrix(a: Array<DoubleArray>, basis: List<DoubleArray>): Array<DoubleArray> =
        Array(basis.size) { i ->
            DoubleArray(basis.size) { j ->
                var value = 0.0
                for (channel in 0..2) value += a[channel][i] * a[channel][j]
                value += smoothnessGamma * curvatureInnerProduct(basis[i], basis[j])
                if (i == j) value += ridge
                value
            }
        }

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
