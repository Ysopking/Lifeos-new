package app.lifeos.core.runtime.life

import app.lifeos.core.model.Photon

enum class DomainFactKind {
    QUESTION,
    KNOWLEDGE_GAP,
    LEGAL_ISSUE,
    OBLIGATION,
    DEADLINE,
    CLAIM,
    DEBT,
    CREDITOR,
    PAYMENT,
    AMOUNT,
    BUSINESS_OPPORTUNITY,
    BUSINESS_RISK,
    KPI,
}

data class DomainFact(
    val kind: DomainFactKind,
    val value: String,
    val confidence: Double,
    val evidence: String,
) {
    init {
        require(value.isNotBlank())
        require(confidence.isFinite() && confidence in 0.0..1.0)
        require(evidence.isNotBlank())
    }
}

/**
 * Deterministic evidence extractor used before higher-order domain reasoning.
 * It does not assert legal/financial truth; it identifies structured candidate facts that keep
 * the originating Photon as evidence and can be confirmed, contradicted or enriched later.
 */
object DomainEvidenceExtractor {
    fun curiosity(photon: Photon): List<DomainFact> {
        val text = photon.content.trim()
        if (text.isBlank()) return emptyList()
        val facts = mutableListOf<DomainFact>()
        if ('?' in text || containsAny(text, QUESTION_WORDS)) {
            facts += fact(DomainFactKind.QUESTION, text.take(512), 0.9, text)
        }
        if (containsAny(text, KNOWLEDGE_GAP_WORDS)) {
            facts += fact(DomainFactKind.KNOWLEDGE_GAP, text.take(512), 0.8, text)
        }
        return facts.distinctBy { it.kind to it.value }
    }

    fun legal(photon: Photon): List<DomainFact> {
        val text = photon.content
        val facts = mutableListOf<DomainFact>()
        if (containsAny(text, LEGAL_WORDS)) {
            facts += fact(DomainFactKind.LEGAL_ISSUE, compactEvidence(text), 0.72, text)
        }
        if (containsAny(text, OBLIGATION_WORDS)) {
            facts += fact(DomainFactKind.OBLIGATION, compactEvidence(text), 0.68, text)
        }
        extractDates(text).forEach { date ->
            if (containsAny(text, DEADLINE_WORDS)) {
                facts += fact(DomainFactKind.DEADLINE, date, 0.82, text)
            }
        }
        if (containsAny(text, CLAIM_WORDS)) {
            facts += fact(DomainFactKind.CLAIM, compactEvidence(text), 0.76, text)
        }
        extractAmounts(text).forEach { amount ->
            facts += fact(DomainFactKind.AMOUNT, amount, 0.9, text)
        }
        return facts.distinctBy { it.kind to it.value }
    }

    fun debt(photon: Photon): List<DomainFact> {
        val text = photon.content
        val facts = mutableListOf<DomainFact>()
        if (containsAny(text, DEBT_WORDS)) {
            facts += fact(DomainFactKind.DEBT, compactEvidence(text), 0.8, text)
        }
        if (containsAny(text, CREDITOR_WORDS)) {
            facts += fact(DomainFactKind.CREDITOR, compactEvidence(text), 0.66, text)
        }
        if (containsAny(text, PAYMENT_WORDS)) {
            facts += fact(DomainFactKind.PAYMENT, compactEvidence(text), 0.72, text)
        }
        extractAmounts(text).forEach { amount ->
            facts += fact(DomainFactKind.AMOUNT, amount, 0.92, text)
        }
        extractDates(text).forEach { date ->
            if (containsAny(text, DEADLINE_WORDS + PAYMENT_WORDS)) {
                facts += fact(DomainFactKind.DEADLINE, date, 0.8, text)
            }
        }
        return facts.distinctBy { it.kind to it.value }
    }

