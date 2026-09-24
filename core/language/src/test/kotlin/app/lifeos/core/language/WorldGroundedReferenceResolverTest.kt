package app.lifeos.core.language

import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonRevisionRef
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorldGroundedReferenceResolverTest {
    private val now = Instant.parse("2026-09-24T12:00:00Z")
    private val photonId = PhotonId("reference-target")
    private val ref = PhotonRevisionRef(photonId, 7L)
    private val expression = ReferenceExpression(
        kind = ReferenceKind.THAT,
        rawText = "that",
        preferredKinds = setOf("file"),
        confidence = 0.9,
    )

    @Test
    fun exactRevisionInParticipatingContextIsGrounded() {
        val resolved = ResolvedReference(
            expression = expression,
            targetPhotonId = photonId,
            score = 0.90,
            targetPhotonRef = ref,
        )
        val state = WorldGroundedReferenceResolver().ground(
            listOf(resolved),
            context(ref),
        )

        val grounded = state.references.single()
        assertEquals(LanguageReferenceGroundingStatus.EXACT_REVISION, grounded.status)
        assertTrue(grounded.exactWorldReference)
        assertEquals(setOf(ref), state.exactRevisionRefs)
        assertFalse(state.directWorldStateMutationAllowed)
        assertFalse(state.executionAuthority)
    }

    @Test
    fun samePhotonDifferentRevisionIsExplicitlyStale() {
        val resolved = ResolvedReference(
            expression = expression,
            targetPhotonId = photonId,
            score = 0.90,
            targetPhotonRef = ref,
        )
        val staleContext = context(
            PhotonRevisionRef(photonId, 8L)
        )

        val grounded = WorldGroundedReferenceResolver()
            .ground(listOf(resolved), staleContext)
            .references
            .single()

        assertEquals(LanguageReferenceGroundingStatus.STALE_REVISION, grounded.status)
        assertFalse(grounded.exactWorldReference)
    }

    @Test
    fun legacyIdOnlyReferenceNeverPretendsExactRevisionGrounding() {
        val resolved = ResolvedReference(
            expression = expression,
            targetPhotonId = photonId,
            score = 0.90,
            targetPhotonRef = null,
        )

        val grounded = WorldGroundedReferenceResolver()
            .ground(listOf(resolved), context(ref))
            .references
            .single()

        assertEquals(LanguageReferenceGroundingStatus.LEGACY_ID_ONLY, grounded.status)
        assertFalse(grounded.exactWorldReference)
    }

    @Test
    fun closeRunnerUpRemainsAmbiguousEvenWithExactContextRevision() {
        val resolved = ResolvedReference(
            expression = expression,
            targetPhotonId = photonId,
            score = 0.70,
            targetPhotonRef = ref,
            revisionAlternatives = listOf(
                PhotonRevisionRef(PhotonId("other-target"), 3L) to 0.66
            ),
        )

        val grounded = WorldGroundedReferenceResolver()
            .ground(listOf(resolved), context(ref))
            .references
            .single()

        assertEquals(LanguageReferenceGroundingStatus.AMBIGUOUS, grounded.status)
    }

    @Test
    fun languageUnderstandingPublishesGroundingState() {
        val result = LanguageUnderstandingEngine().understand(
            "Kannst du das genauer erklären?",
            LanguageContext(
                items = listOf(contextItem(ref)),
                now = now,
            ),
        )

        assertTrue(result.goal.referenceGrounding.references.isNotEmpty())
        assertFalse(result.goal.referenceGrounding.executionAuthority)
    }

    private fun context(
        revisionRef: PhotonRevisionRef,
    ): LanguageContext = LanguageContext(
        items = listOf(contextItem(revisionRef)),
        now = now,
    )

    private fun contextItem(
        revisionRef: PhotonRevisionRef,
    ) = LanguageContextItem(
        photonId = revisionRef.photonId,
        kind = "file",
        tags = setOf("file"),
        createdAt = now.minusSeconds(30),
        active = true,
        contentTerms = setOf("details"),
        confidence = 1.0,
        revisionRef = revisionRef,
        semanticTypes = setOf("file"),
        normalizedTerms = setOf("details"),
    )
}
