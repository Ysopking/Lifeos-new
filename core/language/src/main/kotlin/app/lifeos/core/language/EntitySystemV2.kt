package app.lifeos.core.language

import java.util.Locale

@JvmInline
value class SemanticEntityTypeId(val value: String) {
    init {
        require(value.matches(Regex("[a-z][a-z0-9_.-]*"))) {
            "Invalid semantic entity type id: $value"
        }
    }
    override fun toString(): String = value
}

data class SemanticEntityTypeDefinition(
    val id: SemanticEntityTypeId,
    val family: String,
    val legacyType: EntityType? = null,
)

object EntityTypeRegistry {
    private fun type(id: String, family: String, legacy: EntityType? = null) =
        SemanticEntityTypeDefinition(SemanticEntityTypeId(id), family, legacy)

    val PERSON = type("personal.person", "personal", EntityType.PERSON)
    val ORGANIZATION = type("personal.organization", "personal")
    val AUTHORITY = type("authority.authority", "authority")
    val LOCATION = type("spatial.location", "spatial", EntityType.LOCATION)
    val ADDRESS = type("spatial.address", "spatial")
    val EMAIL_ADDRESS = type("communication.email_address", "communication")
    val PHONE_NUMBER = type("communication.phone_number", "communication")
    val URL = type("communication.url", "communication")

    val DATE = type("temporal.date", "temporal", EntityType.DATE)
    val TIME = type("temporal.time", "temporal", EntityType.TIME)
    val DURATION = type("temporal.duration", "temporal", EntityType.DURATION)
    val TIME_RANGE = type("temporal.time_range", "temporal")
    val DEADLINE = type("temporal.deadline", "temporal")

    val NUMBER = type("quantity.number", "quantity", EntityType.NUMBER)
    val MONEY = type("finance.money", "finance")
    val CURRENCY = type("finance.currency", "finance")
    val PERCENTAGE = type("quantity.percentage", "quantity")
    val QUANTITY = type("quantity.quantity", "quantity")
    val UNIT = type("quantity.unit", "quantity")

    val FILE = type("documents.file", "documents", EntityType.FILE)
    val DOCUMENT = type("documents.document", "documents")
    val IMAGE = type("documents.image", "documents", EntityType.IMAGE)
    val COLOR = type("visual.color", "visual", EntityType.COLOR)
    val OBJECT = type("core.object", "core", EntityType.OBJECT)
    val ACTION = type("core.action", "core", EntityType.ACTION)
    val STYLE = type("visual.style", "visual", EntityType.STYLE)
    val CONTRACT = type("contract.contract", "contract")
    val INVOICE = type("finance.invoice", "finance")
    val NOTICE = type("authority.notice", "authority")
    val APPLICATION = type("authority.application", "authority")
    val CLAIM = type("legal.claim", "legal")

    val ACCOUNT = type("finance.account", "finance")
    val BANK_ACCOUNT = type("finance.bank_account", "finance")
    val DEBT = type("debt.debt", "debt")
    val PAYMENT = type("finance.payment", "finance")
    val INSTALLMENT = type("debt.installment", "debt")

    val MEDICATION = type("health.medication", "health")
    val DOSAGE = type("health.dosage", "health")

    val EVENT = type("appointment.event", "appointment")
    val APPOINTMENT = type("appointment.appointment", "appointment")
    val CASE = type("legal.case", "legal")
    val MATTER = type("life.matter", "life")

    private val definitions = listOf(
        PERSON, ORGANIZATION, AUTHORITY, LOCATION, ADDRESS, EMAIL_ADDRESS, PHONE_NUMBER, URL,
        DATE, TIME, DURATION, TIME_RANGE, DEADLINE,
        NUMBER, MONEY, CURRENCY, PERCENTAGE, QUANTITY, UNIT,
        FILE, DOCUMENT, IMAGE, COLOR, OBJECT, ACTION, STYLE,
        CONTRACT, INVOICE, NOTICE, APPLICATION, CLAIM,
        ACCOUNT, BANK_ACCOUNT, DEBT, PAYMENT, INSTALLMENT,
        MEDICATION, DOSAGE, EVENT, APPOINTMENT, CASE, MATTER,
    ).associateBy { it.id }

