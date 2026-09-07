package app.lifeos.core.image

import java.time.Instant

/**
 * Complete model-free MMSI reference pipeline.
 * Production SIMD/Vulkan backends must preserve this deterministic contract.
 */
class MmsiImageEngine(
    val grid: SpectralGrid = SpectralGrid(startNm = 400, endNm = 700, stepNm = 10),
    private val solarEngine: SolarEphemerisEngine = SolarEphemerisEngine(),
    private val atmosphereEngine: AtmosphericRadiometryEngine = AtmosphericRadiometryEngine(grid),
    private val reconstructor: SpectralReconstructor = SpectralReconstructor(grid),
    private val inverseRadiometry: InverseRadiometryPass = InverseRadiometryPass(),
) {
    private val expansion = HyperspectralExpansionPass(reconstructor)
    private val forward = ForwardSynthesisPass(reconstructor)

    init { require(grid.bandCount == 31) { "MMSI mobile profile requires 31 spectral bands" } }

    data class Request(
        val observation: MaterialObservation,
        val latitudeDeg: Double,
        val longitudeDeg: Double,
        val instant: Instant,
        val newSunDirection: SolarVector? = null,
        val newSunColorLinear: RgbSample = RgbSample(1.0, 0.97, 0.92),
        val skyAmbientLinear: RgbSample = RgbSample(0.18, 0.22, 0.30),
        val shadowVisibility: Double = 1.0,
        val ambientOcclusion: Double = 1.0,
        val waveField: WaveFieldPhase = WaveFieldPhase(0.0, 0.0),
        val pixelX: Int = 0,
        val pixelY: Int = 0,
    )

    fun process(request: Request): MmsiPipelineResult {
        val solarPosition = solarEngine.position(request.latitudeDeg, request.longitudeDeg, request.instant)
        val ground = atmosphereEngine.illumination(solarPosition)
        val incident = atmosphereEngine.effectiveIncidentSpectrum(
            illumination = ground,
            normal = request.observation.normal,
            sun = solarPosition.vector,
            visibility = request.observation.sunVisibility,
        )
        val directIntensity = ground.direct.values.average().coerceAtLeast(0.0)
        val ambientIntensity = ground.diffuse.values.average().coerceAtLeast(1e-4)
        val intrinsic = inverseRadiometry.execute(
            observation = request.observation,
            context = DeLightingContext(
                sunDirection = solarPosition.vector,
                sunIntensity = directIntensity,
                ambientIntensity = ambientIntensity,
            ),
        )
        val spectral = expansion.execute(
            material = intrinsic,
            incident = incident,
            pixelX = request.pixelX,
            pixelY = request.pixelY,
            waveField = request.waveField,
        )
        val norm = normalizedDaylight()
        val output = forward.execute(
            pixel = spectral,
            illuminant = norm,
            context = ForwardSynthesisContext(
                sunDirection = request.newSunDirection ?: solarPosition.vector,
                sunColorLinear = request.newSunColorLinear,
                skyAmbientLinear = request.skyAmbientLinear,
                roughness = request.observation.roughness,
                shadowVisibility = request.shadowVisibility,
                ambientOcclusion = request.ambientOcclusion,
            ),
        )
        return MmsiPipelineResult(
            intrinsic = intrinsic,
            spectral = spectral,
            output = output,
            solarPosition = solarPosition,
            backend = MmsiBackend.CPU_REFERENCE,
        )
    }

    fun normalizedDaylight(): IlluminantSpectrum {
        val values = DoubleArray(grid.bandCount) { index ->
            val wavelength = grid.wavelengthNm(index).toDouble()
            // Smooth daylight approximation centered in the photopic range.
            val blueDelta = (wavelength - 460.0) / 95.0
            val broadDelta = (wavelength - 560.0) / 180.0
            val blueShoulder = kotlin.math.exp(-0.5 * Math.pow(blueDelta, 2.0))
            val broadDaylight = kotlin.math.exp(-0.5 * Math.pow(broadDelta, 2.0))
            0.35 * blueShoulder + 0.9 * broadDaylight
        }
        val max = values.maxOrNull()?.coerceAtLeast(1e-9) ?: 1.0
        for (i in values.indices) values[i] /= max
        return IlluminantSpectrum("mmsi-normalized-daylight", SpectralCurve(grid, values))
    }
}
