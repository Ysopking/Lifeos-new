package app.lifeos.core.creative

import app.lifeos.core.model.SemanticArtifactClaim
import app.lifeos.core.model.SemanticArtifactKind
import app.lifeos.core.model.SemanticArtifactPlan

data class ArtifactLayoutBlock(
    val claimId: String,
    val content: String,
    val ordinal: Int,
) {
    init {
        require(claimId.isNotBlank())
        require(content.isNotBlank())
        require(ordinal >= 0)
    }
}

data class ArtifactLayout(
    val planId: String,
    val planRevision: Long,
    val blocks: List<ArtifactLayoutBlock>,
) {
    init {
        require(planId.isNotBlank())
        require(planRevision > 0L)
        require(blocks.map { it.claimId }.distinct().size == blocks.size)
    }
}

class ArtifactLayoutPlanner {
    fun plan(plan: SemanticArtifactPlan): ArtifactLayout {
        val claims = renderableClaims(plan)
        return ArtifactLayout(
            planId = plan.planId,
            planRevision = plan.planRevision,
            blocks = claims.mapIndexed { index, claim ->
                ArtifactLayoutBlock(
                    claimId = claim.claimId,
                    content = claim.canonicalContent,
                    ordinal = index,
                )
            },
        )
    }
}

data class RenderedSemanticArtifact(
    val planFingerprint: String,
    val mediaType: String,
    val payload: ByteArray,
    val renderedClaimIds: Set<String>,
) {
    init {
        require(planFingerprint.isNotBlank())
        require(mediaType.isNotBlank())
        require(payload.isNotEmpty())
        require(renderedClaimIds.isNotEmpty())
    }
}

class DocumentRenderer(
    private val layoutPlanner: ArtifactLayoutPlanner = ArtifactLayoutPlanner(),
) {
    fun render(plan: SemanticArtifactPlan): RenderedSemanticArtifact {
        require(plan.kind == SemanticArtifactKind.TEXT || plan.kind == SemanticArtifactKind.PDF) {
            "DocumentRenderer accepts TEXT/PDF semantic plans only"
        }
        val layout = layoutPlanner.plan(plan)
        val text = layout.blocks.joinToString(separator = "\n\n") { it.content }
        return RenderedSemanticArtifact(
            planFingerprint = plan.fingerprint,
            mediaType = if (plan.kind == SemanticArtifactKind.PDF) {
                "application/vnd.lifeos.semantic-document+text"
            } else {
                "text/plain"
            },
            payload = text.toByteArray(Charsets.UTF_8),
            renderedClaimIds = layout.blocks.mapTo(linkedSetOf()) { it.claimId },
        )
    }
}

class ImageCompositionRenderer(
    private val layoutPlanner: ArtifactLayoutPlanner = ArtifactLayoutPlanner(),
) {
    fun render(plan: SemanticArtifactPlan): RenderedSemanticArtifact {
        require(plan.kind == SemanticArtifactKind.IMAGE)
        val layout = layoutPlanner.plan(plan)
        val scene = buildString {
            appendLine("semantic-image-plan:${plan.planId}@${plan.planRevision}")
            layout.blocks.forEach { block ->
                append(block.ordinal)
                append('|')
                append(block.claimId)
                append('|')
                appendLine(block.content)
            }
        }
        return RenderedSemanticArtifact(
            planFingerprint = plan.fingerprint,
            mediaType = "application/vnd.lifeos.semantic-image-scene+text",
            payload = scene.toByteArray(Charsets.UTF_8),
            renderedClaimIds = layout.blocks.mapTo(linkedSetOf()) { it.claimId },
        )
    }
}

class CodeRenderer(
    private val layoutPlanner: ArtifactLayoutPlanner = ArtifactLayoutPlanner(),
) {
    fun render(plan: SemanticArtifactPlan): RenderedSemanticArtifact {
        require(plan.kind == SemanticArtifactKind.TASK) {
            "CodeRenderer accepts TASK semantic plans only"
        }
        val layout = layoutPlanner.plan(plan)
        val code = layout.blocks.joinToString(separator = "\n") { block ->
            "// claim:${block.claimId}\n${block.content}"
        }
        return RenderedSemanticArtifact(
            planFingerprint = plan.fingerprint,
            mediaType = "text/plain",
            payload = code.toByteArray(Charsets.UTF_8),
            renderedClaimIds = layout.blocks.mapTo(linkedSetOf()) { it.claimId },
        )
    }
}

private fun renderableClaims(plan: SemanticArtifactPlan): List<SemanticArtifactClaim> {
    require(plan.unresolvedClaimIds.isEmpty()) {
        "Renderer refuses semantic plan with unresolved claims"
    }
    val claims = plan.resolvedClaims()
    require(claims.isNotEmpty()) { "Renderer requires at least one resolved semantic claim" }
    require(claims.all { it.evidence.isNotEmpty() }) {
        "Renderer requires revision evidence for every claim"
    }
    return claims
}