    fun definition(id: SemanticEntityTypeId): SemanticEntityTypeDefinition? = definitions[id]
    fun all(): Collection<SemanticEntityTypeDefinition> = definitions.values.sortedBy { it.id.value }
}

data class SemanticEntityV2(
    val typeId: SemanticEntityTypeId,
    val rawText: String,
    val normalizedValue: String,
    val tokenStart: Int,
    val tokenEndExclusive: Int,
    val confidence: Double,
    val source: String,
) {
    init {
        require(rawText.isNotBlank())
        require(normalizedValue.isNotBlank())
        require(tokenStart >= 0)
        require(tokenEndExclusive > tokenStart)
        require(confidence.isFinite() && confidence in 0.0..1.0)
        require(source.isNotBlank())
    }

    fun legacyProjection(): SemanticEntity? {
        val legacy = EntityTypeRegistry.definition(typeId)?.legacyType ?: return null
        return SemanticEntity(
            type = legacy,
            rawText = rawText,
            normalizedValue = normalizedValue,
            tokenStart = tokenStart,
            tokenEndExclusive = tokenEndExclusive,
            confidence = confidence,
        )
    }
}

data class EntityPipelineV2Result(
    val entities: List<SemanticEntityV2>,
    val legacyProjection: List<SemanticEntity>,
)

