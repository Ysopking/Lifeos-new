package app.lifeos.core.image

import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

data class RenderVec3(val x: Double, val y: Double, val z: Double) {
    init { require(x.isFinite() && y.isFinite() && z.isFinite()) }

    operator fun plus(other: RenderVec3) = RenderVec3(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: RenderVec3) = RenderVec3(x - other.x, y - other.y, z - other.z)
    operator fun times(scale: Double) = RenderVec3(x * scale, y * scale, z * scale)
    infix fun dot(other: RenderVec3): Double = x * other.x + y * other.y + z * other.z
    fun lengthSquared(): Double = this dot this
    fun length(): Double = sqrt(lengthSquared())
    fun normalized(): RenderVec3 {
        val length = length()
        require(length > 1e-12)
        return this * (1.0 / length)
    }
    fun asDirection(): SolarVector = SolarVector(x, y, z).normalized()
}

enum class MmsiLightSpace { WORLD_ENU, VIEW }

data class MmsiDirectionalLight(
    val id: String,
    val direction: SolarVector,
    val colorLinear: RgbSample,
    val intensity: Double,
    val angularRadiusRad: Double,
    val castsShadow: Boolean = true,
    val coordinateSpace: MmsiLightSpace = MmsiLightSpace.WORLD_ENU,
) {
    init {
        require(id.isNotBlank())
        require(intensity.isFinite() && intensity >= 0.0)
        require(angularRadiusRad.isFinite() && angularRadiusRad >= 0.0)
    }
}

enum class MmsiLocalLightKind { POINT, SPOT }

data class MmsiLocalLight(
    val id: String,
    val kind: MmsiLocalLightKind,
    val position: RenderVec3,
    /** For SPOT, points from the emitter into the illuminated scene. */
    val direction: SolarVector? = null,
    val colorLinear: RgbSample,
    val intensity: Double,
    val rangeMeters: Double,
    val innerConeDeg: Double = 25.0,
    val outerConeDeg: Double = 40.0,
    val castsShadow: Boolean = false,
    val coordinateSpace: MmsiLightSpace = MmsiLightSpace.WORLD_ENU,
) {
    init {
        require(id.isNotBlank())
        require(intensity.isFinite() && intensity >= 0.0)
        require(rangeMeters.isFinite() && rangeMeters > 0.0)
        require(innerConeDeg in 0.0..90.0)
        require(outerConeDeg in innerConeDeg..90.0)
        if (kind == MmsiLocalLightKind.SPOT) require(direction != null)
    }
}

/**
 * Deterministic camera-response policy. This is a rendering exposure model rather than a claim
 * that the normalized MMSI radiance units are calibrated camera watts/sr/m².
 */
data class CameraExposureSettings(
    val apertureFNumber: Double,
    val shutterSeconds: Double,
    val iso: Double,
    val referenceEv100: Double = 14.0,
) {
    init {
        require(apertureFNumber.isFinite() && apertureFNumber > 0.0)
        require(shutterSeconds.isFinite() && shutterSeconds > 0.0)
        require(iso.isFinite() && iso > 0.0)
        require(referenceEv100.isFinite())
    }

    val ev100: Double
        get() = log2((apertureFNumber * apertureFNumber) / shutterSeconds * (100.0 / iso))

    /** Gain relative to the deterministic normalized-radiance reference exposure. */
    val linearGain: Double
        get() = 2.0.pow(referenceEv100 - ev100).coerceIn(1.0 / 64.0, 16_384.0)

    private fun log2(value: Double): Double = ln(value) / ln(2.0)

    companion object {
        val DAY = CameraExposureSettings(8.0, 1.0 / 250.0, 100.0)
        val CIVIL_TWILIGHT = CameraExposureSettings(4.0, 1.0 / 125.0, 200.0)
        val NAUTICAL_TWILIGHT = CameraExposureSettings(2.8, 1.0 / 60.0, 800.0)
        val ASTRONOMICAL_TWILIGHT = CameraExposureSettings(2.0, 1.0 / 30.0, 1600.0)
        val NIGHT = CameraExposureSettings(1.8, 1.0 / 15.0, 3200.0)
    }
}
