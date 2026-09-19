package app.lifeos.core.runtime.world

import app.lifeos.core.field.world.WorldEquationSpec
import kotlin.test.Test
import kotlin.test.assertEquals

class WorldEquationSpecCodecTest {
    @Test
    fun roundTripPreservesArtifactPhysicsAndSchemaFingerprints() {
        val spec = CognitiveWorldEquationProfile().spec
        val decoded = WorldEquationSpecCodec.decode(WorldEquationSpecCodec.encode(spec))

        assertEquals(spec, decoded)
        assertEquals(spec.fingerprint(), decoded.fingerprint())
        assertEquals(spec.physicsFingerprint(), decoded.physicsFingerprint())
        assertEquals(spec.schemaFingerprint(), decoded.schemaFingerprint())
    }

    @Test
    fun coefficientOrderDoesNotChangeCanonicalEncoding() {
        val spec = CognitiveWorldEquationProfile().spec
        val reordered = WorldEquationSpec(
            version = spec.version,
            coefficients = spec.coefficients.reversed(),
        )

        assertEquals(
            WorldEquationSpecCodec.encode(spec).toList(),
            WorldEquationSpecCodec.encode(reordered).toList(),
        )
    }
}
