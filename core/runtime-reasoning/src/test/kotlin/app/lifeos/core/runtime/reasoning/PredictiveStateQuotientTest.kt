package app.lifeos.core.runtime.reasoning

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class PredictiveStateQuotientTest {
    private val quotient = PredictiveStateQuotient()

    @Test
    fun `identical future laws merge histories into one exact predictive class`() {
        val law = law("safe" to 700_000L, "blocked" to 300_000L)

        val classes = quotient.exact(
            listOf(
                history("h2", law),
                history("h1", law),
            )
        )

        assertEquals(1, classes.size)
        assertEquals(listOf("h1", "h2"), classes.single().memberHistoryFingerprints)
        assertEquals(law, classes.single().futureLaw)
    }

    @Test
    fun `different future laws remain separate predictive classes`() {
        val first = law("safe" to 700_000L, "blocked" to 300_000L)
        val second = law("safe" to 400_000L, "blocked" to 600_000L)

        val classes = quotient.exact(
            listOf(
                history("h1", first),
                history("h2", second),
            )
        )

        assertEquals(2, classes.size)
        assertNotEquals(classes[0].id, classes[1].id)
    }

    @Test
    fun `predictive class identity is stable when additional equivalent histories arrive`() {
        val law = law("a" to 500_000L, "b" to 500_000L)
        val first = quotient.exact(listOf(history("h1", law))).single()
        val expanded = quotient.exact(
            listOf(
                history("h1", law),
                history("h2", law),
            )
        ).single()

        assertEquals(first.id, expanded.id)
        assertNotEquals(first.memberHistoryFingerprints, expanded.memberHistoryFingerprints)
    }

    @Test
    fun `outcome ordering cannot change future law identity`() {
        val first = law("a" to 250_000L, "b" to 750_000L)
        val second = DiscreteFutureLaw.create(
            linkedMapOf(
                "b" to 750_000L,
                "a" to 250_000L,
            )
        )

        assertEquals(first, second)
    }

    @Test
    fun `law must use complete fixed probability mass`() {
        assertFailsWith<IllegalArgumentException> {
            DiscreteFutureLaw.create(
                mapOf(
                    "a" to 500_000L,
                    "b" to 400_000L,
                )
            )
        }
    }

    @Test
    fun `zero mass outcomes are rejected to prevent representational ambiguity`() {
        assertFailsWith<IllegalArgumentException> {
            DiscreteFutureLaw.create(
                mapOf(
                    "a" to PREDICTIVE_PROBABILITY_SCALE,
                    "never" to 0L,
                )
            )
        }
    }

    @Test
    fun `duplicate history ids are rejected rather than silently fabricating quotient evidence`() {
        val law = law("a" to PREDICTIVE_PROBABILITY_SCALE)

        assertFailsWith<IllegalArgumentException> {
            quotient.exact(
                listOf(
                    history("h1", law),
                    history("h1", law),
                )
            )
        }
    }

    @Test
    fun `mixed frozen realization profiles cannot be quotiented together`() {
        val law = law("a" to PREDICTIVE_PROBABILITY_SCALE)

        assertFailsWith<IllegalArgumentException> {
            quotient.exact(
                listOf(
                    history("h1", law),
                    PredictiveHistory(
                        realizationProfileFingerprint = "profile:other",
                        historyFingerprint = "h2",
                        futureLaw = law,
                    ),
                )
            )
        }
    }

    @Test
    fun `probability law and quotient grant neither single-event truth nor execution authority`() {
        val law = law("a" to PREDICTIVE_PROBABILITY_SCALE)
        val state = quotient.exact(listOf(history("h1", law))).single()

        assertFalse(law.singleEventAuthority)
        assertFalse(state.truthAuthority)
        assertFalse(state.executionAuthority)
    }

    private fun history(
        id: String,
        law: DiscreteFutureLaw,
    ) = PredictiveHistory(
        realizationProfileFingerprint = "profile:v2.2",
        historyFingerprint = id,
        futureLaw = law,
    )

    private fun law(
        vararg probabilities: Pair<String, Long>,
    ): DiscreteFutureLaw =
        DiscreteFutureLaw.create(linkedMapOf(*probabilities))
}
