package app.lifeos.core.language

import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonRevisionRef
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

class RevisionAwareReferenceResolverTest {
    private val resolver = ReferenceResolver()
    private val now = Instant.parse("2026-09-18T12:00:00Z")

    @Test
    fun `semantic content outweighs unrelated active image`() {
        val dogId = PhotonId("dog-image")
        val catId = PhotonId("cat-image")
        val context = LanguageContext(
            items = listOf(
                item(catId, 4, setOf("bild", "katze", "garten"), active = true),
                item(dogId, 3, setOf("bild", "hund", "garten"), active = false),
            ),
            now = now,
        )
        val expression = ReferenceExpression(
            kind = ReferenceKind.THAT,
            rawText = "das Bild mit dem Hund",
            preferredKinds = setOf("image"),
            confidence = 0.9,
        )

        val resolved = resolver.resolve(expression, context)

        assertEquals(PhotonRevisionRef(dogId, 3), resolved.targetPhotonRef)
        assertEquals(dogId, resolved.targetPhotonId)
    }

    @Test
    fun `same photon id remains revision specific`() {
        val id = PhotonId("same-image")
        val older = item(id, 2, setOf("bild", "alt"), active = false)
        val newer = item(id, 7, setOf("bild", "hund"), active = true)
        val context = LanguageContext(items = listOf(older, newer), now = now)
        val expression = ReferenceExpression(
            kind = ReferenceKind.THAT,
            rawText = "das Bild mit dem Hund",
            preferredKinds = setOf("image"),
            confidence = 0.9,
        )

        val resolved = resolver.resolve(expression, context)

        assertEquals(PhotonRevisionRef(id, 7), resolved.targetPhotonRef)
        assertNotEquals(PhotonRevisionRef(id, 2), resolved.targetPhotonRef)
        assertNotNull(resolved.revisionAlternatives)
    }

    private fun item(
        id: PhotonId,
        revision: Long,
        terms: Set<String>,
        active: Boolean,
    ) = LanguageContextItem(
        photonId = id,
        kind = "image",
        tags = setOf("image"),
        createdAt = now.minusSeconds(revision),
        active = active,
        contentTerms = terms,
        normalizedTerms = terms,
        confidence = 1.0,
        revisionRef = PhotonRevisionRef(id, revision),
        semanticTypes = setOf("image"),
    )
}
