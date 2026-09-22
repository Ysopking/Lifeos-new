package app.lifeos.core.creative

import app.lifeos.core.model.SemanticArtifactPlan
import app.lifeos.core.model.StableCognitiveIds
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class PresentationBullet private constructor(
    val claimId: String,
    val text: String,
    val evidenceStableKeys: List<String>,
    val fingerprint: String,
) {
    init {
        require(claimId.isNotBlank())
        require(text.isNotBlank())
        require(text.length <= MAX_PRESENTATION_TEXT_CHARS_B447)
        require(evidenceStableKeys.isNotEmpty())
        require(evidenceStableKeys == evidenceStableKeys.distinct().sorted())
        require(evidenceStableKeys.none(String::isBlank))
        require(
            fingerprint == presentationBulletFingerprint(
                claimId,
                text,
                evidenceStableKeys,
            )
        )
    }

    val factualAuthority: Boolean get() = false
    val claimCreationAuthority: Boolean get() = false
    val citationAuthority: Boolean get() = false

    companion object {
        fun fromClaim(
            semanticPlan: SemanticArtifactPlan,
            claimId: String,
        ): PresentationBullet {
            val claim = requireNotNull(semanticPlan.claim(claimId)) {
                "B447 presentation bullet requires an exact semantic claim"
            }
            require(claim.canonicalContent.isNotBlank()) {
                "B447 presentation bullet requires non-empty canonical claim content"
            }
            require(claim.evidence.isNotEmpty()) {
                "B447 presentation bullet requires exact claim evidence"
            }
            val evidence = claim.evidence
                .map { it.stableKey }
                .distinct()
                .sorted()
            return PresentationBullet(
                claimId = claim.claimId,
                text = claim.canonicalContent,
                evidenceStableKeys = evidence,
                fingerprint = presentationBulletFingerprint(
                    claim.claimId,
                    claim.canonicalContent,
                    evidence,
                ),
            )
        }
    }
}

data class PresentationSlidePlan private constructor(
    val ordinal: Int,
    val title: String,
    val bullets: List<PresentationBullet>,
    val fingerprint: String,
) {
    init {
        require(ordinal > 0)
        require(title.isNotBlank())
        require(title.length <= MAX_PRESENTATION_TITLE_CHARS_B447)
        require(title.none { it == '\u0000' || it == '\r' || it == '\n' })
        require(bullets.isNotEmpty())
        require(bullets.size <= MAX_BULLETS_PER_SLIDE_B447)
        require(
            fingerprint == presentationSlideFingerprint(
                ordinal,
                title,
                bullets,
            )
        )
    }

    val factualAuthority: Boolean get() = false
    val layoutAuthority: Boolean get() = false
    val finalizationAuthority: Boolean get() = false

    companion object {
        fun create(
            ordinal: Int,
            title: String,
            bullets: Collection<PresentationBullet>,
        ): PresentationSlidePlan {
            val canonicalTitle = title.trim()
            val canonicalBullets = bullets.toList()
            require(canonicalBullets.isNotEmpty())
            return PresentationSlidePlan(
                ordinal = ordinal,
                title = canonicalTitle,
                bullets = canonicalBullets,
                fingerprint = presentationSlideFingerprint(
                    ordinal,
                    canonicalTitle,
                    canonicalBullets,
                ),
            )
        }
    }
}

