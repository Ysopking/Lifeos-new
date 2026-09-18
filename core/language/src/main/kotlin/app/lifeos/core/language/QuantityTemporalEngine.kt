package app.lifeos.core.language

import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.Currency
import java.util.Locale

enum class QuantityComparator {
    EQUAL,
    GREATER_THAN,
    GREATER_OR_EQUAL,
    LESS_THAN,
    LESS_OR_EQUAL,
    RANGE_INCLUSIVE,
}

data class SemanticQuantityV2(
    val value: BigDecimal?,
    val unit: String?,
    val currency: Currency?,
    val comparator: QuantityComparator,
    val lowerBound: BigDecimal?,
    val upperBound: BigDecimal?,
    val span: TextSpan,
    val confidence: Double,
) {
    init {
        require(value != null || lowerBound != null || upperBound != null)
        require(confidence.isFinite() && confidence in 0.0..1.0)
        if (comparator == QuantityComparator.RANGE_INCLUSIVE) {
            require(lowerBound != null && upperBound != null)
            require(lowerBound <= upperBound)
        }
    }
}

enum class TemporalRelation {
    AT,
    BEFORE_OR_AT,
    AFTER_OR_AT,
    WITHIN,
    SINCE,
    RANGE,
}

data class SemanticTemporalValue(
    val relation: TemporalRelation,
    val startInclusive: Instant?,
    val endInclusive: Instant?,
    val sourceText: String,
    val span: TextSpan,
    val confidence: Double,
) {
    init {
        require(startInclusive != null || endInclusive != null)
        require(sourceText.isNotBlank())
        require(confidence.isFinite() && confidence in 0.0..1.0)
        if (startInclusive != null && endInclusive != null) {
            require(!endInclusive.isBefore(startInclusive))
        }
    }
}

data class QuantityTemporalResult(
    val quantities: List<SemanticQuantityV2>,
    val temporals: List<SemanticTemporalValue>,
)

class QuantityTemporalEngine {
    fun parse(
        utterance: NormalizedUtterance,
        referenceInstant: Instant,
        zoneId: ZoneId,
    ): QuantityTemporalResult {
        val quantities = buildList {
            addAll(parseRanges(utterance))
            addAll(parseScalarQuantities(utterance))
        }.distinctBy {
            listOf(
                it.span.start.toString(),
                it.span.endExclusive.toString(),
                it.comparator.name,
                it.value?.toPlainString().orEmpty(),
                it.lowerBound?.toPlainString().orEmpty(),
                it.upperBound?.toPlainString().orEmpty(),
                it.unit.orEmpty(),
                it.currency?.currencyCode.orEmpty(),
            )
        }.sortedBy { it.span.start }

        val temporals = parseTemporals(utterance, referenceInstant, zoneId)
            .distinctBy { Triple(it.span.start, it.span.endExclusive, it.relation) }
            .sortedBy { it.span.start }

        return QuantityTemporalResult(quantities, temporals)
    }

    private fun parseRanges(utterance: NormalizedUtterance): List<SemanticQuantityV2> =
        RANGE_REGEX.findAll(utterance.original).mapNotNull { match ->
            val lower = decimal(match.groupValues[1]) ?: return@mapNotNull null
            val upper = decimal(match.groupValues[2]) ?: return@mapNotNull null
            val rawUnit = match.groupValues.getOrNull(3).orEmpty().ifBlank { null }
            val (unit, currency) = normalizeUnit(rawUnit)
            SemanticQuantityV2(
                value = null,
                unit = unit,
                currency = currency,
                comparator = QuantityComparator.RANGE_INCLUSIVE,
                lowerBound = minOf(lower, upper),
                upperBound = maxOf(lower, upper),
                span = TextSpan(match.range.first, match.range.last + 1),
                confidence = 0.995,
            )
        }.toList()

    private fun parseScalarQuantities(utterance: NormalizedUtterance): List<SemanticQuantityV2> =
        SCALAR_REGEX.findAll(utterance.original).mapNotNull { match ->
            // Range matches are authority for their complete span.
            if (RANGE_REGEX.findAll(utterance.original).any { range ->
                    match.range.first >= range.range.first && match.range.last <= range.range.last
                }) return@mapNotNull null

            val cue = match.groupValues[1].lowercase(Locale.ROOT).trim()
            val amount = decimal(match.groupValues[2]) ?: return@mapNotNull null
            val rawUnit = match.groupValues.getOrNull(3).orEmpty().ifBlank { null }
            val (unit, currency) = normalizeUnit(rawUnit)
            SemanticQuantityV2(
                value = amount,
                unit = unit,
                currency = currency,
                comparator = comparator(cue),
                lowerBound = null,
                upperBound = null,
                span = TextSpan(match.range.first, match.range.last + 1),
                confidence = if (cue.isBlank()) 0.96 else 0.99,
            )
        }.toList()

