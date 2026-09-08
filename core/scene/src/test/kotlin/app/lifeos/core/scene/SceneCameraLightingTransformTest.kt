package app.lifeos.core.scene

import app.lifeos.core.image.ProceduralMmsiProfile
import app.lifeos.core.image.SolarVector
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

class SceneCameraLightingTransformTest {
    @Test
    fun `east north up light is transformed into same view basis as raster normals`() {
        val camera = SceneCamera(
            position = SceneVec3(0.0, -8.0, 2.0),
            target = SceneVec3(0.0, 0.0, 1.0),
        )
        val east = ProceduralMmsiProfile(sunDirection = SolarVector(1.0, 0.0, 0.0))
            .toMmsiViewSpace(camera)
            .sunDirection
        assertTrue(east.x > 0.99)
        assertTrue(abs(east.y) < 1e-9)
        assertTrue(abs(east.z) < 1e-9)
    }

    @Test
    fun `transformed astronomical direction remains normalized`() {
        val transformed = ProceduralMmsiProfile(
            sunDirection = SolarVector(0.32, -0.24, 0.916515138991168).normalized(),
        ).toMmsiViewSpace(SceneCamera()).sunDirection
        val length = sqrt(transformed.x * transformed.x + transformed.y * transformed.y + transformed.z * transformed.z)
        assertTrue(abs(length - 1.0) < 1e-12)
    }
}
