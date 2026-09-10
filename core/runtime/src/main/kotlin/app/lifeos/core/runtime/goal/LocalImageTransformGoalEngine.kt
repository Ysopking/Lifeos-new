package app.lifeos.core.runtime.goal

import app.lifeos.core.image.ImagePhotonFactory
import app.lifeos.core.image.LocalImageTransformOperation
import app.lifeos.core.language.GoalFrame
import app.lifeos.core.language.IntentType
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonPhase
import java.util.Locale

sealed interface LocalImageTransformPlanResult {
    data class Prepared(
        val sourcePhoton: Photon,
        val operations: List<LocalImageTransformOperation>,
    ) : LocalImageTransformPlanResult

    data class Blocked(val reason: String) : LocalImageTransformPlanResult {
        init { require(reason.isNotBlank()) }
    }

    data class Unsupported(val intent: IntentType) : LocalImageTransformPlanResult
}

/**
 * Resolves a TRANSFORM_IMAGE goal to one existing LIFEOS image and a bounded deterministic list of
 * pixel operations. Explicit/resolved image references win; fallback to the newest image is allowed
 * only when the utterance did not contain an unresolved image reference.
 */
class LocalImageTransformGoalEngine {
    fun supports(intent: IntentType): Boolean = intent == IntentType.TRANSFORM_IMAGE

    fun plan(
        goal: GoalFrame,
        photons: List<Photon>,
    ): LocalImageTransformPlanResult {
        if (!supports(goal.intent)) return LocalImageTransformPlanResult.Unsupported(goal.intent)

        val operations = extractOperations(goal.objective)
        if (operations.isEmpty()) return LocalImageTransformPlanResult.Blocked("image-transform-operation-unsupported")

        val images = photons
            .filter { photon ->
                photon.mimeType == ImagePhotonFactory.IMAGE_REFERENCE_MIME &&
                    photon.phase != PhotonPhase.ARCHIVED
            }
        val imagesById = images.associateBy { it.id }
        val resolvedReferencedImage = goal.references
            .asSequence()
            .mapNotNull { reference -> reference.targetPhotonId }
            .mapNotNull(imagesById::get)
            .firstOrNull()

        val hasImageReference = goal.references.any { reference ->
            "image" in reference.expression.preferredKinds
        }
        val source = resolvedReferencedImage ?: when {
            hasImageReference -> null
            else -> images.maxWithOrNull(
                compareBy<Photon> { it.provenance.createdAt }.thenBy { it.id.value }
            )
        }
        if (source == null) {
            return LocalImageTransformPlanResult.Blocked(
                if (hasImageReference) "image-transform-reference-unresolved" else "image-transform-source-missing"
            )
        }

        return LocalImageTransformPlanResult.Prepared(source, operations)
    }

    fun extractOperations(objective: String): List<LocalImageTransformOperation> {
        val normalized = objective.lowercase(Locale.ROOT)
        return OPERATION_PATTERNS
            .flatMap { (operation, pattern) ->
                pattern.findAll(normalized).map { match -> OperationMatch(match.range.first, operation) }
            }
            .sortedWith(compareBy<OperationMatch> { it.index }.thenBy { it.operation.name })
            .map { it.operation }
            .distinct()
    }

    private data class OperationMatch(
        val index: Int,
        val operation: LocalImageTransformOperation,
    )

    private companion object {
        val OPERATION_PATTERNS = listOf(
            LocalImageTransformOperation.BRIGHTER to Regex("\\b(heller|aufhellen|brighter|brighten)\\b"),
            LocalImageTransformOperation.DARKER to Regex("\\b(dunkler|abdunkeln|darker|darken)\\b"),
            LocalImageTransformOperation.WARMER to Regex("\\b(wärmer|waermer|warmer)\\b"),
            LocalImageTransformOperation.COOLER to Regex("\\b(kühler|kuehler|cooler)\\b"),
            LocalImageTransformOperation.SHARPER to Regex("\\b(schärfer|schaerfer|schärfen|schaerfen|sharper|sharpen)\\b"),
            LocalImageTransformOperation.GRAYSCALE to Regex("\\b(schwarzweiß|schwarzweiss|graustufe|graustufen|grayscale|greyscale)\\b"),
        )
    }
}
