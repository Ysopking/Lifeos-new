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

data class SemanticDateTimeValue(
    val instant: Instant,
    val zoneId: String,
    val sourceText: String,
    val span: TextSpan,
    val dateSpan: TextSpan?,
    val timeSpan: TextSpan,
    val confidence: Double,
) {
    init {
        require(zoneId.isNotBlank())
        require(sourceText.isNotBlank())
        require(confidence.isFinite() && confidence in 0.0..1.0)
        require(span.contains(timeSpan))
        require(dateSpan == null || span.contains(dateSpan))
    }
}

data class QuantityTemporalResult(
    val quantities: List<SemanticQuantityV2>,
    val temporals: List<SemanticTemporalValue>,
    val dateTimes: List<SemanticDateTimeValue> = emptyList(),
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
            addAll(parseWordQuantities(utterance))
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
        val dateTimes = bindDateTimes(
            utterance = utterance,
            temporals = temporals,
            referenceInstant = referenceInstant,
            zoneId = zoneId,
        )
        return QuantityTemporalResult(quantities, temporals, dateTimes)
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
            // Range and temporal matches are authority for their complete span.
            if (RANGE_REGEX.findAll(utterance.original).any { range ->
                    match.range.first >= range.range.first && match.range.last <= range.range.last
                }) return@mapNotNull null
            if (numericSpanBelongsToTemporal(utterance.original, match.range.first, match.range.last + 1)) {
                return@mapNotNull null
            }

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

    private fun parseWordQuantities(utterance: NormalizedUtterance): List<SemanticQuantityV2> =
        utterance.tokens.withIndex().mapNotNull { indexed ->
            val amount = NUMBER_WORD_DECIMALS[indexed.value.normalized] ?: return@mapNotNull null
            val unitToken = utterance.tokens.getOrNull(indexed.index + 1)
                ?.takeIf { it.kind == TokenKind.WORD || it.kind == TokenKind.PUNCTUATION }
                ?: return@mapNotNull null
            val (unit, currency) = normalizeUnit(unitToken.normalized)
            if (unit == null && currency == null) return@mapNotNull null
            SemanticQuantityV2(
                value = amount,
                unit = unit,
                currency = currency,
                comparator = QuantityComparator.EQUAL,
                lowerBound = null,
                upperBound = null,
                span = TextSpan(indexed.value.start, unitToken.endExclusive),
                confidence = 0.95,
            )
        }

    private fun numericSpanBelongsToTemporal(
        text: String,
        start: Int,
        endExclusive: Int,
    ): Boolean {
        val temporalRegexes = listOf(
            CLOCK_REGEX,
            AT_DATE_REGEX,
            UNTIL_DATE_REGEX,
        )
        return temporalRegexes.any { regex ->
            regex.findAll(text).any { match ->
                start >= match.range.first && endExclusive <= match.range.last + 1
            }
        }
    }

    private fun bindDateTimes(
        utterance: NormalizedUtterance,
        temporals: List<SemanticTemporalValue>,
        referenceInstant: Instant,
        zoneId: ZoneId,
    ): List<SemanticDateTimeValue> {
        val baseDate = referenceInstant.atZone(zoneId).toLocalDate()
        return CLOCK_REGEX.findAll(utterance.original).mapNotNull { match ->
            var hour = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val minute = match.groupValues.getOrNull(2).orEmpty().ifBlank { "0" }.toIntOrNull()
                ?: return@mapNotNull null
            val meridiem = match.groupValues.getOrNull(3).orEmpty().lowercase(Locale.ROOT)
            if (meridiem == "pm" && hour in 1..11) hour += 12
            if (meridiem == "am" && hour == 12) hour = 0
            val time = runCatching { LocalTime.of(hour, minute) }.getOrNull() ?: return@mapNotNull null
            val timeSpan = TextSpan(match.range.first, match.range.last + 1)
            val nearestDate = temporals
                .asSequence()
                .filter { it.relation == TemporalRelation.AT && it.startInclusive != null }
                .map { temporal ->
                    val distance = when {
                        temporal.span.endExclusive < timeSpan.start -> timeSpan.start - temporal.span.endExclusive
                        timeSpan.endExclusive < temporal.span.start -> temporal.span.start - timeSpan.endExclusive
                        else -> 0
                    }
                    temporal to distance
                }
                .filter { it.second <= MAX_DATE_TIME_BINDING_DISTANCE }
                .minWithOrNull(
                    compareBy<Pair<SemanticTemporalValue, Int>> { it.second }
                        .thenBy { it.first.span.start }
                )
                ?.first
            val date = nearestDate?.startInclusive?.atZone(zoneId)?.toLocalDate() ?: baseDate
            val dateSpan = nearestDate?.span
            val combinedStart = minOf(dateSpan?.start ?: timeSpan.start, timeSpan.start)
            val combinedEnd = maxOf(dateSpan?.endExclusive ?: timeSpan.endExclusive, timeSpan.endExclusive)
            SemanticDateTimeValue(
                instant = LocalDateTime.of(date, time).atZone(zoneId).toInstant(),
                zoneId = zoneId.id,
                sourceText = utterance.original.substring(combinedStart, combinedEnd),
                span = TextSpan(combinedStart, combinedEnd),
                dateSpan = dateSpan,
                timeSpan = timeSpan,
                confidence = if (nearestDate != null) minOf(0.995, nearestDate.confidence) else 0.94,
            )
        }.distinctBy { listOf(it.instant.toString(), it.zoneId, it.span.start, it.span.endExclusive) }
            .sortedBy { it.span.start }
    }

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
            "b", "byte", "bytes" -> "B"
            "kb", "kilobyte", "kilobytes" -> "KB"
            "mb", "megabyte", "megabytes" -> "MB"
            "gb", "gigabyte", "gigabytes" -> "GB"
            "tb", "terabyte", "terabytes" -> "TB"
            "ms", "millisekunde", "millisekunden", "millisecond", "milliseconds" -> "ms"
            "s", "sekunde", "sekunden", "second", "seconds" -> "s"
            "min", "minute", "minuten", "minutes" -> "min"
            "h", "stunde", "stunden", "hour", "hours" -> "h"
            "g", "gramm", "gram", "grams" -> "g"
            "kg", "kilogramm", "kilogram", "kilograms" -> "kg"
            "mm", "millimeter" -> "mm"
            "cm", "centimeter", "zentimeter" -> "cm"
            "m", "meter", "metre", "meters", "metres" -> "m"
            "km", "kilometer", "kilometre", "kilometers", "kilometres" -> "km"
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
        normalized.toBigDecimal()
    }.getOrNull()

    private fun number(raw: String): Int? =
        raw.toIntOrNull() ?: NUMBER_WORDS[raw.lowercase(Locale.ROOT)]

    private fun weekday(raw: String): DayOfWeek? = WEEKDAYS[raw.lowercase(Locale.ROOT)]

    private companion object {
        const val MAX_DATE_TIME_BINDING_DISTANCE = 48
        val RANGE_REGEX = Regex(
            """(?i)\bzwischen\s+(\d+(?:[.,]\d+)?)\s+(?:und|bis)\s+(\d+(?:[.,]\d+)?)\s*([\p{L}%€$£]+)?"""
        )
        val SCALAR_REGEX = Regex(
            """(?i)(?:(nicht\s+mehr\s+als|not\s+more\s+than|mindestens|mehr\s+als|über|ueber|höchstens|hoechstens|weniger\s+als|unter|at\s+least|more\s+than|at\s+most|less\s+than)\s+)?(\d+(?:[.,]\d+)?)\s*([\p{L}%€$£]+)?"""
        )
        val CLOCK_REGEX = Regex(
            """(?i)\b(?:um|at)\s+(\d{1,2})(?::(\d{2}))?\s*(?:uhr\b|(am|pm)\b)?"""
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
        val NUMBER_WORD_DECIMALS = mapOf(
            "null" to BigDecimal.ZERO, "zero" to BigDecimal.ZERO,
            "ein" to BigDecimal.ONE, "eins" to BigDecimal.ONE, "eine" to BigDecimal.ONE, "one" to BigDecimal.ONE,
            "zwei" to BigDecimal("2"), "two" to BigDecimal("2"),
            "drei" to BigDecimal("3"), "three" to BigDecimal("3"),
            "vier" to BigDecimal("4"), "four" to BigDecimal("4"),
            "fünf" to BigDecimal("5"), "fuenf" to BigDecimal("5"), "five" to BigDecimal("5"),
            "sechs" to BigDecimal("6"), "six" to BigDecimal("6"),
            "sieben" to BigDecimal("7"), "seven" to BigDecimal("7"),
            "acht" to BigDecimal("8"), "eight" to BigDecimal("8"),
            "neun" to BigDecimal("9"), "nine" to BigDecimal("9"),
            "zehn" to BigDecimal("10"), "ten" to BigDecimal("10"),
        )
        val NUMBER_WORDS = mapOf(
            "ein" to 1, "einer" to 1, "einem" to 1, "zwei" to 2, "drei" to 3,
            "vier" to 4, "fünf" to 5, "fuenf" to 5,
        )
    }
}
