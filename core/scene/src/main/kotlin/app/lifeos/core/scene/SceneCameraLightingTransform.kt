package app.lifeos.core.scene

import app.lifeos.core.image.ProceduralMmsiProfile
import app.lifeos.core.image.SolarVector
import kotlin.math.sqrt

/**
 * MMSI raster normals are view-space. Astronomical sun vectors are ENU/world-space where
 * +X=east, +Y=north and +Z=up, so the light direction must cross the same camera basis.
 */
fun ProceduralMmsiProfile.toMmsiViewSpace(camera: SceneCamera): ProceduralMmsiProfile {
    val origin = CameraVec.from(camera.position)
    val target = CameraVec.from(camera.target)
    val forward = (target - origin).normalized()
    val worldUp = CameraVec(0.0, 0.0, 1.0)
    var right = forward.cross(worldUp)
    if (right.lengthSquared() < EPSILON) right = CameraVec(1.0, 0.0, 0.0)
    right = right.normalized()
    val up = right.cross(forward).normalized()
    val worldLight = CameraVec(sunDirection.x, sunDirection.y, sunDirection.z)
    val viewLight = SolarVector(
        x = worldLight dot right,
        y = worldLight dot up,
        z = worldLight dot (forward * -1.0),
    ).normalized()
    return ProceduralMmsiProfile(
        grid = grid,
        sunDirection = viewLight,
        sunColorLinear = sunColorLinear,
        skyAmbientLinear = skyAmbientLinear,
        sunSolidAngleRad = sunSolidAngleRad,
        shadowFloor = shadowFloor,
    )
}

private data class CameraVec(val x: Double, val y: Double, val z: Double) {
    operator fun minus(other: CameraVec) = CameraVec(x - other.x, y - other.y, z - other.z)
    operator fun times(scale: Double) = CameraVec(x * scale, y * scale, z * scale)
    infix fun dot(other: CameraVec): Double = x * other.x + y * other.y + z * other.z
    fun cross(other: CameraVec) = CameraVec(
        y * other.z - z * other.y,
        z * other.x - x * other.z,
        x * other.y - y * other.x,
    )
    fun lengthSquared(): Double = this dot this
    fun normalized(): CameraVec {
        val length = sqrt(lengthSquared())
        require(length > EPSILON)
        return this * (1.0 / length)
    }

    companion object {
        fun from(value: SceneVec3) = CameraVec(value.x, value.y, value.z)
    }
}

private const val EPSILON = 1e-8
