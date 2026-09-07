package app.lifeos.core.runtime

import kotlin.test.Test
import kotlin.test.assertEquals

class FieldRegistryTest {
    @Test fun staticRegistryCopiesInputList() {
        val input = mutableListOf<ForceField>()
        val registry = StaticFieldRegistry(input)

        input += ForceField { null }

        assertEquals(0, registry.activeFields().size)
    }
}
