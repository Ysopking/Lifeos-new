package app.lifeos.core.runtime.reasoning

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class PredictiveIdentifiabilityAnalyzerTest {
    private val analyzer = PredictiveIdentifiabilityAnalyzer()

    @Test
    fun `different observational laws are already distinguishable`() {
        val result = analyzer.assess(
            listOf(
                candidate("a", observational = "law:1"),
                candidate("b", observational = "law:2"),
            )
        )

        assertEquals(IdentifiabilityStatus.OBSERVATIONALLY_DISTINCT, result.status)
        assertEquals(emptyList(), result.discriminatingInterventionIds)
    }

    @Test
    fun `same observational law can be separated by shared intervention prediction`() {
        val result = analyzer.assess(
            listOf(
                candidate(
                    "a",
                    interventions = mapOf(
                        "ask-owner" to "law:same",
                        "refresh-source" to "law:a",
                    ),
                ),
                candidate(
                    "b",
                    interventions = mapOf(
                        "ask-owner" to "law:same",
                        "refresh-source" to "law:b",
                    ),
                ),
            )
        )

        assertEquals(IdentifiabilityStatus.INTERVENTION_SEPARABLE, result.status)
        assertEquals(
            listOf("ask-owner", "refresh-source"),
            result.sharedInterventionIds,
        )
        assertEquals(
            listOf("refresh-source"),
            result.discriminatingInterventionIds,
        )
    }

    @Test
    fun `same laws under tested shared interventions remain currently inseparable`() {
        val result = analyzer.assess(
            listOf(
                candidate("a", interventions = mapOf("probe" to "same")),
                candidate("b", interventions = mapOf("probe" to "same")),
            )
        )

        assertEquals(IdentifiabilityStatus.CURRENTLY_INSEPARABLE, result.status)
        assertEquals(listOf("probe"), result.sharedInterventionIds)
    }

    @Test
    fun `partial intervention coverage remains unresolved`() {
        val result = analyzer.assess(
            listOf(
                candidate("a", interventions = mapOf("probe-a" to "law:a")),
                candidate("b", interventions = mapOf("probe-b" to "law:b")),
            )
        )

        assertEquals(IdentifiabilityStatus.UNRESOLVED, result.status)
        assertEquals(emptyList(), result.sharedInterventionIds)
    }

    @Test
    fun `no intervention predictions remain unresolved`() {
        val result = analyzer.assess(
            listOf(candidate("a"), candidate("b"))
        )

        assertEquals(IdentifiabilityStatus.UNRESOLVED, result.status)
    }

    @Test
    fun `candidate order does not change assessment`() {
        val a = candidate("a", interventions = mapOf("probe" to "law:a"))
        val b = candidate("b", interventions = mapOf("probe" to "law:b"))

        assertEquals(
            analyzer.assess(listOf(a, b)),
            analyzer.assess(listOf(b, a)),
        )
    }

    @Test
    fun `duplicate candidate ids are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            analyzer.assess(listOf(candidate("a"), candidate("a")))
        }
    }

    @Test
    fun `cross-profile candidate comparison is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            analyzer.assess(
                listOf(
                    candidate("a"),
                    PredictiveCandidateSignature.create(
                        realizationProfileFingerprint = "profile:other",
                        candidateId = "b",
                        observationalLawFingerprint = "observed:same",
                    ),
                )
            )
        }
    }

    @Test
    fun `identifiability evidence grants neither causal nor execution authority`() {
        val result = analyzer.assess(
            listOf(
                candidate("a", observational = "law:1"),
                candidate("b", observational = "law:2"),
            )
        )

        assertFalse(result.causalAuthority)
        assertFalse(result.executionAuthority)
    }

    private fun candidate(
        id: String,
        observational: String = "observed:same",
        interventions: Map<String, String> = emptyMap(),
    ) = PredictiveCandidateSignature.create(
        realizationProfileFingerprint = "profile:v2.2",
        candidateId = id,
        observationalLawFingerprint = observational,
        interventionalLawFingerprints = interventions,
    )
}