data class PresentationArtifactPlan private constructor(
    val semanticPlanFingerprint: String,
    val sourceWorldRevision: Long,
    val slides: List<PresentationSlidePlan>,
    val fingerprint: String,
) {
    init {
        require(semanticPlanFingerprint.matches(SHA_256_B447))
        require(sourceWorldRevision >= 0L)
        require(slides.isNotEmpty())
        require(slides.size <= MAX_PRESENTATION_SLIDES_B447)
        require(slides.map { it.ordinal } == (1..slides.size).toList())
        require(
            fingerprint == presentationPlanFingerprint(
                semanticPlanFingerprint,
                sourceWorldRevision,
                slides,
            )
        )
    }

    val factualAuthority: Boolean get() = false
    val claimCreationAuthority: Boolean get() = false
    val finalizationAuthority: Boolean get() = false
    val publicationAuthority: Boolean get() = false

    companion object {
        fun create(
            semanticPlan: SemanticArtifactPlan,
            slides: Collection<PresentationSlidePlan>,
        ): PresentationArtifactPlan {
            val ordered = slides.sortedBy { it.ordinal }
            require(ordered.map { it.ordinal } == (1..ordered.size).toList()) {
                "B447 slide ordinals must be contiguous from one"
            }
            return PresentationArtifactPlan(
                semanticPlanFingerprint = semanticPlan.fingerprint,
                sourceWorldRevision = semanticPlan.sourceWorldRevision,
                slides = ordered,
                fingerprint = presentationPlanFingerprint(
                    semanticPlan.fingerprint,
                    semanticPlan.sourceWorldRevision,
                    ordered,
                ),
            )
        }
    }
}

class PresentationArtifact internal constructor(
    val semanticPlanFingerprint: String,
    val presentationPlanFingerprint: String,
    val renderedClaimIds: List<String>,
    val evidenceStableKeys: List<String>,
    val slideFingerprints: List<String>,
    payload: ByteArray,
    val contentSha256: String,
    val fingerprint: String,
) {
    private val payloadBytes = payload.copyOf()

    init {
        require(semanticPlanFingerprint.matches(SHA_256_B447))
        require(presentationPlanFingerprint.matches(SHA_256_B447))
        require(renderedClaimIds.isNotEmpty())
        require(renderedClaimIds == renderedClaimIds.distinct().sorted())
        require(evidenceStableKeys.isNotEmpty())
        require(evidenceStableKeys == evidenceStableKeys.distinct().sorted())
        require(slideFingerprints.isNotEmpty())
        require(slideFingerprints == slideFingerprints.distinct())
        require(payloadBytes.isNotEmpty())
        require(contentSha256 == sha256B447(payloadBytes))
        require(
            fingerprint == presentationArtifactFingerprint(
                semanticPlanFingerprint,
                presentationPlanFingerprint,
                renderedClaimIds,
                evidenceStableKeys,
                slideFingerprints,
                contentSha256,
            )
        )
    }

    val mediaType: String
        get() = "application/vnd.openxmlformats-officedocument.presentationml.presentation"

    val payload: ByteArray
        get() = payloadBytes.copyOf()

    val factualAuthority: Boolean get() = false
    val claimCreationAuthority: Boolean get() = false
    val finalizationAuthority: Boolean get() = false
    val publicationAuthority: Boolean get() = false
}

/**
 * B447 renders an evidence-bound presentation plan as a real deterministic PPTX package.
 *
 * Slide titles are presentation metadata. Every factual bullet must be the exact canonical content
 * of one resolved SemanticArtifactClaim and carry the exact evidence stable keys from that claim.
 * Rendering cannot create claims, select new evidence, finalize, publish, or widen Owner Policy.
 */
class PresentationArtifactRenderer {
    fun render(
        semanticPlan: SemanticArtifactPlan,
        plan: PresentationArtifactPlan,
    ): PresentationArtifact {
        require(plan.semanticPlanFingerprint == semanticPlan.fingerprint) {
            "B447 presentation plan does not match semantic plan"
        }
        require(plan.sourceWorldRevision == semanticPlan.sourceWorldRevision)

        validateEvidenceClosureB447(semanticPlan, plan)

        val payload = renderPptxB447(plan)
        val contentSha = sha256B447(payload)
        val claimIds = plan.slides
            .flatMap { slide -> slide.bullets.map { it.claimId } }
            .distinct()
            .sorted()
        val evidence = plan.slides
            .flatMap { slide -> slide.bullets.flatMap { it.evidenceStableKeys } }
            .distinct()
            .sorted()
        val slideFingerprints = plan.slides.map { it.fingerprint }

        return PresentationArtifact(
            semanticPlanFingerprint = semanticPlan.fingerprint,
            presentationPlanFingerprint = plan.fingerprint,
            renderedClaimIds = claimIds,
            evidenceStableKeys = evidence,
            slideFingerprints = slideFingerprints,
            payload = payload,
            contentSha256 = contentSha,
            fingerprint = presentationArtifactFingerprint(
                semanticPlan.fingerprint,
                plan.fingerprint,
                claimIds,
                evidence,
                slideFingerprints,
                contentSha,
            ),
        )
    }
}

