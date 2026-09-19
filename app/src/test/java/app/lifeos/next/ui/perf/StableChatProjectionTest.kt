package app.lifeos.next.ui.perf

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.Provenance
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class StableChatProjectionTest {
    @Test
    fun equivalentRevisionVectorReusesProjectionAcrossInputOrder() {
        val first = chatPhoton("a", 1, "Alpha", 1)
        val second = chatPhoton("b", 1, "Beta", 2)
        val cache = StableChatProjection()

        val initial = cache.project(listOf(first, second))
        val reordered = cache.project(listOf(second, first))

        assertSame(initial, reordered)
        assertEquals(1, cache.recomputationCount)
    }

    @Test
    fun revisionChangeInvalidatesProjection() {
        val cache = StableChatProjection()
        val first = cache.project(listOf(chatPhoton("a", 1, "Alpha", 1)))
        val revised = cache.project(listOf(chatPhoton("a", 2, "Alpha 2", 1)))

        assertNotSame(first, revised)
        assertEquals("Alpha 2", revised.events.single().text)
        assertEquals(2, cache.recomputationCount)
    }


    @Test
    fun revisionFingerprintIsOrderIndependentAndRevisionSensitive() {
        val first = chatPhoton("a", 1, "Alpha", 1)
        val second = chatPhoton("b", 1, "Beta", 2)
        val revised = chatPhoton("a", 2, "Alpha 2", 1)

        assertEquals(
            canonicalPhotonRevisionFingerprint(listOf(first, second)),
            canonicalPhotonRevisionFingerprint(listOf(second, first)),
        )
        assertNotEquals(
            canonicalPhotonRevisionFingerprint(listOf(first, second)),
            canonicalPhotonRevisionFingerprint(listOf(revised, second)),
        )
    }

    @Test
    fun largeStableHistoryDoesNotReprojectWhenRuntimeStateChangesOnly() {
        val photons = (0 until 3000).map { index ->
            chatPhoton(
                id = "p-$index",
                revision = 1,
                content = "Message $index",
                second = index.toLong(),
            )
        }
        val cache = StableChatProjection()

        val first = cache.project(photons)
        repeat(20) {
            assertSame(first, cache.project(photons.toList()))
        }

        assertEquals(3000, first.events.size)
        assertEquals(1, cache.recomputationCount)
    }

    private fun chatPhoton(
        id: String,
        revision: Long,
        content: String,
        second: Long,
    ): Photon = Photon(
        id = PhotonId(id),
        revision = revision,
        content = content,
        provenance = Provenance(
            source = "test",
            actor = "user",
            createdAt = Instant.EPOCH.plusSeconds(second),
        ),
        tags = setOf("chat", "chat:user", "conversation:default"),
    )
}
