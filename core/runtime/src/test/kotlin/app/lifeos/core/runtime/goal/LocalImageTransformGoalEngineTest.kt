package app.lifeos.core.runtime.goal

import app.lifeos.core.image.ImagePhotonFactory
import app.lifeos.core.image.LocalImageTransformOperation
import app.lifeos.core.language.GoalFrame
import app.lifeos.core.language.IntentType
import app.lifeos.core.language.LanguageCode
import app.lifeos.core.language.ReferenceExpression
import app.lifeos.core.language.ReferenceKind
import app.lifeos.core.language.ResolvedReference
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.Provenance
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LocalImageTransformGoalEngineTest {
    private val engine = LocalImageTransformGoalEngine()

    @Test
    fun `explicit resolved image reference wins over newer image`() {
        val older = image("older", "2026-09-10T10:00:00Z")
        val newer = image("newer", "2026-09-10T11:00:00Z")
        val goal = goal(
            objective = "Mach dieses Bild heller und schärfer",
            references = listOf(imageReference(older.id)),
        )

        val result = assertIs<LocalImageTransformPlanResult.Prepared>(
            engine.plan(goal, listOf(older, newer))
        )

        assertEquals(older.id, result.sourcePhoton.id)
        assertEquals(
            listOf(LocalImageTransformOperation.BRIGHTER, LocalImageTransformOperation.SHARPER),
            result.operations,
        )
    }

    @Test
    fun `newest image is used only when no image reference exists`() {
        val older = image("older", "2026-09-10T10:00:00Z")
        val newer = image("newer", "2026-09-10T11:00:00Z")

        val result = assertIs<LocalImageTransformPlanResult.Prepared>(
            engine.plan(goal("Mach das Bild wärmer"), listOf(older, newer))
        )

        assertEquals(newer.id, result.sourcePhoton.id)
        assertEquals(listOf(LocalImageTransformOperation.WARMER), result.operations)
    }

    @Test
    fun `unresolved image reference blocks instead of silently choosing another image`() {
        val available = image("available", "2026-09-10T11:00:00Z")
        val unresolved = ResolvedReference(
            expression = ReferenceExpression(
                kind = ReferenceKind.THIS,
                rawText = "dieses Bild",
                preferredKinds = setOf("image"),
                confidence = 0.9,
            ),
            targetPhotonId = null,
            score = 0.0,
        )

        val result = assertIs<LocalImageTransformPlanResult.Blocked>(
            engine.plan(goal("Mach dieses Bild dunkler", listOf(unresolved)), listOf(available))
        )

        assertEquals("image-transform-reference-unresolved", result.reason)
    }

    @Test
    fun `missing image or unsupported transform is an honest block`() {
        val noSource = assertIs<LocalImageTransformPlanResult.Blocked>(
            engine.plan(goal("Mach es heller"), emptyList())
        )
        assertEquals("image-transform-source-missing", noSource.reason)

        val unsupported = assertIs<LocalImageTransformPlanResult.Blocked>(
            engine.plan(goal("Dreh dieses Bild auf den Kopf"), listOf(image("image", "2026-09-10T10:00:00Z")))
        )
        assertEquals("image-transform-operation-unsupported", unsupported.reason)
    }

    @Test
    fun `operation order follows the utterance and duplicates collapse`() {
        assertEquals(
            listOf(
                LocalImageTransformOperation.DARKER,
                LocalImageTransformOperation.WARMER,
                LocalImageTransformOperation.GRAYSCALE,
            ),
            engine.extractOperations("dunkler, dann wärmer, noch wärmer und am Ende schwarzweiß"),
        )
    }

    private fun image(id: String, createdAt: String) = Photon(
        id = PhotonId(id),
        content = "image-ref",
        mimeType = ImagePhotonFactory.IMAGE_REFERENCE_MIME,
        provenance = Provenance(
            source = "test",
            actor = "test",
            createdAt = Instant.parse(createdAt),
        ),
        tags = setOf("image"),
    )

    private fun imageReference(target: PhotonId) = ResolvedReference(
        expression = ReferenceExpression(
            kind = ReferenceKind.THIS,
            rawText = "dieses Bild",
            preferredKinds = setOf("image"),
            confidence = 0.9,
        ),
        targetPhotonId = target,
        score = 0.9,
    )

    private fun goal(
        objective: String,
        references: List<ResolvedReference> = emptyList(),
    ) = GoalFrame(
        intent = IntentType.TRANSFORM_IMAGE,
        objective = objective,
        entities = emptyList(),
        references = references,
        constraints = emptyList(),
        ambiguities = emptyList(),
        confidence = 0.92,
        language = LanguageCode.DE,
    )
}