private fun validateEvidenceClosureB447(
    semanticPlan: SemanticArtifactPlan,
    plan: PresentationArtifactPlan,
) {
    val resolved = semanticPlan.resolvedClaims().associateBy { it.claimId }
    require(resolved.isNotEmpty()) {
        "B447 requires resolved semantic claims"
    }
    plan.slides.forEach { slide ->
        slide.bullets.forEach { bullet ->
            val claim = requireNotNull(resolved[bullet.claimId]) {
                "B447 presentation bullet references unresolved or foreign claim"
            }
            require(bullet.text == claim.canonicalContent) {
                "B447 presentation bullet must equal exact canonical claim content"
            }
            val evidence = claim.evidence
                .map { it.stableKey }
                .distinct()
                .sorted()
            require(bullet.evidenceStableKeys == evidence) {
                "B447 presentation bullet evidence differs from exact claim evidence"
            }
        }
    }
}

private fun renderPptxB447(plan: PresentationArtifactPlan): ByteArray {
    val output = ByteArrayOutputStream()
    ZipOutputStream(output).use { zip ->
        writeZipEntryB447(
            zip,
            "[Content_Types].xml",
            pptxContentTypesB447(plan.slides.size),
        )
        writeZipEntryB447(zip, "_rels/.rels", pptxRootRelsB447())
        writeZipEntryB447(zip, "docProps/core.xml", pptxCorePropertiesB447())
        writeZipEntryB447(zip, "docProps/app.xml", pptxAppPropertiesB447(plan.slides.size))
        writeZipEntryB447(zip, "ppt/presentation.xml", pptxPresentationB447(plan.slides.size))
        writeZipEntryB447(
            zip,
            "ppt/_rels/presentation.xml.rels",
            pptxPresentationRelsB447(plan.slides.size),
        )
        writeZipEntryB447(zip, "ppt/slideMasters/slideMaster1.xml", pptxSlideMasterB447())
        writeZipEntryB447(
            zip,
            "ppt/slideMasters/_rels/slideMaster1.xml.rels",
            pptxSlideMasterRelsB447(),
        )
        writeZipEntryB447(zip, "ppt/slideLayouts/slideLayout1.xml", pptxSlideLayoutB447())
        writeZipEntryB447(
            zip,
            "ppt/slideLayouts/_rels/slideLayout1.xml.rels",
            pptxSlideLayoutRelsB447(),
        )
        writeZipEntryB447(zip, "ppt/theme/theme1.xml", pptxThemeB447())
        plan.slides.forEachIndexed { index, slide ->
            val number = index + 1
            writeZipEntryB447(
                zip,
                "ppt/slides/slide$number.xml",
                pptxSlideB447(slide),
            )
            writeZipEntryB447(
                zip,
                "ppt/slides/_rels/slide$number.xml.rels",
                pptxSlideRelsB447(),
            )
        }
    }
    return output.toByteArray()
}