class DeterministicEntityPipelineV2(
    private val legacy: RuleBasedEntityExtractor = RuleBasedEntityExtractor(),
) {
    fun extract(utterance: NormalizedUtterance): EntityPipelineV2Result {
        val result = mutableListOf<SemanticEntityV2>()
        legacy.extract(utterance).forEach { result += it.toV2("legacy-entity-extractor") }

        addRegex(utterance, EMAIL_REGEX, EntityTypeRegistry.EMAIL_ADDRESS, 0.995, result) {
            it.lowercase(Locale.ROOT)
        }
        addRegex(utterance, URL_REGEX, EntityTypeRegistry.URL, 0.995, result) { it.trim() }
        addRegex(utterance, PHONE_REGEX, EntityTypeRegistry.PHONE_NUMBER, 0.96, result) {
            it.replace(Regex("[^+0-9]"), "")
        }
        addRegex(utterance, PERCENT_REGEX, EntityTypeRegistry.PERCENTAGE, 0.99, result) {
            it.replace(" ", "").replace("%", "")
        }
        addRegex(utterance, MONEY_REGEX, EntityTypeRegistry.MONEY, 0.995, result) {
            normalizeMoney(it)
        }
        addRegex(utterance, IBAN_REGEX, EntityTypeRegistry.BANK_ACCOUNT, 0.995, result) {
            it.replace(" ", "").uppercase(Locale.ROOT)
        }

        val words = utterance.tokens.map { it.normalized }
        utterance.tokens.forEachIndexed { index, token ->
            if (token.kind != TokenKind.WORD) return@forEachIndexed
            val word = token.normalized

            domainCompoundTypes(token.original).forEach { definition ->
                result += SemanticEntityV2(
                    typeId = definition.id,
                    rawText = token.original,
                    normalizedValue = canonicalLexicalValue(definition, word),
                    tokenStart = index,
                    tokenEndExclusive = index + 1,
                    confidence = 0.94,
                    source = "entity-v2-domain-compound",
                )
            }

            lexicalType(word)?.let { definition ->
                result += SemanticEntityV2(
                    typeId = definition.id,
                    rawText = token.original,
                    normalizedValue = canonicalLexicalValue(definition, word),
                    tokenStart = index,
                    tokenEndExclusive = index + 1,
                    confidence = 0.96,
                    source = "entity-v2-domain-lexicon",
                )
            }

            if (looksLikePersonName(utterance, index)) {
                result += SemanticEntityV2(
                    typeId = EntityTypeRegistry.PERSON.id,
                    rawText = token.original,
                    normalizedValue = token.original,
                    tokenStart = index,
                    tokenEndExclusive = index + 1,
                    confidence = 0.72,
                    source = "entity-v2-capitalization-name-pattern",
                )
            }
        }

        extractAuthorities(utterance, words, result)
        extractDocumentPhrases(utterance, result)

        val entities = result
            .distinctBy {
                listOf(
                    it.typeId.value,
                    it.tokenStart.toString(),
                    it.tokenEndExclusive.toString(),
                    it.normalizedValue,
                )
            }
            .sortedWith(
                compareBy<SemanticEntityV2> { it.tokenStart }
                    .thenBy { it.tokenEndExclusive }
                    .thenBy { it.typeId.value }
                    .thenByDescending { it.confidence }
            )

        val projected = entities
            .mapNotNull(SemanticEntityV2::legacyProjection)
            .distinctBy {
                listOf(
                    it.type.name,
                    it.tokenStart.toString(),
                    it.tokenEndExclusive.toString(),
                    it.normalizedValue,
                )
            }
            .sortedWith(compareBy<SemanticEntity> { it.tokenStart }.thenBy { it.type.name })

        return EntityPipelineV2Result(entities, projected)
    }

    private fun extractAuthorities(
        utterance: NormalizedUtterance,
        words: List<String>,
        out: MutableList<SemanticEntityV2>,
    ) {
        words.forEachIndexed { index, word ->
            if (word !in AUTHORITY_TERMS) return@forEachIndexed
            val token = utterance.tokens[index]
            out += SemanticEntityV2(
                typeId = EntityTypeRegistry.AUTHORITY.id,
                rawText = token.original,
                normalizedValue = normalizeAuthority(word),
                tokenStart = index,
                tokenEndExclusive = index + 1,
                confidence = 0.98,
                source = "entity-v2-authority-pack",
            )
        }
    }

    private fun extractDocumentPhrases(
        utterance: NormalizedUtterance,
        out: MutableList<SemanticEntityV2>,
    ) {
        utterance.tokens.forEachIndexed { index, token ->
            val definition = DOCUMENT_TERMS[token.normalized] ?: return@forEachIndexed
            out += SemanticEntityV2(
                typeId = definition.id,
                rawText = token.original,
                normalizedValue = token.normalized,
                tokenStart = index,
                tokenEndExclusive = index + 1,
                confidence = 0.97,
                source = "entity-v2-document-pack",
            )
        }
    }

    private fun looksLikePersonName(
        utterance: NormalizedUtterance,
        index: Int,
    ): Boolean {
        val token = utterance.tokens[index]
        if (token.original.firstOrNull()?.isUpperCase() != true) return false
        if (token.normalized in NON_NAME_WORDS) return false
        if (index == 0 && token.normalized in SENTENCE_INITIAL_NON_NAMES) return false
        if (token.normalized in AUTHORITY_TERMS) return false
        if (DOCUMENT_TERMS.containsKey(token.normalized)) return false
        return true
    }

    private fun domainCompoundTypes(raw: String): Set<SemanticEntityTypeDefinition> {
        val token = normalizeFieldText(raw)
        return buildSet {
            if ("jobcenter" in token || "arbeitsagentur" in token || "finanzamt" in token) {
                add(EntityTypeRegistry.AUTHORITY)
            }
            if (token.endsWith("bescheid")) add(EntityTypeRegistry.NOTICE)
            if ("widerspruch" in token) add(EntityTypeRegistry.CLAIM)
            if (token.endsWith("frist")) add(EntityTypeRegistry.DEADLINE)
            if ("ratenzahlung" in token) add(EntityTypeRegistry.INSTALLMENT)
            if (token.endsWith("vereinbarung")) add(EntityTypeRegistry.CONTRACT)
            if ("rechnung" in token) add(EntityTypeRegistry.INVOICE)
            if ("forderung" in token || "schuld" in token) add(EntityTypeRegistry.DEBT)
        }
    }

    private fun lexicalType(word: String): SemanticEntityTypeDefinition? = when (word) {
        in CONTRACT_TERMS -> EntityTypeRegistry.CONTRACT
        in INVOICE_TERMS -> EntityTypeRegistry.INVOICE
        in DEBT_TERMS -> EntityTypeRegistry.DEBT
        in PAYMENT_TERMS -> EntityTypeRegistry.PAYMENT
        in INSTALLMENT_TERMS -> EntityTypeRegistry.INSTALLMENT
        in APPOINTMENT_TERMS -> EntityTypeRegistry.APPOINTMENT
        in DEADLINE_TERMS -> EntityTypeRegistry.DEADLINE
        in MEDICATION_TERMS -> EntityTypeRegistry.MEDICATION
        in MATTER_TERMS -> EntityTypeRegistry.MATTER
        else -> null
    }

    private fun canonicalLexicalValue(
        type: SemanticEntityTypeDefinition,
        word: String,
    ): String = when (type.id) {
        EntityTypeRegistry.CONTRACT.id -> "contract"
        EntityTypeRegistry.INVOICE.id -> "invoice"
        EntityTypeRegistry.DEBT.id -> "debt"
        EntityTypeRegistry.PAYMENT.id -> "payment"
        EntityTypeRegistry.INSTALLMENT.id -> "installment"
        EntityTypeRegistry.APPOINTMENT.id -> "appointment"
        EntityTypeRegistry.DEADLINE.id -> "deadline"
        EntityTypeRegistry.MEDICATION.id -> "medication"
        EntityTypeRegistry.MATTER.id -> "matter"
        else -> word
    }

    private fun SemanticEntity.toV2(source: String): SemanticEntityV2 {
        val definition = when (type) {
            EntityType.PERSON -> EntityTypeRegistry.PERSON
            EntityType.LOCATION -> EntityTypeRegistry.LOCATION
            EntityType.DATE -> EntityTypeRegistry.DATE
            EntityType.TIME -> EntityTypeRegistry.TIME
            EntityType.DURATION -> EntityTypeRegistry.DURATION
            EntityType.NUMBER -> EntityTypeRegistry.NUMBER
            EntityType.FILE -> EntityTypeRegistry.FILE
            EntityType.IMAGE -> EntityTypeRegistry.IMAGE
            EntityType.COLOR -> EntityTypeRegistry.COLOR
            EntityType.OBJECT -> EntityTypeRegistry.OBJECT
            EntityType.ACTION -> EntityTypeRegistry.ACTION
            EntityType.STYLE -> EntityTypeRegistry.STYLE
        }
        return SemanticEntityV2(
            typeId = definition.id,
            rawText = rawText,
            normalizedValue = normalizedValue,
            tokenStart = tokenStart,
            tokenEndExclusive = tokenEndExclusive,
            confidence = confidence,
            source = source,
        )
    }

    private fun addRegex(
        utterance: NormalizedUtterance,
        regex: Regex,
        type: SemanticEntityTypeDefinition,
        confidence: Double,
        out: MutableList<SemanticEntityV2>,
        normalize: (String) -> String,
    ) {
        regex.findAll(utterance.original).forEach { match ->
            val overlapping = utterance.tokens.withIndex().filter { (_, token) ->
                token.start < match.range.last + 1 && token.endExclusive > match.range.first
            }
            if (overlapping.isEmpty()) return@forEach
            out += SemanticEntityV2(
                typeId = type.id,
                rawText = match.value.trim(),
                normalizedValue = normalize(match.value.trim()),
                tokenStart = overlapping.first().index,
                tokenEndExclusive = overlapping.last().index + 1,
                confidence = confidence,
                source = "entity-v2-regex",
            )
        }
    }

    private companion object {
        val EMAIL_REGEX = Regex("""\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b""", RegexOption.IGNORE_CASE)
        val URL_REGEX = Regex("""\bhttps?://[^\s<>()]+""", RegexOption.IGNORE_CASE)
        val PHONE_REGEX = Regex("""(?<!\w)(?:\+?\d[\d /()-]{6,}\d)(?!\w)""")
        val PERCENT_REGEX = Regex("""\b\d+(?:[.,]\d+)?\s*%""")
        val MONEY_REGEX = Regex(
            """(?i)(?:\b\d+(?:[.,]\d{1,2})?\s*(?:€|eur|euro|usd|dollar|gbp|pound)\b|(?:€|\$|£)\s*\d+(?:[.,]\d{1,2})?)"""
        )
        val IBAN_REGEX = Regex("""\b[A-Z]{2}\d{2}(?:\s?[A-Z0-9]){11,30}\b""", RegexOption.IGNORE_CASE)

        val AUTHORITY_TERMS = setOf(
            "jobcenter", "arbeitsagentur", "finanzamt", "sozialamt", "jugendamt",
            "ausländerbehörde", "auslaenderbehoerde", "behörde", "behoerde", "amt",
        )
        val DOCUMENT_TERMS = mapOf(
            "bescheid" to EntityTypeRegistry.NOTICE,
            "widerspruch" to EntityTypeRegistry.CLAIM,
            "antrag" to EntityTypeRegistry.APPLICATION,
            "rechnung" to EntityTypeRegistry.INVOICE,
            "vertrag" to EntityTypeRegistry.CONTRACT,
            "dokument" to EntityTypeRegistry.DOCUMENT,
            "datei" to EntityTypeRegistry.FILE,
        )
        val CONTRACT_TERMS = setOf("vertrag", "vertragslaufzeit", "kündigung", "kuendigung")
        val INVOICE_TERMS = setOf("rechnung", "invoice")
        val DEBT_TERMS = setOf("schuld", "schulden", "forderung", "rückforderung", "rueckforderung", "debt")
        val PAYMENT_TERMS = setOf("zahlung", "überweisung", "ueberweisung", "lastschrift", "payment", "transfer")
        val INSTALLMENT_TERMS = setOf("rate", "ratenzahlung", "installment")
        val APPOINTMENT_TERMS = setOf("termin", "appointment")
        val DEADLINE_TERMS = setOf("frist", "widerspruchsfrist", "deadline")
        val MEDICATION_TERMS = setOf("medikament", "medikamente", "medication")
        val MATTER_TERMS = setOf("fall", "angelegenheit", "matter", "case")

        val NON_NAME_WORDS = (
            AUTHORITY_TERMS +
                DOCUMENT_TERMS.keys +
                CONTRACT_TERMS +
                INVOICE_TERMS +
                DEBT_TERMS +
                PAYMENT_TERMS +
                INSTALLMENT_TERMS +
                APPOINTMENT_TERMS +
                DEADLINE_TERMS +
                MEDICATION_TERMS +
                MATTER_TERMS
            ).toSet()
        val SENTENCE_INITIAL_NON_NAMES = setOf(
            "der", "die", "das", "ein", "eine", "ich", "du", "wir", "sie", "er", "es",
            "wenn", "falls", "bitte", "the", "a", "an", "i", "you", "we", "he", "she", "it", "if",
        )

        fun normalizeAuthority(word: String): String = word.lowercase(Locale.ROOT).replace("ß", "ss")
        fun normalizeMoney(raw: String): String = raw
            .lowercase(Locale.ROOT)
            .replace("euro", "EUR", ignoreCase = true)
            .replace("eur", "EUR", ignoreCase = true)
            .replace("€", "EUR")
            .replace("usd", "USD", ignoreCase = true)
            .replace("$", "USD")
            .replace("gbp", "GBP", ignoreCase = true)
            .replace("£", "GBP")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
