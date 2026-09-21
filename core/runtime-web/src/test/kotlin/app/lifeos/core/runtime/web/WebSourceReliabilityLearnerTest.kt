package app.lifeos.core.runtime.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WebSourceReliabilityLearnerTest {
    @Test
    fun `verified supported and contradicted observations update only candidate reliability`() {
        val resource = WebResourceIdentity.parse("https://example.com/a")
        val observations = listOf(
            observation(resource, "claim-a", WebReliabilityVerdict.SUPPORTED, true, 1.0, "evidence-a"),
            observation(resource, "claim-b", WebReliabilityVerdict.SUPPORTED, true, 0.5, "evidence-b"),
            observation(resource, "claim-c", WebReliabilityVerdict.CONTRADICTED, true, 1.0, "evidence-c"),
        )
        val profile = WebSourceReliabilityLearner().learn(
            origin = resource.origin,
            observations = observations,
            policy = WebSourceReliabilityPolicy(
                priorSupportedWeight = 1.0,
                priorContradictedWeight = 1.0,
                minimumVerifiedDirectionalObservations = 3,
            ),
        )

        assertEquals(3, profile.verifiedDirectionalObservations)
        assertEquals(1.5, profile.supportedWeight)
        assertEquals(1.0, profile.contradictedWeight)
        assertEquals(2.5 / 4.5, profile.candidateReliability)
        assertTrue(profile.evidenceSufficient)
        assertFalse(profile.trustAuthority)
        assertFalse(profile.routingAuthority)
        assertFalse(profile.promotionAllowed)
        assertFalse(profile.activationAllowed)
    }

    @Test
    fun `unverified observation cannot claim support or contradiction`() {
        val resource = WebResourceIdentity.parse("https://example.com/a")

        assertFailsWith<IllegalArgumentException> {
            WebReliabilityObservation.create(
                resource = resource,
                claimCandidateId = claimId("a"),
                sourceDocumentFingerprint = digest("doc"),
                verificationEvidenceFingerprint = digest("evidence"),
                verified = false,
                verdict = WebReliabilityVerdict.SUPPORTED,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            WebReliabilityObservation.create(
                resource = resource,
                claimCandidateId = claimId("b"),
                sourceDocumentFingerprint = digest("doc"),
                verificationEvidenceFingerprint = digest("evidence"),
                verified = false,
                verdict = WebReliabilityVerdict.CONTRADICTED,
            )
        }
    }

    @Test
    fun `verified or unverified indeterminate observations never change score`() {
        val resource = WebResourceIdentity.parse("https://example.com/a")
        val profile = WebSourceReliabilityLearner().learn(
            origin = resource.origin,
            observations = listOf(
                observation(resource, "claim-a", WebReliabilityVerdict.INDETERMINATE, false, 1.0, "evidence-a"),
                observation(resource, "claim-b", WebReliabilityVerdict.INDETERMINATE, true, 1.0, "evidence-b"),
            ),
        )

        assertEquals(0, profile.verifiedDirectionalObservations)
        assertEquals(0.0, profile.supportedWeight)
        assertEquals(0.0, profile.contradictedWeight)
        assertEquals(0.5, profile.candidateReliability)
        assertFalse(profile.evidenceSufficient)
    }

    @Test
    fun `cross-origin observation substitution fails closed`() {
        val first = WebResourceIdentity.parse("https://example.com/a")
        val second = WebResourceIdentity.parse("https://other.example/b")

        assertFailsWith<IllegalArgumentException> {
            WebSourceReliabilityLearner().learn(
                origin = first.origin,
                observations = listOf(
                    observation(second, "claim-a", WebReliabilityVerdict.SUPPORTED, true, 1.0, "evidence"),
                ),
            )
        }
    }

    @Test
    fun `duplicate observations fail closed instead of inflating support`() {
        val resource = WebResourceIdentity.parse("https://example.com/a")
        val observation = observation(
            resource,
            "claim-a",
            WebReliabilityVerdict.SUPPORTED,
            true,
            1.0,
            "evidence",
        )

        assertFailsWith<IllegalArgumentException> {
            WebSourceReliabilityLearner().learn(
                resource.origin,
                listOf(observation, observation),
            )
        }
    }

    @Test
    fun `observation ordering cannot change profile identity`() {
        val resource = WebResourceIdentity.parse("https://example.com/a")
        val firstObservation = observation(
            resource,
            "claim-a",
            WebReliabilityVerdict.SUPPORTED,
            true,
            1.0,
            "evidence-a",
        )
        val secondObservation = observation(
            resource,
            "claim-b",
            WebReliabilityVerdict.CONTRADICTED,
            true,
            1.0,
            "evidence-b",
        )
        val learner = WebSourceReliabilityLearner()

        val first = learner.learn(resource.origin, listOf(firstObservation, secondObservation))
        val second = learner.learn(resource.origin, listOf(secondObservation, firstObservation))

        assertEquals(first, second)
    }

    @Test
    fun `verification evidence fingerprint participates in learned profile identity`() {
        val resource = WebResourceIdentity.parse("https://example.com/a")
        val first = WebSourceReliabilityLearner().learn(
            resource.origin,
            listOf(
                observation(
                    resource,
                    "claim-a",
                    WebReliabilityVerdict.SUPPORTED,
                    true,
                    1.0,
                    "evidence-a",
                )
            ),
        )
        val second = WebSourceReliabilityLearner().learn(
            resource.origin,
            listOf(
                observation(
                    resource,
                    "claim-a",
                    WebReliabilityVerdict.SUPPORTED,
                    true,
                    1.0,
                    "evidence-b",
                )
            ),
        )

        assertEquals(first.candidateReliability, second.candidateReliability)
        assertNotEquals(first.observationFingerprints, second.observationFingerprints)
        assertNotEquals(first.fingerprint, second.fingerprint)
    }

    @Test
    fun `same origin across resources accumulates but another origin does not`() {
        val first = WebResourceIdentity.parse("https://example.com/a")
        val second = WebResourceIdentity.parse("https://example.com/b")
        val other = WebResourceIdentity.parse("https://other.example/a")

        val profile = WebSourceReliabilityLearner().learn(
            first.origin,
            listOf(
                observation(first, "claim-a", WebReliabilityVerdict.SUPPORTED, true, 1.0, "evidence-a"),
                observation(second, "claim-b", WebReliabilityVerdict.SUPPORTED, true, 1.0, "evidence-b"),
            ),
            WebSourceReliabilityPolicy(minimumVerifiedDirectionalObservations = 2),
        )
        assertTrue(profile.evidenceSufficient)
        assertTrue(profile.candidateReliability > 0.5)

        assertFailsWith<IllegalArgumentException> {
            WebSourceReliabilityLearner().learn(
                first.origin,
                listOf(
                    observation(first, "claim-a", WebReliabilityVerdict.SUPPORTED, true, 1.0, "evidence-a"),
                    observation(other, "claim-c", WebReliabilityVerdict.SUPPORTED, true, 1.0, "evidence-c"),
                ),
            )
        }
    }

    private fun observation(
        resource: WebResourceIdentity,
        claimKey: String,
        verdict: WebReliabilityVerdict,
        verified: Boolean,
        weight: Double,
        evidenceKey: String,
    ): WebReliabilityObservation = WebReliabilityObservation.create(
        resource = resource,
        claimCandidateId = claimId(claimKey),
        sourceDocumentFingerprint = digest("document:" + resource.id.value + ":" + claimKey),
        verificationEvidenceFingerprint = digest(evidenceKey),
        verified = verified,
        verdict = verdict,
        weight = weight,
    )

    private fun claimId(value: String): String =
        WebClaimCandidate.ID_PREFIX + digest("claim:" + value)

    private fun digest(value: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.encodeToByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
