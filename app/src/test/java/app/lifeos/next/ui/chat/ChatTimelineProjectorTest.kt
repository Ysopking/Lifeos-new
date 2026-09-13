package app.lifeos.next.ui.chat

import app.lifeos.core.image.ImagePhotonFactory
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.Provenance
import app.lifeos.core.runtime.chat.ConversationProjector
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ChatTimelineProjectorTest {
    private val baseTime = Instant.parse("2026-09-13T12:00:00Z")

    @Test
    fun plainChatPhotonsProduceOnlyMessages() {
        val user = chatUser("user-1", "turn-1", "Hallo", baseTime)
        val assistant = chatAssistant("assistant-1", "turn-1", "Hi", baseTime.plusSeconds(1), user.id)

        val timeline = ChatTimelineProjector.project(listOf(user, assistant))

        assertEquals(2, timeline.size)
        assertTrue(timeline.all { it is ChatTimelineItem.Message })
    }

    @Test
    fun unrelatedImageIsExcluded() {
        val user = chatUser("user-1", "turn-1", "Hallo", baseTime)
        val unrelated = image("image-1", baseTime.plusSeconds(1), setOf(PhotonId("other-parent")))

        val timeline = ChatTimelineProjector.project(listOf(user, unrelated))

        assertEquals(1, timeline.size)
        assertIs<ChatTimelineItem.Message>(timeline.single())
    }

    @Test
    fun generatedImageWithDirectChatUserParentIsIncluded() {
        val user = chatUser("user-1", "turn-1", "Erzeuge ein Bild", baseTime)
        val generated = image("image-1", baseTime.plusSeconds(1), setOf(user.id))

        val timeline = ChatTimelineProjector.project(listOf(user, generated))

        assertEquals(2, timeline.size)
        assertEquals(generated.id, assertIs<ChatTimelineItem.Image>(timeline.last()).photon.id)
    }

    @Test
    fun imageFromDifferentConversationIsExcluded() {
        val otherUser = chatUser(
            id = "user-other",
            turn = "turn-other",
            content = "Bild",
            createdAt = baseTime,
            conversationId = "other",
        )
        val generated = image("image-other", baseTime.plusSeconds(1), setOf(otherUser.id))

        val timeline = ChatTimelineProjector.project(listOf(otherUser, generated))

        assertTrue(timeline.isEmpty())
    }

    @Test
    fun transformedImageThroughGoalAncestryIsIncluded() {
        val user = chatUser("user-transform", "turn-transform", "Mach das Bild heller", baseTime)
        val goal = photon(
            id = "goal-transform",
            createdAt = baseTime.plusMillis(100),
            parents = setOf(user.id),
            tags = setOf("goal"),
        )
        val sourceImage = image(
            id = "source-image",
            createdAt = baseTime.minusSeconds(10),
            parents = setOf(PhotonId("unrelated-origin")),
        )
        val transformed = image(
            id = "transformed-image",
            createdAt = baseTime.plusSeconds(1),
            parents = setOf(sourceImage.id, goal.id),
        )

        val timeline = ChatTimelineProjector.project(listOf(user, goal, sourceImage, transformed))

        assertEquals(2, timeline.size)
        assertEquals(transformed.id, assertIs<ChatTimelineItem.Image>(timeline.last()).photon.id)
    }

    @Test
    fun brokenParentChainFailsClosed() {
        val user = chatUser("user-1", "turn-1", "Hallo", baseTime)
        val generated = image(
            "image-broken",
            baseTime.plusSeconds(1),
            setOf(PhotonId("missing-parent")),
        )

        val timeline = ChatTimelineProjector.project(listOf(user, generated))

        assertEquals(1, timeline.size)
        assertTrue(timeline.none { it is ChatTimelineItem.Image })
    }

    @Test
    fun cyclicParentGraphTerminatesAndFailsClosed() {
        val aId = PhotonId("cycle-a")
        val bId = PhotonId("cycle-b")
        val a = photon("cycle-a", baseTime, parents = setOf(bId))
        val b = photon("cycle-b", baseTime, parents = setOf(aId))
        val generated = image("image-cycle", baseTime.plusSeconds(1), setOf(aId))

        val timeline = ChatTimelineProjector.project(listOf(a, b, generated))

        assertTrue(timeline.isEmpty())
    }

    @Test
    fun orderingIsDeterministicByTimeKindAndId() {
        val user = chatUser("user-z", "turn-z", "Hallo", baseTime)
        val imageB = image("image-b", baseTime, setOf(user.id))
        val imageA = image("image-a", baseTime, setOf(user.id))
        val input = listOf(imageB, user, imageA)

        val first = ChatTimelineProjector.project(input)
        val second = ChatTimelineProjector.project(input.reversed())

        assertEquals(first, second)
        assertIs<ChatTimelineItem.Message>(first[0])
        assertEquals("image-a", assertIs<ChatTimelineItem.Image>(first[1]).photon.id.value)
        assertEquals("image-b", assertIs<ChatTimelineItem.Image>(first[2]).photon.id.value)
    }

    @Test
    fun messageProjectionRemainsExactlyConversationProjectorOutput() {
        val user = chatUser("user-1", "turn-1", "Hallo", baseTime)
        val assistant = chatAssistant("assistant-1", "turn-1", "Antwort", baseTime.plusSeconds(1), user.id)
        val image = image("image-1", baseTime.plusSeconds(2), setOf(user.id))
        val photons = listOf(image, assistant, user)

        val expected = ConversationProjector.project(photons)
        val actual = ChatTimelineProjector.project(photons)
            .filterIsInstance<ChatTimelineItem.Message>()
            .map { it.event }

        assertEquals(expected, actual)
    }

    private fun chatUser(
        id: String,
        turn: String,
        content: String,
        createdAt: Instant,
        conversationId: String = ConversationProjector.DEFAULT_CONVERSATION_ID,
    ): Photon = photon(
        id = id,
        content = content,
        createdAt = createdAt,
        tags = setOf(
            "chat",
            "chat:user",
            "${ConversationProjector.CONVERSATION_PREFIX}$conversationId",
            "${ConversationProjector.TURN_PREFIX}$turn",
        ),
        actor = "user",
    )

    private fun chatAssistant(
        id: String,
        turn: String,
        content: String,
        createdAt: Instant,
        parent: PhotonId,
    ): Photon = photon(
        id = id,
        content = content,
        createdAt = createdAt,
        parents = setOf(parent),
        tags = setOf(
            "chat",
            "chat:assistant",
            "${ConversationProjector.CONVERSATION_PREFIX}${ConversationProjector.DEFAULT_CONVERSATION_ID}",
            "${ConversationProjector.TURN_PREFIX}$turn",
        ),
        actor = "lifeos",
    )

    private fun image(
        id: String,
        createdAt: Instant,
        parents: Set<PhotonId>,
    ): Photon = photon(
        id = id,
        content = "image-reference-$id",
        createdAt = createdAt,
        parents = parents,
        mimeType = ImagePhotonFactory.IMAGE_REFERENCE_MIME,
        tags = setOf("image", "asset-ref"),
        actor = "lifeos.image",
    )

    private fun photon(
        id: String,
        createdAt: Instant,
        parents: Set<PhotonId> = emptySet(),
        tags: Set<String> = emptySet(),
        content: String = id,
        mimeType: String = "text/plain",
        actor: String = "test",
    ): Photon = Photon(
        id = PhotonId(id),
        content = content,
        mimeType = mimeType,
        provenance = Provenance(
            source = "test",
            actor = actor,
            createdAt = createdAt,
            parentIds = parents,
        ),
        tags = tags,
    )
}
