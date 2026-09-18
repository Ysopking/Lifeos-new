package app.lifeos.core.language

import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class QuantityTemporalEngineTest {
    private val normalizer = UtteranceNormalizer()
    private val engine = QuantityTemporalEngine()
    private val now = Instant.parse("2026-09-18T12:00:00Z")
    private val zone = ZoneId.of("Europe/Berlin")

    @Test
    fun `parses comparator currency and inclusive range`() {
        val bounded = parse("höchstens 312 Euro")
        val range = parse("zwischen 10 und 20 Euro")

        val amount = bounded.quantities.single { it.currency?.currencyCode == "EUR" }
        assertEquals(BigDecimal("312"), amount.value)
        assertEquals(QuantityComparator.LESS_OR_EQUAL, amount.comparator)

        val interval = range.quantities.single { it.comparator == QuantityComparator.RANGE_INCLUSIVE }
        assertEquals(BigDecimal("10"), interval.lowerBound)
        assertEquals(BigDecimal("20"), interval.upperBound)
        assertEquals("EUR", interval.currency?.currencyCode)
    }

    @Test
    fun `not more than remains a bounded quantity`() {
        val result = parse("nicht mehr als 100 Euro")
        val amount = assertNotNull(result.quantities.firstOrNull {
            it.currency?.currencyCode == "EUR"
        })

        assertEquals(BigDecimal("100"), amount.value)
        assertEquals(QuantityComparator.LESS_OR_EQUAL, amount.comparator)
    }

    @Test
    fun `relative and explicit dates are canonicalized from reference instant`() {
        val result = parse("morgen, am Freitag und bis 4. Oktober")

        assertTrue(result.temporals.any { it.sourceText.lowercase().contains("morgen") })
        assertTrue(result.temporals.any { it.sourceText.lowercase().contains("freitag") })
        val deadline = assertNotNull(result.temporals.firstOrNull {
            it.relation == TemporalRelation.BEFORE_OR_AT
        })
        assertTrue(deadline.endInclusive.toString().startsWith("2026-10-04"))
    }

    @Test
    fun `binds relative date and clock time into one canonical date time`() {
        val result = parse("morgen um 14:30 Uhr")
        val dateTime = result.dateTimes.single()

        assertTrue(dateTime.sourceText.lowercase().contains("morgen"))
        assertTrue(dateTime.sourceText.contains("14:30"))
        assertEquals("Europe/Berlin", dateTime.zoneId)
        assertEquals("2026-09-19T12:30:00Z", dateTime.instant.toString())
    }

    @Test
    fun `clock time is temporal evidence and never a scalar quantity`() {
        val result = parse("morgen um 14:30 Uhr")

        assertTrue(result.dateTimes.isNotEmpty())
        assertTrue(result.quantities.isEmpty())
    }

    @Test
    fun `within duration creates bounded temporal interval`() {
        val result = parse("innerhalb von 14 Tagen")
        val within = result.temporals.single { it.relation == TemporalRelation.WITHIN }

        assertEquals(now, within.startInclusive)
        assertTrue(requireNotNull(within.endInclusive).isAfter(now))
    }

    private fun parse(text: String): QuantityTemporalResult =
        engine.parse(normalizer.normalize(text), now, zone)
}
