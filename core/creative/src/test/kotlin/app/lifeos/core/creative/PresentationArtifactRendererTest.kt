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
import kotlin.test.assertTrue

class PresentationArtifactRendererTest {
    @Test
    fun pptx_renderer_emits_real_deterministic_presentation_with_exact_claim_text() {
        val semantic = semanticPlan(
            "claim-a" to "Revenue increased by 12%.",
            "claim-b" to "The result is supported by audited records.",
        )
        val plan = PresentationArtifactPlan.create(
            semantic,
            listOf(
                PresentationSlidePlan.create(
                    ordinal = 1,
                    title = "Summary",
                    bullets = listOf(
                        PresentationBullet.fromClaim(semantic, "claim-a"),
                        PresentationBullet.fromClaim(semantic, "claim-b"),
                    ),
                )
            ),
        )
        val renderer = PresentationArtifactRenderer()

        val first = renderer.render(semantic, plan)
        val second = renderer.render(semantic, plan)

        assertContentEquals(first.payload, second.payload)
        assertEquals(first.contentSha256, second.contentSha256)
        assertEquals(first.fingerprint, second.fingerprint)
        assertEquals(
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            first.mediaType,
        )
        assertEquals(listOf("claim-a", "claim-b"), first.renderedClaimIds)
        assertFalse(first.factualAuthority)
        assertFalse(first.claimCreationAuthority)
        assertFalse(first.finalizationAuthority)
        assertFalse(first.publicationAuthority)

        val parts = unzip(first.payload)
        assertTrue("[Content_Types].xml" in parts)
        assertTrue("_rels/.rels" in parts)
        assertTrue("ppt/presentation.xml" in parts)
        assertTrue("ppt/_rels/presentation.xml.rels" in parts)
        assertTrue("ppt/slideMasters/slideMaster1.xml" in parts)
        assertTrue("ppt/slideLayouts/slideLayout1.xml" in parts)
        assertTrue("ppt/theme/theme1.xml" in parts)
        assertTrue("ppt/slides/slide1.xml" in parts)
        assertTrue("ppt/slides/_rels/slide1.xml.rels" in parts)
        assertTrue("docProps/core.xml" in parts)
        assertTrue("docProps/app.xml" in parts)

        val slide = parts.getValue("ppt/slides/slide1.xml").decodeToString()
        assertTrue(slide.contains("<a:t>Summary</a:t>"))
        assertTrue(slide.contains("<a:t>• Revenue increased by 12%.</a:t>"))
        assertTrue(slide.contains("<a:t>• The result is supported by audited records.</a:t>"))
    }

    @Test
    fun pptx_renderer_escapes_xml_and_keeps_title_non_authoritative() {
        val semantic = semanticPlan("claim-a" to "A & B < C")
        val bullet = PresentationBullet.fromClaim(semantic, "claim-a")
        val slidePlan = PresentationSlidePlan.create(
            ordinal = 1,
            title = "A & B",
            bullets = listOf(bullet),
        )
        val artifact = PresentationArtifactRenderer().render(
            semantic,
            PresentationArtifactPlan.create(semantic, listOf(slidePlan)),
        )

        val slide = unzip(artifact.payload)
            .getValue("ppt/slides/slide1.xml")
            .decodeToString()
        assertTrue(slide.contains("A &amp; B"))
        assertTrue(slide.contains("A &amp; B &lt; C"))
        assertFalse(slidePlan.factualAuthority)
        assertFalse(slidePlan.finalizationAuthority)
        assertFalse(bullet.factualAuthority)
    }

    @Test
    fun renderer_rejects_foreign_semantic_plan() {
        val source = semanticPlan("claim-a" to "A")
        val foreign = semanticPlan("claim-a" to "B")
        val plan = PresentationArtifactPlan.create(
            source,
            listOf(
                PresentationSlidePlan.create(
                    1,
                    "Summary",
                    listOf(PresentationBullet.fromClaim(source, "claim-a")),
                )
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            PresentationArtifactRenderer().render(foreign, plan)
        }
    }

    @Test
    fun slide_ordinals_must_be_contiguous() {
        val semantic = semanticPlan("claim-a" to "A")
        val bullet = PresentationBullet.fromClaim(semantic, "claim-a")

        assertFailsWith<IllegalArgumentException> {
            PresentationArtifactPlan.create(
                semantic,
                listOf(PresentationSlidePlan.create(2, "Late", listOf(bullet))),
            )
        }
    }

    @Test
    fun bullet_requires_exact_claim_evidence_and_resolved_claim() {
        val semantic = semanticPlan("claim-a" to "A")
        val bullet = PresentationBullet.fromClaim(semantic, "claim-a")
        assertEquals("A", bullet.text)
        assertTrue(bullet.evidenceStableKeys.isNotEmpty())

        assertFailsWith<IllegalArgumentException> {
            PresentationBullet.fromClaim(semantic, "missing")
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
                            PhotonId("b447-photon-" + (index + 1)),
                            (index + 1).toLong(),
                        )
                    ),
                    confidenceMicros = 900_000L,
                    canonicalContent = pair.second,
                )
            },
            sourceWorldRevision = 48L,
        )

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