private fun pptxSlideB447(slide: PresentationSlidePlan): String = buildString {
    append(XML_HEADER_B447)
    append("<p:sld xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" ")
    append("xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" ")
    append("xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\">")
    append("<p:cSld><p:spTree>")
    append("<p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>")
    append("<p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/>")
    append("<a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>")
    append(textShapeB447(
        id = 2,
        name = "Title",
        x = 457200,
        y = 274320,
        cx = 8229600,
        cy = 914400,
        texts = listOf(slide.title),
        title = true,
    ))
    append(textShapeB447(
        id = 3,
        name = "Content",
        x = 685800,
        y = 1371600,
        cx = 7772400,
        cy = 4800600,
        texts = slide.bullets.map { "• " + it.text },
        title = false,
    ))
    append("</p:spTree></p:cSld>")
    append("<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr>")
    append("</p:sld>")
}

private fun textShapeB447(
    id: Int,
    name: String,
    x: Long,
    y: Long,
    cx: Long,
    cy: Long,
    texts: List<String>,
    title: Boolean,
): String = buildString {
    append("<p:sp><p:nvSpPr><p:cNvPr id=\"")
    append(id)
    append("\" name=\"")
    append(escapeXmlB447(name))
    append("\"/><p:cNvSpPr txBox=\"1\"/><p:nvPr/></p:nvSpPr>")
    append("<p:spPr><a:xfrm><a:off x=\"")
    append(x)
    append("\" y=\"")
    append(y)
    append("\"/><a:ext cx=\"")
    append(cx)
    append("\" cy=\"")
    append(cy)
    append("\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom>")
    append("<a:noFill/><a:ln><a:noFill/></a:ln></p:spPr>")
    append("<p:txBody><a:bodyPr wrap=\"square\"/><a:lstStyle/>")
    texts.forEach { text ->
        append("<a:p><a:r><a:rPr lang=\"en-US\" sz=\"")
        append(if (title) "2800" else "1800")
        append("\"")
        if (title) append(" b=\"1\"")
        append("/><a:t>")
        append(escapeXmlB447(text))
        append("</a:t></a:r><a:endParaRPr lang=\"en-US\"/></a:p>")
    }
    append("</p:txBody></p:sp>")
}

private fun pptxContentTypesB447(slideCount: Int): String = buildString {
    append(XML_HEADER_B447)
    append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
    append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
    append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
    append("<Override PartName=\"/ppt/presentation.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml\"/>")
    append("<Override PartName=\"/ppt/slideMasters/slideMaster1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml\"/>")
    append("<Override PartName=\"/ppt/slideLayouts/slideLayout1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml\"/>")
    append("<Override PartName=\"/ppt/theme/theme1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.theme+xml\"/>")
    for (index in 1..slideCount) {
        append("<Override PartName=\"/ppt/slides/slide")
        append(index)
        append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slide+xml\"/>")
    }
    append("<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>")
    append("<Override PartName=\"/docProps/app.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.extended-properties+xml\"/>")
    append("</Types>")
}

private fun pptxRootRelsB447(): String =
    XML_HEADER_B447 +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"ppt/presentation.xml\"/>" +
        "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/>" +
        "<Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties\" Target=\"docProps/app.xml\"/>" +
        "</Relationships>"

private fun pptxPresentationB447(slideCount: Int): String = buildString {
    append(XML_HEADER_B447)
    append("<p:presentation xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" ")
    append("xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" ")
    append("xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\">")
    append("<p:sldMasterIdLst><p:sldMasterId id=\"2147483648\" r:id=\"rId1\"/></p:sldMasterIdLst>")
    append("<p:sldIdLst>")
    for (index in 1..slideCount) {
        append("<p:sldId id=\"")
        append(255 + index)
        append("\" r:id=\"rId")
        append(index + 1)
        append("\"/>")
    }
    append("</p:sldIdLst>")
    append("<p:sldSz cx=\"9144000\" cy=\"6858000\" type=\"screen4x3\"/>")
    append("<p:notesSz cx=\"6858000\" cy=\"9144000\"/>")
    append("</p:presentation>")
}

private fun pptxPresentationRelsB447(slideCount: Int): String = buildString {
    append(XML_HEADER_B447)
    append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
    append("<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster\" Target=\"slideMasters/slideMaster1.xml\"/>")
    for (index in 1..slideCount) {
        append("<Relationship Id=\"rId")
        append(index + 1)
        append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide\" Target=\"slides/slide")
        append(index)
        append(".xml\"/>")
    }
    append("</Relationships>")
}

private fun pptxSlideMasterB447(): String =
    XML_HEADER_B447 +
        "<p:sldMaster xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\">" +
        "<p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr></p:spTree></p:cSld>" +
        "<p:clrMap accent1=\"accent1\" accent2=\"accent2\" accent3=\"accent3\" accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" bg1=\"lt1\" bg2=\"lt2\" folHlink=\"folHlink\" hlink=\"hlink\" tx1=\"dk1\" tx2=\"dk2\"/>" +
        "<p:sldLayoutIdLst><p:sldLayoutId id=\"1\" r:id=\"rId1\"/></p:sldLayoutIdLst>" +
        "<p:txStyles><p:titleStyle/><p:bodyStyle/><p:otherStyle/></p:txStyles></p:sldMaster>"

private fun pptxSlideMasterRelsB447(): String =
    XML_HEADER_B447 +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/>" +
        "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme\" Target=\"../theme/theme1.xml\"/>" +
        "</Relationships>"

private fun pptxSlideLayoutB447(): String =
    XML_HEADER_B447 +
        "<p:sldLayout xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\" type=\"blank\" preserve=\"1\">" +
        "<p:cSld name=\"Blank\"><p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr></p:spTree></p:cSld>" +
        "<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>"

private fun pptxSlideLayoutRelsB447(): String =
    XML_HEADER_B447 +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster\" Target=\"../slideMasters/slideMaster1.xml\"/>" +
        "</Relationships>"

private fun pptxSlideRelsB447(): String =
    XML_HEADER_B447 +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/>" +
        "</Relationships>"

private fun pptxThemeB447(): String =
    XML_HEADER_B447 +
        "<a:theme xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" name=\"LIFEOS\">" +
        "<a:themeElements><a:clrScheme name=\"LIFEOS\">" +
        "<a:dk1><a:sysClr val=\"windowText\" lastClr=\"000000\"/></a:dk1><a:lt1><a:sysClr val=\"window\" lastClr=\"FFFFFF\"/></a:lt1>" +
        "<a:dk2><a:srgbClr val=\"1F1F1F\"/></a:dk2><a:lt2><a:srgbClr val=\"F2F2F2\"/></a:lt2>" +
        "<a:accent1><a:srgbClr val=\"4472C4\"/></a:accent1><a:accent2><a:srgbClr val=\"ED7D31\"/></a:accent2>" +
        "<a:accent3><a:srgbClr val=\"A5A5A5\"/></a:accent3><a:accent4><a:srgbClr val=\"FFC000\"/></a:accent4>" +
        "<a:accent5><a:srgbClr val=\"5B9BD5\"/></a:accent5><a:accent6><a:srgbClr val=\"70AD47\"/></a:accent6>" +
        "<a:hlink><a:srgbClr val=\"0563C1\"/></a:hlink><a:folHlink><a:srgbClr val=\"954F72\"/></a:folHlink></a:clrScheme>" +
        "<a:fontScheme name=\"LIFEOS\"><a:majorFont><a:latin typeface=\"Aptos Display\"/></a:majorFont><a:minorFont><a:latin typeface=\"Aptos\"/></a:minorFont></a:fontScheme>" +
        "<a:fmtScheme name=\"LIFEOS\"><a:fillStyleLst><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:fillStyleLst>" +
        "<a:lnStyleLst><a:ln w=\"9525\"><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:ln></a:lnStyleLst>" +
        "<a:effectStyleLst><a:effectStyle><a:effectLst/></a:effectStyle></a:effectStyleLst>" +
        "<a:bgFillStyleLst><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:bgFillStyleLst></a:fmtScheme></a:themeElements></a:theme>"

private fun pptxCorePropertiesB447(): String =
    XML_HEADER_B447 +
        "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:dcterms=\"http://purl.org/dc/terms/\" xmlns:dcmitype=\"http://purl.org/dc/dcmitype/\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">" +
        "<dc:title>LIFEOS presentation</dc:title><dc:creator>LIFEOS</dc:creator><cp:lastModifiedBy>LIFEOS</cp:lastModifiedBy>" +
        "<dcterms:created xsi:type=\"dcterms:W3CDTF\">" + FIXED_TIMESTAMP_B447 + "</dcterms:created>" +
        "<dcterms:modified xsi:type=\"dcterms:W3CDTF\">" + FIXED_TIMESTAMP_B447 + "</dcterms:modified></cp:coreProperties>"

private fun pptxAppPropertiesB447(slideCount: Int): String =
    XML_HEADER_B447 +
        "<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\" xmlns:vt=\"http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes\">" +
        "<Application>LIFEOS</Application><PresentationFormat>On-screen Show (4:3)</PresentationFormat><Slides>" +
        slideCount + "</Slides><Notes>0</Notes><HiddenSlides>0</HiddenSlides><AppVersion>1.0</AppVersion></Properties>"

private fun writeZipEntryB447(
    zip: ZipOutputStream,
    name: String,
    content: String,
) {
    val entry = ZipEntry(name)
    entry.time = 0L
    zip.putNextEntry(entry)
    zip.write(content.toByteArray(StandardCharsets.UTF_8))
    zip.closeEntry()
}

private fun escapeXmlB447(value: String): String = buildString(value.length) {
    value.forEach { char ->
        when (char) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> if (char.code >= 0x20 || char == '\n' || char == '\t') append(char)
        }
    }
}

private fun presentationBulletFingerprint(
    claimId: String,
    text: String,
    evidenceStableKeys: List<String>,
): String = StableCognitiveIds.fingerprint(
    "presentation-bullet/v1",
    claimId,
    text,
    evidenceStableKeys.joinToString("\u001f"),
)

private fun presentationSlideFingerprint(
    ordinal: Int,
    title: String,
    bullets: List<PresentationBullet>,
): String = StableCognitiveIds.fingerprint(
    "presentation-slide-plan/v1",
    ordinal.toString(),
    title,
    *bullets.map { it.fingerprint }.toTypedArray(),
)

private fun presentationPlanFingerprint(
    semanticPlanFingerprint: String,
    sourceWorldRevision: Long,
    slides: List<PresentationSlidePlan>,
): String = StableCognitiveIds.fingerprint(
    "presentation-artifact-plan/v1",
    semanticPlanFingerprint,
    sourceWorldRevision.toString(),
    *slides.map { it.fingerprint }.toTypedArray(),
)

private fun presentationArtifactFingerprint(
    semanticPlanFingerprint: String,
    presentationPlanFingerprint: String,
    renderedClaimIds: List<String>,
    evidenceStableKeys: List<String>,
    slideFingerprints: List<String>,
    contentSha256: String,
): String = StableCognitiveIds.fingerprint(
    "presentation-artifact/v1",
    semanticPlanFingerprint,
    presentationPlanFingerprint,
    renderedClaimIds.joinToString("\u001f"),
    evidenceStableKeys.joinToString("\u001f"),
    slideFingerprints.joinToString("\u001f"),
    contentSha256,
)

private fun sha256B447(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

private const val XML_HEADER_B447 =
    "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
private const val FIXED_TIMESTAMP_B447 = "2000-01-01T00:00:00Z"
private const val MAX_PRESENTATION_SLIDES_B447 = 256
private const val MAX_BULLETS_PER_SLIDE_B447 = 32
private const val MAX_PRESENTATION_TITLE_CHARS_B447 = 240
private const val MAX_PRESENTATION_TEXT_CHARS_B447 = 8_000
private val SHA_256_B447 = Regex("[0-9a-f]{64}")
