package app.lifeos.core.runtime

import app.lifeos.core.model.ModuleIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LegacyFieldModuleMigrationTest {
    @Test fun migrationPreservesCanonicalIdentity() {
        val identity = ModuleIdentity("legacy", "1", setOf("legacy-capability"), "implementation")
        val module = LegacyFieldModuleMigration.migrate(LegacyFieldModuleSpec(identity, NoOpField))
        assertEquals(identity, module.descriptor.identity)
    }

    @Test fun duplicateImplementationsAreRejected() {
        val identity = ModuleIdentity("legacy", "1", setOf("legacy-capability"), "implementation")
        assertFailsWith<IllegalArgumentException> {
            LegacyFieldModuleMigration.migrateAll(listOf(
                LegacyFieldModuleSpec(identity, NoOpField),
                LegacyFieldModuleSpec(identity, NoOpField),
            ))
        }
    }

    private object NoOpField : ForceField {
        override val id: String = "noop"
        override suspend fun influence(context: FieldContext): FieldInfluence = FieldInfluence(fieldId = id)
    }
}
