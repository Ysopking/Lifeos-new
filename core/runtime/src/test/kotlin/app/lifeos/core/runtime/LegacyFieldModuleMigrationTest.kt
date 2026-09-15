package app.lifeos.core.runtime

import app.lifeos.core.model.FieldInfluence
import app.lifeos.core.model.ModuleIdentity
import app.lifeos.core.model.Photon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LegacyFieldModuleMigrationTest {
    @Test fun migrationPreservesCanonicalIdentity() {
        val identity = ModuleIdentity("legacy", "1", "implementation", setOf("legacy-capability"))
        val module = LegacyFieldModuleMigration.migrate(LegacyFieldModuleSpec(identity, NoOpField))
        assertEquals(identity, module.descriptor.identity)
    }

    @Test fun duplicateImplementationsAreRejected() {
        val identity = ModuleIdentity("legacy", "1", "implementation", setOf("legacy-capability"))
        assertFailsWith<IllegalArgumentException> {
            LegacyFieldModuleMigration.migrateAll(listOf(
                LegacyFieldModuleSpec(identity, NoOpField),
                LegacyFieldModuleSpec(identity, NoOpField),
            ))
        }
    }

    private object NoOpField : ForceField {
        override suspend fun influence(photon: Photon): FieldInfluence? = null
    }
}