    private fun parseTemporals(
        utterance: NormalizedUtterance,
        referenceInstant: Instant,
        zoneId: ZoneId,
    ): List<SemanticTemporalValue> {
        val original = utterance.original
        val baseDate = referenceInstant.atZone(zoneId).toLocalDate()
        val result = mutableListOf<SemanticTemporalValue>()

        RELATIVE_DAY_REGEX.findAll(original).forEach { match ->
            val word = match.value.lowercase(Locale.ROOT)
            val date = when (word) {
                "heute", "today" -> baseDate
                "morgen", "tomorrow" -> baseDate.plusDays(1)
                "übermorgen", "uebermorgen" -> baseDate.plusDays(2)
                "gestern", "yesterday" -> baseDate.minusDays(1)
                else -> return@forEach
            }
            result += dayValue(
                relation = if (word in setOf("gestern", "yesterday")) TemporalRelation.SINCE else TemporalRelation.AT,
                date = date,
                zoneId = zoneId,
                source = match.value,
                span = TextSpan(match.range.first, match.range.last + 1),
                confidence = 0.995,
            )
        }

        AT_DATE_REGEX.findAll(original).forEach { match ->
            val date = explicitGermanDate(
                dayRaw = match.groupValues[1],
                monthRaw = match.groupValues[2],
                yearRaw = match.groupValues.getOrNull(3).orEmpty(),
                baseDate = baseDate,
            ) ?: return@forEach
            result += dayValue(
                TemporalRelation.AT,
                date,
                zoneId,
                match.value,
                TextSpan(match.range.first, match.range.last + 1),
                0.995,
            )
        }

        NEXT_WEEKDAY_REGEX.findAll(original).forEach { match ->
            val day = weekday(match.groupValues[1]) ?: return@forEach
            val date = baseDate.with(TemporalAdjusters.next(day))
            result += dayValue(
                TemporalRelation.AT,
                date,
                zoneId,
                match.value,
                TextSpan(match.range.first, match.range.last + 1),
                0.98,
            )
        }

        IN_WEEKS_REGEX.findAll(original).forEach { match ->
            val count = number(match.groupValues[1]) ?: return@forEach
            val target = referenceInstant.atZone(zoneId).plusWeeks(count.toLong()).toInstant()
            result += SemanticTemporalValue(
                relation = TemporalRelation.AT,
                startInclusive = target,
                endInclusive = target,
                sourceText = match.value,
                span = TextSpan(match.range.first, match.range.last + 1),
                confidence = 0.96,
            )
        }

        WITHIN_DAYS_REGEX.findAll(original).forEach { match ->
            val count = number(match.groupValues[1]) ?: return@forEach
            result += SemanticTemporalValue(
                relation = TemporalRelation.WITHIN,
                startInclusive = referenceInstant,
                endInclusive = referenceInstant.atZone(zoneId).plusDays(count.toLong()).toInstant(),
                sourceText = match.value,
                span = TextSpan(match.range.first, match.range.last + 1),
                confidence = 0.99,
            )
        }

        UNTIL_DATE_REGEX.findAll(original).forEach { match ->
            val date = explicitGermanDate(
                dayRaw = match.groupValues[1],
                monthRaw = match.groupValues[2],
                yearRaw = match.groupValues.getOrNull(3).orEmpty(),
                baseDate = baseDate,
            ) ?: return@forEach
            result += SemanticTemporalValue(
                relation = TemporalRelation.BEFORE_OR_AT,
                startInclusive = null,
                endInclusive = date.atTime(LocalTime.MAX).atZone(zoneId).toInstant(),
                sourceText = match.value,
                span = TextSpan(match.range.first, match.range.last + 1),
                confidence = 0.995,
            )
        }

        AT_WEEKDAY_REGEX.findAll(original).forEach { match ->
            val day = weekday(match.groupValues[1]) ?: return@forEach
            val date = baseDate.with(TemporalAdjusters.nextOrSame(day))
            result += dayValue(
                TemporalRelation.AT,
                date,
                zoneId,
                match.value,
                TextSpan(match.range.first, match.range.last + 1),
                0.97,
            )
        }

        WEEKDAY_RANGE_REGEX.findAll(original).forEach { match ->
            val firstDay = weekday(match.groupValues[1]) ?: return@forEach
            val secondDay = weekday(match.groupValues[2]) ?: return@forEach
            val startDate = baseDate.with(TemporalAdjusters.nextOrSame(firstDay))
            var endDate = startDate.with(TemporalAdjusters.nextOrSame(secondDay))
            if (endDate.isBefore(startDate)) endDate = endDate.plusWeeks(1)
            result += SemanticTemporalValue(
                relation = TemporalRelation.RANGE,
                startInclusive = startDate.atStartOfDay(zoneId).toInstant(),
                endInclusive = endDate.atTime(LocalTime.MAX).atZone(zoneId).toInstant(),
                sourceText = match.value,
                span = TextSpan(match.range.first, match.range.last + 1),
                confidence = 0.97,
            )
        }

        return result
    }

