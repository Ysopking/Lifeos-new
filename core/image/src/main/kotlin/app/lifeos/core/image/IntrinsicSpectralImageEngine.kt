package app.lifeos.core.image

import kotlin.math.exp
import kotlin.math.pow

class IntrinsicSpectralImageEngine(
    val grid: SpectralGrid = SpectralGrid(),
    private val solar: SolarEphemerisEngine = SolarEphemerisEngine(),
    private val atmosphere: AtmosphericRadiometryEngine = AtmosphericRadiometryEngine(grid),
    private val reconstructor: SpectralReconstructor = SpectralReconstructor(grid),
) {
    data class ReconstructionRequest(
        val srgb: RgbSample,
        val normal: SurfaceNormal,
        val latitudeDeg: Double,
        val longitudeDeg: Double,
        val instant: java.time.Instant,
        val sunVisibility: Double = 1.0,
        val atmosphereProfile: AtmosphericRadiometryEngine.Atmosphere = AtmosphericRadiometryEngine.Atmosphere(),
    ) {
        init { require(sunVisibility in 0.0..1.0) }
    }

    data class Reconstruction(
        val pixel: SpectralPixel,
        val solarPosition: SolarPosition,
        val incidentSpectrum: SpectralCurve,
    )

    fun reconstruct(request: ReconstructionRequest): Reconstruction {
        val solarPosition = solar.position(request.latitudeDeg, request.longitudeDeg, request.instant)
        val ground = atmosphere.illumination(solarPosition, request.atmosphereProfile)
        val incident = atmosphere.effectiveIncidentSpectrum(
            illumination = ground,
            normal = request.normal,
            sun = solarPosition.vector,
            visibility = request.sunVisibility,
        )
        val linear = RgbSample(
            ColorTransfer.srgbToLinear(request.srgb.r),
            ColorTransfer.srgbToLinear(request.srgb.g),
            ColorTransfer.srgbToLinear(request.srgb.b),
        )
        val reconstruction = reconstructor.reconstruct(linear, incident)
        return Reconstruction(
            pixel = SpectralPixel(
                reflectance = reconstruction.reflectance,
                normal = request.normal.normalized(),
                confidence = reconstruction.confidence,
                reconstructionError = reconstruction.reconstructionError,
            ),
            solarPosition = solarPosition,
            incidentSpectrum = incident,
        )
    }

    /** Reprojects intrinsic reflectance under a supplied spectrum without directional shadowing. */
    fun resynthesizeIsotropic(pixel: SpectralPixel, illuminant: IlluminantSpectrum): RgbSample {
        require(illuminant.curve.grid == grid)
        val linear = reconstructor.predictRgb(pixel.reflectance.values, illuminant.curve)
        return RgbSample(
            ColorTransfer.linearToSrgb(linear.r),
            ColorTransfer.linearToSrgb(linear.g),
            ColorTransfer.linearToSrgb(linear.b),
        )
    }

    fun normalizedDaylight(): IlluminantSpectrum {
        val temperatureK = 6504.0
        val c2 = 1.438776877e-2
        fun blackBody(lambdaNm: Double): Double {
            val wavelengthM = lambdaNm * 1e-9
            return 1.0 / (wavelengthM.pow(5.0) * (exp(c2 / (wavelengthM * temperatureK)) - 1.0))
        }
        val reference = blackBody(560.0)
        val values = DoubleArray(grid.bandCount) { index -> blackBody(grid.wavelengthNm(index).toDouble()) / reference }
        return IlluminantSpectrum(
            name = "normalized-daylight-6504K",
            curve = SpectralCurve(grid, values),
        )
    }
}
