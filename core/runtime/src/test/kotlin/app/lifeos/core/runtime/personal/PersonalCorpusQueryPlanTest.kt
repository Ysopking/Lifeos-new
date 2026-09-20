package app.lifeos.core.runtime.personal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PersonalCorpusQueryPlanTest {
    @Test
    fun `plan normalizes terms and binds owner constraints deterministically`() {
        val first = assertNotNull(
            PersonalCorpusQueryPlan.create(
                query = "Alpha alpha beta gamma",
                ownerOnly = true,
                maxTerms = 2,
                perTermLimit = 32,
                maxCandidates = 48,
            )
        )
        val replay = assertNotNull(
            PersonalCorpusQueryPlan.create(
                query = "alpha beta",
                ownerOnly = true,
                maxTerms = 2,
                perTermLimit = 32,
                maxCandidates = 48,
            )
        )
        val nonOwner = assertNotNull(
            PersonalCorpusQueryPlan.create(
                query = "alpha beta",
                ownerOnly = false,
                maxTerms = 2,
                perTermLimit = 32,
                maxCandidates = 48,
            )
        )

        assertEquals(listOf("alpha", "beta"), first.terms)
        assertEquals(setOf("corpus:archive", "speaker:owner"), first.requiredTags)
        assertEquals(setOf("corpus-term:alpha", "corpus-term:beta"), first.termTags)
        assertEquals(48, first.retrievalLimit)
        assertEquals(first.fingerprint, replay.fingerprint)
        assertNotEquals(first.fingerprint, nonOwner.fingerprint)
    }

    @Test
    fun `empty semantic query has no executable plan`() {
        assertNull(
            PersonalCorpusQueryPlan.create(
                query = "   ",
                ownerOnly = true,
                maxTerms = 6,
                perTermLimit = 32,
                maxCandidates = 96,
            )
        )
    }
}