    private fun dayValue(
        relation: TemporalRelation,
        date: LocalDate,
        zoneId: ZoneId,
        source: String,
        span: TextSpan,
        confidence: Double,
    ): SemanticTemporalValue = SemanticTemporalValue(
        relation = relation,
        startInclusive = date.atStartOfDay(zoneId).toInstant(),
        endInclusive = date.atTime(LocalTime.MAX).atZone(zoneId).toInstant(),
        sourceText = source,
        span = span,
        confidence = confidence,
    )

    private fun comparator(cue: String): QuantityComparator = when (cue) {
        "mindestens", "at least" -> QuantityComparator.GREATER_OR_EQUAL
        "mehr als", "über", "ueber", "more than" -> QuantityComparator.GREATER_THAN
        "höchstens", "hoechstens", "nicht mehr als", "not more than", "at most" ->
            QuantityComparator.LESS_OR_EQUAL
        "weniger als", "unter", "less than" -> QuantityComparator.LESS_THAN
        else -> QuantityComparator.EQUAL
    }

    private fun normalizeUnit(raw: String?): Pair<String?, Currency?> {
        if (raw.isNullOrBlank()) return null to null
        val normalized = raw.lowercase(Locale.ROOT).trim()
        val currencyCode = when (normalized) {
            "€", "eur", "euro", "euros" -> "EUR"
            "$", "usd", "dollar", "dollars" -> "USD"
            "£", "gbp", "pound", "pounds" -> "GBP"
            else -> null
        }
        if (currencyCode != null) {
            return currencyCode to Currency.getInstance(currencyCode)
        }
        val unit = when (normalized) {
            "mb", "megabyte", "megabytes" -> "MB"
            "gb", "gigabyte", "gigabytes" -> "GB"
            "kb", "kilobyte", "kilobytes" -> "KB"
            "%", "prozent", "percent" -> "%"
            else -> normalized.uppercase(Locale.ROOT)
        }
        return unit to null
    }

    private fun explicitGermanDate(
        dayRaw: String,
        monthRaw: String,
        yearRaw: String,
        baseDate: LocalDate,
    ): LocalDate? {
        val day = dayRaw.toIntOrNull() ?: return null
        val month = MONTHS[monthRaw.lowercase(Locale.ROOT)] ?: return null
        var year = yearRaw.toIntOrNull() ?: baseDate.year
        var candidate = runCatching { LocalDate.of(year, month, day) }.getOrNull() ?: return null
        if (yearRaw.isBlank() && candidate.isBefore(baseDate)) {
            year += 1
            candidate = runCatching { LocalDate.of(year, month, day) }.getOrNull() ?: return null
        }
        return candidate
    }

    private fun decimal(raw: String): BigDecimal? = runCatching {
        val normalized = when {
            ',' in raw && '.' in raw -> {
                if (raw.lastIndexOf(',') > raw.lastIndexOf('.')) {
                    raw.replace(".", "").replace(',', '.')
                } else {
                    raw.replace(",", "")
                }
            }
            ',' in raw -> raw.replace(',', '.')
            else -> raw
        }
        normalized.toBigDecimal().stripTrailingZeros()
    }.getOrNull()

