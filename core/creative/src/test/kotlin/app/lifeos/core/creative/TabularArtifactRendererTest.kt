package app.lifeos.core.creative

import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.SemanticArtifactClaim
import app.lifeos.core.model.SemanticArtifactKind
import app.lifeos.core.model.SemanticArtifactPlan
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TabularArtifactRendererTest {
    @Test
    fun csv_renderer_emits_exact_evidence_bound_values_deterministically() {
        val semantic = semanticPlan(
            "claim-name" to "Widget",
            "claim-qty" to "2",
            "claim-price" to "3.5",
        )
        val sheet = TabularSheetPlan.create(
            "Data",
            listOf(
                TabularCell.header(1, 1, "Name"),
                TabularCell.header(1, 2, "Quantity"),
                TabularCell.header(1, 3, "Price"),
                literalText(semantic, "claim-name", 2, 1),
                literalDecimal(semantic, "claim-qty", 2, 2),
                literalDecimal(semantic, "claim-price", 2, 3),
            ),
        )
        val plan = TabularArtifactPlan.create(semantic, listOf(sheet))
        val renderer = TabularArtifactRenderer()

        val first = renderer.render(semantic, plan, TabularOutputFormat.CSV)
        val second = renderer.render(semantic, plan, TabularOutputFormat.CSV)

        assertContentEquals(first.payload, second.payload)
        assertEquals(first.contentSha256, second.contentSha256)
        assertEquals(first.fingerprint, second.fingerprint)
        assertEquals("text/csv; charset=utf-8", first.mediaType)
        assertEquals(
            "Name,Quantity,Price\r\nWidget,2,3.5\r\n",
            first.payload.decodeToString(),
        )
        assertEquals(
            listOf("claim-name", "claim-price", "claim-qty"),
            first.renderedClaimIds,
        )
        assertFalse(first.factualAuthority)
        assertFalse(first.claimCreationAuthority)
        assertFalse(first.formulaExecutionAuthority)
        assertFalse(first.finalizationAuthority)
        assertFalse(first.publicationAuthority)
    }

    @Test
    fun xlsx_renderer_emits_real_workbook_with_safe_local_formula() {
        val semantic = semanticPlan(
            "claim-item" to "Widget",
            "claim-qty" to "2",
            "claim-price" to "3.5",
        )
        val sheet = TabularSheetPlan.create(
            "Metrics",
            listOf(
                TabularCell.header(1, 1, "Item"),
                TabularCell.header(1, 2, "Quantity"),
                TabularCell.header(1, 3, "Price"),
                TabularCell.header(1, 4, "Total"),
                literalText(semantic, "claim-item", 2, 1),
                literalDecimal(semantic, "claim-qty", 2, 2),
                literalDecimal(semantic, "claim-price", 2, 3),
                TabularCell.formula(2, 4, "=B2*C2"),
            ),
        )
        val plan = TabularArtifactPlan.create(semantic, listOf(sheet))

        val artifact = TabularArtifactRenderer().render(
            semantic,
            plan,
            TabularOutputFormat.XLSX,
        )

        val parts = unzip(artifact.payload)
        assertTrue("[Content_Types].xml" in parts)
        assertTrue("xl/workbook.xml" in parts)
        assertTrue("xl/styles.xml" in parts)
        assertTrue("xl/worksheets/sheet1.xml" in parts)
        assertTrue("xl/_rels/workbook.xml.rels" in parts)
        assertTrue("docProps/core.xml" in parts)
        assertTrue("docProps/app.xml" in parts)

        val workbook = parts.getValue("xl/workbook.xml").decodeToString()
        val sheetXml = parts.getValue("xl/worksheets/sheet1.xml").decodeToString()
        assertTrue(workbook.contains("name=\"Metrics\""))
        assertTrue(workbook.contains("fullCalcOnLoad=\"1\""))
        assertTrue(sheetXml.contains("<c r=\"A1\" t=\"inlineStr\" s=\"1\">"))
        assertTrue(sheetXml.contains("<c r=\"B2\"><v>2</v></c>"))
        assertTrue(sheetXml.contains("<f>B2*C2</f>"))
        assertEquals(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            artifact.mediaType,
        )
    }

    @Test
    fun renderer_rejects_formula_in_csv_and_formula_injection_literals() {
        val semantic = semanticPlan(
            "claim-value" to "2",
            "claim-text" to "=HYPERLINK",
        )

        val withFormula = TabularArtifactPlan.create(
            semantic,
            listOf(
                TabularSheetPlan.create(
                    "Data",
                    listOf(
                        literalDecimal(semantic, "claim-value", 1, 1),
                        TabularCell.formula(1, 2, "=A1*2"),
                    ),
                )
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            TabularArtifactRenderer().render(
                semantic,
                withFormula,
                TabularOutputFormat.CSV,
            )
        }

        val injectionLiteral = TabularArtifactPlan.create(
            semantic,
            listOf(
                TabularSheetPlan.create(
                    "Data",
                    listOf(
                        literalText(semantic, "claim-text", 1, 1),
                    ),
                )
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            TabularArtifactRenderer().render(
                semantic,
                injectionLiteral,
                TabularOutputFormat.CSV,
            )
        }
    }

    @Test
    fun renderer_rejects_unsafe_formula_external_or_function_syntax() {
        assertFailsWith<IllegalArgumentException> {
            TabularCell.formula(1, 1, "=SUM(A1:A2)")
        }
        assertFailsWith<IllegalArgumentException> {
            TabularCell.formula(1, 1, "=SHEET2!A1")
        }
        assertFailsWith<IllegalArgumentException> {
            TabularCell.formula(1, 1, "='C:\\\\temp\\\\x.xlsx'[1]Sheet1!A1")
        }
    }

    @Test
    fun renderer_rejects_formula_cycles_and_missing_sources() {
        val semantic = semanticPlan("claim-value" to "2")
        val cyclePlan = TabularArtifactPlan.create(
            semantic,
            listOf(
                TabularSheetPlan.create(
                    "Cycle",
                    listOf(
                        literalDecimal(semantic, "claim-value", 1, 1),
                        TabularCell.formula(1, 2, "=C1+A1"),
                        TabularCell.formula(1, 3, "=B1+A1"),
                    ),
                )
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            TabularArtifactRenderer().render(
                semantic,
                cyclePlan,
                TabularOutputFormat.XLSX,
            )
        }

        val missingPlan = TabularArtifactPlan.create(
            semantic,
            listOf(
                TabularSheetPlan.create(
                    "Missing",
                    listOf(
                        TabularCell.formula(1, 1, "=B1*2"),
                    ),
                )
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            TabularArtifactRenderer().render(
                semantic,
                missingPlan,
                TabularOutputFormat.XLSX,
            )
        }
    }

    @Test
    fun renderer_rejects_literal_that_does_not_equal_claim_content() {
        val semantic = semanticPlan("claim-value" to "2")
        val claim = semantic.claims.single()
        val plan = TabularArtifactPlan.create(
            semantic,
            listOf(
                TabularSheetPlan.create(
                    "Data",
                    listOf(
                        TabularCell.decimal(
                            row = 1,
                            column = 1,
                            value = "3",
                            claimId = claim.claimId,
                            evidenceStableKeys = claim.evidence.map { it.stableKey },
                        )
                    ),
                )
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            TabularArtifactRenderer().render(
                semantic,
                plan,
                TabularOutputFormat.XLSX,
            )
        }
    }

    private fun semanticPlan(
        vararg values: Pair<String, String>,
    ): SemanticArtifactPlan =
        SemanticArtifactPlan(
            kind = SemanticArtifactKind.TEXT,
            claims = values.mapIndexed { index, pair ->
                SemanticArtifactClaim(
                    claimId = pair.first,
                    evidence = setOf(
                        PhotonRevisionRef(
                            PhotonId("b446-photon-" + (index + 1)),
                            (index + 1).toLong(),
                        )
                    ),
                    confidenceMicros = 900_000L,
                    canonicalContent = pair.second,
                )
            },
            sourceWorldRevision = 47L,
        )

    private fun literalText(
        semantic: SemanticArtifactPlan,
        claimId: String,
        row: Int,
        column: Int,
    ): TabularCell {
        val claim = assertNotNull(semantic.claim(claimId))
        return TabularCell.text(
            row = row,
            column = column,
            value = claim.canonicalContent,
            claimId = claim.claimId,
            evidenceStableKeys = claim.evidence.map { it.stableKey },
        )
    }

    private fun literalDecimal(
        semantic: SemanticArtifactPlan,
        claimId: String,
        row: Int,
        column: Int,
    ): TabularCell {
        val claim = assertNotNull(semantic.claim(claimId))
        return TabularCell.decimal(
            row = row,
            column = column,
            value = claim.canonicalContent,
            claimId = claim.claimId,
            evidenceStableKeys = claim.evidence.map { it.stableKey },
        )
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    result[entry.name] = zip.readBytes()
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return result
    }
}
