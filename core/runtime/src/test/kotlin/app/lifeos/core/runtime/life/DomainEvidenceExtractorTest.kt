package app.lifeos.core.runtime.life

import app.lifeos.core.model.Photon
import app.lifeos.core.model.Provenance
import app.lifeos.core.runtime.FieldAttractionEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DomainEvidenceExtractorTest {
    @Test
    fun legalAndDebtFactsAreExtractedFromUntaggedEvidence() {
        val photon = Photon(
            content = "Mahnung: Forderung 842,50 EUR ist bis zum 18.09.2026 fällig.",
            provenance = Provenance("test", "user"),
        )

        val legal = DomainEvidenceExtractor.legal(photon)
        val debt = DomainEvidenceExtractor.debt(photon)

        assertTrue(legal.any { it.kind == DomainFactKind.CLAIM })
        assertTrue(legal.any { it.kind == DomainFactKind.DEADLINE && it.value == "18.09.2026" })
        assertTrue(debt.any { it.kind == DomainFactKind.AMOUNT && "842,50" in it.value })
    }

    @Test
    fun semanticAttractionFindsDomainWithoutPreexistingDomainTag() {
        val photon = Photon(
            content = "Die Rechnung über 120 EUR ist fällig und der Gläubiger hat gemahnt.",
            provenance = Provenance("test", "user"),
        )
        val plan = FieldAttractionEngine().plan(photon, DomainCognitionModules.all())
        val selected = plan.selected.map { it.module.descriptor.identity.moduleId }.toSet()

        assertTrue("domain.debt" in selected)
        assertTrue("domain.legal" in selected)
    }

    @Test
    fun extractorIsDeterministic() {
        val photon = Photon(
            content = "Cashflow Risiko: Kosten 5.000 EUR, Chance auf neuen Kunden.",
            provenance = Provenance("test", "user"),
        )

        assertEquals(DomainEvidenceExtractor.business(photon), DomainEvidenceExtractor.business(photon))
    }
}