    fun business(photon: Photon): List<DomainFact> {
        val text = photon.content
        val facts = mutableListOf<DomainFact>()
        if (containsAny(text, OPPORTUNITY_WORDS)) {
            facts += fact(DomainFactKind.BUSINESS_OPPORTUNITY, compactEvidence(text), 0.7, text)
        }
        if (containsAny(text, RISK_WORDS)) {
            facts += fact(DomainFactKind.BUSINESS_RISK, compactEvidence(text), 0.7, text)
        }
        if (containsAny(text, KPI_WORDS)) {
            facts += fact(DomainFactKind.KPI, compactEvidence(text), 0.65, text)
        }
        extractAmounts(text).forEach { amount ->
            facts += fact(DomainFactKind.AMOUNT, amount, 0.9, text)
        }
        return facts.distinctBy { it.kind to it.value }
    }

    private fun fact(kind: DomainFactKind, value: String, confidence: Double, source: String): DomainFact =
        DomainFact(kind, value, confidence, compactEvidence(source))

    private fun compactEvidence(value: String): String = value.replace(Regex("\\s+"), " ").trim().take(768)

    private fun containsAny(value: String, terms: Set<String>): Boolean {
        val lower = value.lowercase()
        return terms.any(lower::contains)
    }

    private fun extractAmounts(value: String): List<String> = AMOUNT.findAll(value)
        .map { it.value.trim() }
        .distinct()
        .take(8)
        .toList()

    private fun extractDates(value: String): List<String> = DATE.findAll(value)
        .map { it.value }
        .distinct()
        .take(8)
        .toList()

    private val AMOUNT = Regex("(?i)(?:\\d{1,3}(?:[. ]\\d{3})*|\\d+)(?:,\\d{1,2})?\\s*(?:€|eur)")
    private val DATE = Regex("\\b(?:0?[1-9]|[12]\\d|3[01])[./-](?:0?[1-9]|1[0-2])[./-](?:19|20)\\d{2}\\b")

    private val QUESTION_WORDS = setOf("warum", "wieso", "wie ", "was ", "welche", "where", "why", "how ", "question")
    private val KNOWLEDGE_GAP_WORDS = setOf("weiß nicht", "unklar", "fehlt", "herausfinden", "recherch", "unknown", "missing", "find out")
    private val LEGAL_WORDS = setOf("vertrag", "recht", "gesetz", "kündig", "mahnung", "anspruch", "klage", "gericht", "haftung", "contract", "legal", "law", "court", "liability")
    private val OBLIGATION_WORDS = setOf("muss", "verpflicht", "schuldet", "zu zahlen", "leisten", "pflicht", "must", "obligation", "owes", "payable")
    private val DEADLINE_WORDS = setOf("frist", "bis zum", "fällig", "deadline", "spätestens", "due")
    private val CLAIM_WORDS = setOf("forderung", "anspruch", "mahnung", "rechnung", "claim", "invoice")
    private val DEBT_WORDS = setOf("schuld", "schulden", "forderung", "mahnung", "rückstand", "inkasso", "kredit", "debt", "arrears", "collection")
    private val CREDITOR_WORDS = setOf("gläubiger", "creditor", "inkasso", "forderung von", "collection agency")
    private val PAYMENT_WORDS = setOf("zahlung", "bezahlen", "überweisen", "rate", "tilgung", "fällig", "payment", "pay", "instalment", "installment", "due")
    private val OPPORTUNITY_WORDS = setOf("chance", "möglichkeit", "wachstum", "neuer kunde", "umsatz", "markt", "opportunity", "growth", "new customer", "revenue")
    private val RISK_WORDS = setOf("risiko", "verlust", "kostensteiger", "engpass", "problem", "bedrohung", "risk", "loss", "bottleneck", "threat")
    private val KPI_WORDS = setOf("umsatz", "marge", "gewinn", "kosten", "cashflow", "conversion", "kpi", "revenue", "margin", "profit", "cost")
}
