package app.lifeos.core.model

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhotonIndexExcludedTagsTest {
    private val entry = PhotonIndexEntry(
        ref = PhotonRevisionRef(PhotonId("archive-turn"), 1L),
        createdAt = Instant.parse("2026-09-20T00:00:00Z"),
        phase = PhotonPhase.ACTIVE,
        mimeType = "text/plain",
        tags = setOf("chat", "corpus:archive"),
        semanticMass = 1.0,
        confidence = 1.0,
        contentFingerprint = "a".repeat(64),
        latest = true,
    )

    @Test
    fun archiveTagsCanBeExcludedWithoutChangingRequiredTagSemantics() {
        assertTrue(entry.matches(PhotonIndexQuery(allTags = setOf("chat"))))
        assertFalse(
            entry.matches(
                PhotonIndexQuery(
                    allTags = setOf("chat"),
                    excludedTags = setOf("corpus:archive"),
                )
            )
        )
    }
}
