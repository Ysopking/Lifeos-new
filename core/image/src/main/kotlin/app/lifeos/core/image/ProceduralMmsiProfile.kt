package app.lifeos.core.image

/** Shared deterministic spectral/light profile for procedural scene CPU and Vulkan backends. */
class ProceduralMmsiProfile(
    val grid: SpectralGrid = SpectralGrid(startNm = 400, endNm = 700, stepNm = 10),
    val sunDirection: SolarVector = SolarVector(0.32, -0.24, 0.916515138991168).normalized(),
    val sunColorLinear: RgbSample = RgbSample(1.0, 0.97, 0.92),
    val skyAmbientLinear: RgbSample = RgbSample(0.18, 0.22, 0.30),
    val sunSolidAngleRad: Float = 0.00465f,
    val shadowFloor: Float = 0.05f,
) {
    val reconstructor = SpectralReconstructor(grid)
    val normalizedDaylight: IlluminantSpectrum = normalizedDaylight(grid)
    val coefficientProjection: SpectralReconstructor.CoefficientProjection =
        reconstructor.coefficientProjection(normalizedDaylight.curve)
    val rgbProjection: SpectralReconstructor.RgbProjection =
        reconstructor.rgbProjection(normalizedDaylight.curve)

    init {
        require(grid.bandCount == 31) { "Procedural MMSI mobile profile requires 31 spectral bands" }
        require(sunSolidAngleRad >= 0f)
        require(shadowFloor in 0f..1f)
    }

    companion object {
        fun normalizedDaylight(grid: SpectralGrid): IlluminantSpectrum {
            val values = DoubleArray(grid.bandCount) { index ->
                val wavelength = grid.wavelengthNm(index).toDouble()
                val blue = kotlin.math.exp(-0.5 * Math.pow((wavelength - 460.0) / 95.0, 2.0))
                val broad = kotlin.math.exp(-0.5 * Math.pow((wavelength - 560.0) / 180.0, 2.0))
                0.35 * blue + 0.9 * broad
            }
            val max = values.maxOrNull()?.coerceAtLeast(1e-9) ?: 1.0
            for (i in values.indices) values[i] /= max
            return IlluminantSpectrum(
                name = "lifeos-procedural-daylight-v1",
                curve = SpectralCurve(grid, values),
            )
        }
    }
}
