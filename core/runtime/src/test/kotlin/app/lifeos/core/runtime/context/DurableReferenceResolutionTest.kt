package app.lifeos.core.runtime.context

import app.lifeos.core.language.LanguageUnderstandingEngine
import app.lifeos.core.language.ReferenceExpression
import app.lifeos.core.language.ReferenceKind
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.Provenance
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DurableReferenceResolutionTest {
    private val now = Instant.parse("2026-09-10T16:00:00Z")

    @Test
    fun `target phrases resolve after context stores are recreated`() = runTest {
        val repository = DurableContextStoreTest.InMemoryPhotonRepository()
        val activeGoal = photon(
            "goal-current",
            "Continue LIFEOS G01 implementation",
            tags = setOf("goal"),
            createdAt = now.minusSeconds(1_800),
        )
        val image = photon(
            "image-current",
            "Current architecture image",
            mimeType = "image/png",
            tags = setOf("image", "result"),
            createdAt = now.minusSeconds(3_600),
        )
        val apk = photon(
            "apk-current",
            "LIFEOS debug APK",
            mimeType = "application/vnd.android.package-archive",
            tags = setOf("apk", "result"),
            createdAt = now.minusSeconds(2_700),
        )
        val activeModule = photon(
            "module-current",
            "Current runtime module",
            tags = setOf("module"),
            createdAt = now.minusSeconds(1_200),
        )
        val otherModule = photon(
            "module-other",
            "Previous field module",
            tags = setOf("module"),
            createdAt = now.minusSeconds(7_200),
        )
        val yesterday = photon(
            "yesterday-result",
            "Yesterday completed artifact",
            tags = setOf("result"),
            createdAt = now.minusSeconds(24 * 60 * 60),
        )
        listOf(activeGoal, image, apk, activeModule, otherModule, yesterday).forEach {
            repository.save(it)
        }

        ConversationContextStore(repository, ConversationContextId("conversation-a")).apply {
            record(image, active = true, recordedAt = now.minusSeconds(3_500))
            record(yesterday, active = true, recordedAt = now.minusSeconds(23 * 60 * 60))
        }
        ProjectContextStore(repository, ProjectContextId("project-lifeos")).apply {
            record(apk, active = true, recordedAt = now.minusSeconds(2_600))
            record(activeModule, active = true, recordedAt = now.minusSeconds(1_100))
            record(otherModule, active = false, recordedAt = now.minusSeconds(7_100))
        }
        GoalContextStore(repository, GoalContextId("goal-lifeos")).record(
            activeGoal,
            active = true,
            recordedAt = now.minusSeconds(1_700),
        )

        // Recreate all runtime-facing stores to model a new process reading the same durable truth.
        val resolver = ReferenceContextResolver(
            conversation = ConversationContextStore(
                repository,
                ConversationContextId("conversation-a"),
            ),
            project = ProjectContextStore(
                repository,
                ProjectContextId("project-lifeos"),
            ),
            goal = GoalContextStore(repository, GoalContextId("goal-lifeos")),
            now = { now },
        )

        assertResolution(resolver, "Weiter", ReferenceKind.PREVIOUS, activeGoal.id)
        assertResolution(resolver, "das Bild", ReferenceKind.THAT, image.id)
        assertResolution(resolver, "die APK", ReferenceKind.THAT, apk.id)
        val other = assertResolution(
            resolver,
            "das andere Modul",
            ReferenceKind.OTHER,
            otherModule.id,
        )
        assertTrue(other.alternatives.any { it.photonId == activeModule.id })
        assertResolution(resolver, "wie gestern", ReferenceKind.YESTERDAY, yesterday.id)
    }

    @Test
    fun `unreadable photon vault state remains visible in resolution`() = runTest {
        val repository = DurableContextStoreTest.InMemoryPhotonRepository()
        val image = photon(
            "image-current",
            "Current image",
            mimeType = "image/png",
            tags = setOf("image"),
            createdAt = now.minusSeconds(900),
        )
        repository.save(image)
        ConversationContextStore(repository, ConversationContextId("conversation-a"))
            .record(image, recordedAt = now.minusSeconds(800))
        repository.unreadableFiles = listOf("broken.photon")

        val resolver = ReferenceContextResolver(
            conversation = ConversationContextStore(
                repository,
                ConversationContextId("conversation-a"),
            ),
            now = { now },
        )
        val resolution = resolver.resolve(expression("das Bild", ReferenceKind.THAT))

        assertEquals(image.id, resolution.targetPhotonId)
        assertTrue(resolution.incompleteContext)
        assertTrue(resolution.reasons.any { it.startsWith("context-view-incomplete=") })
        assertTrue(
            resolution.alternatives.single().reasons.contains("incomplete-context-view")
        )
    }

    private suspend fun assertResolution(
        resolver: ReferenceContextResolver,
        utterance: String,
        expectedKind: ReferenceKind,
        expectedTarget: PhotonId,
    ): DurableReferenceResolution {
        val expression = expression(utterance, expectedKind)
        val result = resolver.resolve(expression)
        assertEquals(expectedTarget, result.targetPhotonId, utterance)
        assertTrue(result.confidence > 0.0, utterance)
        assertTrue(!result.incompleteContext, utterance)
        assertTrue(result.alternatives.isNotEmpty(), utterance)
        return result
    }

    private fun expression(
        utterance: String,
        expectedKind: ReferenceKind,
    ): ReferenceExpression {
        val references = LanguageUnderstandingEngine()
            .understand(utterance)
            .goal
            .references
        val expression = references.single { it.expression.kind == expectedKind }.expression
        assertTrue(expression.rawText.isNotBlank())
        return expression
    }

    private fun photon(
        id: String,
        content: String,
        mimeType: String = "text/plain",
        tags: Set<String> = emptySet(),
        createdAt: Instant,
    ) = Photon(
        id = PhotonId(id),
        revision = 1,
        content = content,
        mimeType = mimeType,
        confidence = 0.9,
        provenance = Provenance("test", "user", createdAt),
        tags = tags,
    )
}
