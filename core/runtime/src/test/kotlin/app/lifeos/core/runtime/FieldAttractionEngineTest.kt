package app.lifeos.core.runtime

import app.lifeos.core.model.ModuleIdentity
import app.lifeos.core.model.Photon
import app.lifeos.core.model.Provenance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FieldAttractionEngineTest {
    private fun module(
        id: String,
        mime: Set<String>,
        preferredTags: Set<String> = emptySet(),
        base: Double = 0.1,
    ) = CognitiveModule(
        descriptor = CognitiveModuleDescriptor(
            identity = ModuleIdentity(id, "1", "impl-$id"),
            acceptedMimeTypes = mime,
            preferredTags = preferredTags,
            baseAttraction = base,
        ),
        processor = CognitiveModuleProcessor { _, _ -> CognitiveModuleResult() },
    )

    @Test
    fun relevantModulesAreSelectedAndIrrelevantModulesStayBelowThreshold() {
        val photon = Photon(
            content = "invoice",
            mimeType = "text/plain",
            tags = setOf("finance"),
            provenance = Provenance("test", "user"),
        )
        val debt = module("debt", setOf("text/*"), preferredTags = setOf("finance"))
        val image = module("image", setOf("image/*"))

        val plan = FieldAttractionEngine().plan(photon, listOf(image, debt))

        assertEquals(listOf("debt"), plan.selected.map { it.module.descriptor.identity.moduleId })
        assertTrue(plan.rejected.any { it.module.descriptor.identity.moduleId == "image" })
    }

    @Test
    fun immediateSelfReentryIsBlockedByModuleLineageTag() {
        val module = module("legal", setOf("text/*"), base = 0.4)
        val photon = Photon(
            content = "derived",
            mimeType = "text/plain",
            tags = setOf("module:legal"),
            provenance = Provenance("test", "lifeos"),
        )

        val decision = FieldAttractionEngine().plan(photon, listOf(module)).decisions.single()

        assertFalse(decision.selected)
        assertTrue(decision.reasons.contains("blocked:immediate-self-reentry"))
    }

    @Test
    fun moduleBudgetUsesStableScoreThenIdentityOrdering() {
        val photon = Photon(
            content = "text",
            mimeType = "text/plain",
            provenance = Provenance("test", "user"),
        )
        val a = module("a", setOf("text/*"), base = 0.4)
        val b = module("b", setOf("text/*"), base = 0.4)
        val engine = FieldAttractionEngine(FieldAttractionConfig(maxModulesPerPhoton = 1))

        val first = engine.plan(photon, listOf(b, a)).selected.single().module.descriptor.identity.moduleId
        val second = engine.plan(photon, listOf(a, b)).selected.single().module.descriptor.identity.moduleId

        assertEquals("a", first)
        assertEquals(first, second)
    }
}