    private fun number(raw: String): Int? =
        raw.toIntOrNull() ?: NUMBER_WORDS[raw.lowercase(Locale.ROOT)]

    private fun weekday(raw: String): DayOfWeek? = WEEKDAYS[raw.lowercase(Locale.ROOT)]

    private companion object {
        val RANGE_REGEX = Regex(
            """(?i)\bzwischen\s+(\d+(?:[.,]\d+)?)\s+(?:und|bis)\s+(\d+(?:[.,]\d+)?)\s*([\p{L}%€$£]+)?"""
        )
        val SCALAR_REGEX = Regex(
            """(?i)(?:(nicht\s+mehr\s+als|not\s+more\s+than|mindestens|mehr\s+als|über|ueber|höchstens|hoechstens|weniger\s+als|unter|at\s+least|more\s+than|at\s+most|less\s+than)\s+)?(\d+(?:[.,]\d+)?)\s*([\p{L}%€$£]+)?"""
        )
        val RELATIVE_DAY_REGEX = Regex("""(?i)\b(übermorgen|uebermorgen|morgen|heute|gestern|tomorrow|today|yesterday)\b""")
        val AT_DATE_REGEX = Regex(
            """(?i)\b(?:am|on)\s+(\d{1,2})\.?\s+(januar|februar|märz|maerz|april|mai|juni|juli|august|september|oktober|november|dezember)(?:\s+(\d{4}))?\b"""
        )
        val NEXT_WEEKDAY_REGEX = Regex(
            """(?i)\b(?:nächsten|naechsten|kommenden|next)\s+(montag|dienstag|mittwoch|donnerstag|freitag|samstag|sonntag|monday|tuesday|wednesday|thursday|friday|saturday|sunday)\b"""
        )
        val IN_WEEKS_REGEX = Regex("""(?i)\bin\s+(\d+|ein|einer|zwei|drei|vier|fünf|fuenf)\s+wochen?\b""")
        val WITHIN_DAYS_REGEX = Regex("""(?i)\binnerhalb\s+von\s+(\d+|ein|einem|zwei|drei|vier|fünf|fuenf)\s+tagen?\b""")
        val UNTIL_DATE_REGEX = Regex(
            """(?i)\bbis\s+(\d{1,2})\.?\s+(januar|februar|märz|maerz|april|mai|juni|juli|august|september|oktober|november|dezember)(?:\s+(\d{4}))?\b"""
        )
        val AT_WEEKDAY_REGEX = Regex(
            """(?i)\b(?:am|on)\s+(montag|dienstag|mittwoch|donnerstag|freitag|samstag|sonntag|monday|tuesday|wednesday|thursday|friday|saturday|sunday)\b"""
        )
        val WEEKDAY_RANGE_REGEX = Regex(
            """(?i)\bzwischen\s+(montag|dienstag|mittwoch|donnerstag|freitag|samstag|sonntag)\s+und\s+(montag|dienstag|mittwoch|donnerstag|freitag|samstag|sonntag)\b"""
        )

        val MONTHS = mapOf(
            "januar" to 1, "februar" to 2, "märz" to 3, "maerz" to 3, "april" to 4,
            "mai" to 5, "juni" to 6, "juli" to 7, "august" to 8, "september" to 9,
            "oktober" to 10, "november" to 11, "dezember" to 12,
        )
        val WEEKDAYS = mapOf(
            "montag" to DayOfWeek.MONDAY, "monday" to DayOfWeek.MONDAY,
            "dienstag" to DayOfWeek.TUESDAY, "tuesday" to DayOfWeek.TUESDAY,
            "mittwoch" to DayOfWeek.WEDNESDAY, "wednesday" to DayOfWeek.WEDNESDAY,
            "donnerstag" to DayOfWeek.THURSDAY, "thursday" to DayOfWeek.THURSDAY,
            "freitag" to DayOfWeek.FRIDAY, "friday" to DayOfWeek.FRIDAY,
            "samstag" to DayOfWeek.SATURDAY, "saturday" to DayOfWeek.SATURDAY,
            "sonntag" to DayOfWeek.SUNDAY, "sunday" to DayOfWeek.SUNDAY,
        )
        val NUMBER_WORDS = mapOf(
            "ein" to 1, "einer" to 1, "einem" to 1, "zwei" to 2, "drei" to 3,
            "vier" to 4, "fünf" to 5, "fuenf" to 5,
        )
    }
}
