package app.lifeos.core.runtime.personal

import app.lifeos.core.language.SemanticSearchTerms
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonIndexOrder
import app.lifeos.core.model.PhotonIndexQuery
import app.lifeos.core.model.PhotonPhase
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.PhotonRevisionWriteResult
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.RevisionedPhotonRepository
import app.lifeos.core.model.StableCognitiveIds
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

enum class PersonalConversationSource {
    WHATSAPP,
    GEMINI,
    LIFEOS,
}

enum class PersonalConversationSpeaker {
    OWNER,
    ASSISTANT,
    OTHER,
    UNKNOWN,
}

data class PersonalConversationTurn(
    val source: PersonalConversationSource,
    val conversationId: String,
    val speaker: PersonalConversationSpeaker,
    val text: String,
    val observedAt: Instant,
    val externalMessageId: String? = null,
    val replyToExternalId: String? = null,
) {
    init {
        require(conversationId.isNotBlank())
        require(text.isNotBlank())
        require(externalMessageId == null || externalMessageId.isNotBlank())
        require(replyToExternalId == null || replyToExternalId.isNotBlank())
    }

    val stableFingerprint: String = StableCognitiveIds.fingerprint(
        "personal-conversation-turn/v1",
        source.name,
        conversationId,
        speaker.name,
        observedAt.toString(),
        externalMessageId.orEmpty(),
        replyToExternalId.orEmpty(),
        text,
    )

    val photonId: PhotonId = PhotonId("personal-conversation-$stableFingerprint")

    fun toPhoton(): Photon = Photon(
        id = photonId,
        content = text,
        mimeType = MIME_TYPE,
        phase = PhotonPhase.ARCHIVED,
        semanticMass = 0.35,
        energy = 0.0,
        confidence = 1.0,
        provenance = Provenance(
            source = "personal-conversation:${source.name.lowercase()}",
            actor = when (speaker) {
                PersonalConversationSpeaker.OWNER -> "owner"
                PersonalConversationSpeaker.ASSISTANT -> "assistant"
                PersonalConversationSpeaker.OTHER -> "other"
                PersonalConversationSpeaker.UNKNOWN -> "unknown"
            },
            createdAt = observedAt,
        ),
        tags = buildSet {
            add("corpus:archive")
            add("corpus:personal")
            add("privacy:private-conversation")
            add("privacy:local-only")
            add("privacy:no-external-export")
            add("privacy:no-deepsearch-export")
            add("privacy:no-autonomous-share")
            add("conversation-source:${source.name.lowercase()}")
            add("conversation:${StableCognitiveIds.fingerprint("personal-conversation-id/v1", source.name, conversationId)}")
            add("speaker:${speaker.name.lowercase()}")
            SemanticSearchTerms.tokens(text)
                .take(MAX_INDEXED_TERMS)
                .forEach { add("corpus-term:$it") }
        },
    )

    companion object {
        const val MIME_TYPE = "application/vnd.lifeos.personal-conversation+text"
        private const val MAX_INDEXED_TERMS = 24
    }
}

data class PersonalConversationImportResult(
    val created: Int,
    val replayed: Int,
    val refs: List<PhotonRevisionRef>,
    val sourceCounts: Map<PersonalConversationSource, Int> = emptyMap(),
    val speakerCounts: Map<PersonalConversationSpeaker, Int> = emptyMap(),
) {
    init {
        require(created >= 0)
        require(replayed >= 0)
        require(sourceCounts.values.all { it >= 0 })
        require(speakerCounts.values.all { it >= 0 })
    }

    val total: Int get() = created + replayed

    operator fun plus(other: PersonalConversationImportResult): PersonalConversationImportResult =
        PersonalConversationImportResult(
            created = created + other.created,
            replayed = replayed + other.replayed,
            refs = (refs + other.refs)
                .distinct()
                .sortedWith(
                    compareBy<PhotonRevisionRef> { it.photonId.value }.thenBy { it.revision }
                ),
            sourceCounts = mergeCounts(sourceCounts, other.sourceCounts),
            speakerCounts = mergeCounts(speakerCounts, other.speakerCounts),
        )

    private fun <T> mergeCounts(left: Map<T, Int>, right: Map<T, Int>): Map<T, Int> =
        (left.keys + right.keys).associateWith { key ->
            left.getOrDefault(key, 0) + right.getOrDefault(key, 0)
        }
}

class PersonalConversationCorpusImporter(
    private val photons: RevisionedPhotonRepository,
) {
    suspend fun import(turns: Iterable<PersonalConversationTurn>): PersonalConversationImportResult =
        import(turns.asSequence())

    suspend fun import(turns: Sequence<PersonalConversationTurn>): PersonalConversationImportResult {
        var created = 0
        var replayed = 0
        val refs = mutableListOf<PhotonRevisionRef>()
        val sourceCounts = mutableMapOf<PersonalConversationSource, Int>()
        val speakerCounts = mutableMapOf<PersonalConversationSpeaker, Int>()

        for (turn in turns) {
            val photon = turn.toPhoton()
            when (val write = photons.saveRevision(photon, expectedPreviousRevision = null)) {
                is PhotonRevisionWriteResult.Created -> created += 1
                is PhotonRevisionWriteResult.Idempotent -> replayed += 1
                is PhotonRevisionWriteResult.Advanced ->
                    error("Personal conversation import must never advance an immutable turn")
                is PhotonRevisionWriteResult.Conflict ->
                    error("Personal conversation import conflict: ${write.reason}")
            }
            refs += PhotonRevisionRef(photon.id, photon.revision)
            sourceCounts[turn.source] = sourceCounts.getOrDefault(turn.source, 0) + 1
            speakerCounts[turn.speaker] = speakerCounts.getOrDefault(turn.speaker, 0) + 1
        }
        return PersonalConversationImportResult(
            created = created,
            replayed = replayed,
            refs = refs.distinct().sortedWith(
                compareBy<PhotonRevisionRef> { it.photonId.value }.thenBy { it.revision }
            ),
            sourceCounts = sourceCounts.toSortedMap(compareBy { it.name }),
            speakerCounts = speakerCounts.toSortedMap(compareBy { it.name }),
        )
    }
}

data class PersonalCorpusMatch(
    val ref: PhotonRevisionRef,
    val photon: Photon,
    val score: Double,
) {
    init { require(score.isFinite() && score in 0.0..1.0) }
}

class PersonalCorpusRetriever(
    private val photons: RevisionedPhotonRepository,
    private val maxTerms: Int = 6,
    private val perTermLimit: Int = 32,
    private val maxCandidates: Int = 96,
    private val maxSelected: Int = 16,
) {
    init {
        require(maxTerms in 1..16)
        require(perTermLimit in 1..64)
        require(maxCandidates in 16..256)
        require(maxSelected in 1..maxCandidates)
    }

    suspend fun retrieveOwnerLanguageExamples(
        query: String,
        now: Instant,
    ): List<PersonalCorpusMatch> = retrieve(
        query = query,
        now = now,
        ownerOnly = true,
    )

    suspend fun retrieve(
        query: String,
        now: Instant,
        ownerOnly: Boolean = false,
    ): List<PersonalCorpusMatch> {
        val terms = SemanticSearchTerms.tokens(query)
            .distinct()
            .take(maxTerms)
        if (terms.isEmpty()) return emptyList()

        val required = buildSet {
            add("corpus:archive")
            if (ownerOnly) add("speaker:owner")
        }
        val indexedTerms = terms
            .mapTo(linkedSetOf()) { term -> "corpus-term:$term" }
        val retrievalLimit = minOf(
            maxCandidates,
            perTermLimit * terms.size,
        )
        val refs = photons.query(
            PhotonIndexQuery(
                allTags = required,
                anyTags = indexedTerms,
                latestOnly = true,
                includeTombstoned = false,
                order = PhotonIndexOrder.NEWEST_FIRST,
                limit = retrievalLimit,
            )
        )

        return refs.mapNotNull { ref -> photons.load(ref)?.let { ref to it } }
            .map { (ref, photon) ->
                PersonalCorpusMatch(ref, photon, score(photon, terms.toSet(), now))
            }
            .sortedWith(
                compareByDescending<PersonalCorpusMatch> { it.score }
                    .thenByDescending { it.photon.provenance.createdAt }
                    .thenBy { it.ref.photonId.value }
            )
            .take(maxSelected)
    }

    private fun score(photon: Photon, terms: Set<String>, now: Instant): Double {
        val candidateTerms = SemanticSearchTerms.tokens(photon.content)
        val overlap = terms.count(candidateTerms::contains).toDouble() / terms.size.toDouble()
        val ageDays = Duration.between(photon.provenance.createdAt, now)
            .toHours()
            .coerceAtLeast(0L)
            .toDouble() / 24.0
        val recency = (1.0 - ageDays / 3650.0).coerceIn(0.0, 1.0)
        val speaker = when {
            "speaker:owner" in photon.tags -> 1.0
            "speaker:assistant" in photon.tags -> 0.70
            else -> 0.45
        }
        return (overlap * 0.72 + recency * 0.18 + speaker * 0.10).coerceIn(0.0, 1.0)
    }
}

class WhatsAppTextArchiveParser(
    private val ownerNames: Set<String>,
    private val zoneId: ZoneId = ZoneId.of("Europe/Berlin"),
) {
    init { require(ownerNames.none { it.isBlank() }) }

    private val normalizedOwnerNames: Set<String> = ownerNames
        .map { it.trim().lowercase(Locale.ROOT) }
        .filter { it.isNotBlank() }
        .toSet()

    fun parse(
        content: String,
        conversationId: String,
    ): List<PersonalConversationTurn> =
        parseLines(content.lineSequence(), conversationId).toList()

    fun parseLines(
        lines: Sequence<String>,
        conversationId: String,
    ): Sequence<PersonalConversationTurn> = sequence {
        require(conversationId.isNotBlank())
        var current: MutableTurn? = null
        var index = 0

        for (rawLine in lines) {
            val line = rawLine.trimEnd()
            val header = parseHeader(line)
            if (header != null) {
                current?.toTurn(conversationId, index)?.let {
                    yield(it)
                    index += 1
                }
                current = MutableTurn(
                    speakerName = header.speaker,
                    observedAt = header.observedAt,
                    text = StringBuilder(header.text),
                )
            } else if (current != null && line.isNotBlank()) {
                current.text.append('\n').append(line)
            }
        }

        current?.toTurn(conversationId, index)?.let { yield(it) }
    }

    private fun MutableTurn.toTurn(
        conversationId: String,
        index: Int,
    ): PersonalConversationTurn? {
        val normalizedText = text.toString().trim()
        if (normalizedText.isBlank()) return null
        val normalizedSpeaker = speakerName.trim().lowercase(Locale.ROOT)
        return PersonalConversationTurn(
            source = PersonalConversationSource.WHATSAPP,
            conversationId = conversationId,
            speaker = if (normalizedSpeaker in normalizedOwnerNames) {
                PersonalConversationSpeaker.OWNER
            } else {
                PersonalConversationSpeaker.OTHER
            },
            text = normalizedText,
            observedAt = observedAt,
            externalMessageId = "whatsapp-$index",
        )
    }

    private fun parseHeader(line: String): Header? {
        val match = BRACKET_HEADER.matchEntire(line) ?: DASH_HEADER.matchEntire(line) ?: return null
        val date = match.groupValues[1]
        val time = match.groupValues[2]
        val speaker = match.groupValues[3].trim()
        val text = match.groupValues[4]
        if (speaker.isBlank()) return null
        return Header(
            speaker = speaker,
            observedAt = parseInstant(date, time) ?: return null,
            text = text,
        )
    }

    private fun parseInstant(date: String, time: String): Instant? = runCatching {
        val dateParts = date.split('.', '/', '-').map(String::toInt)
        val timeParts = time.split(':').map(String::toInt)
        require(dateParts.size == 3 && timeParts.size >= 2)
        val year = if (dateParts[2] < 100) 2000 + dateParts[2] else dateParts[2]
        LocalDateTime.of(
            year,
            dateParts[1],
            dateParts[0],
            timeParts[0],
            timeParts[1],
            timeParts.getOrElse(2) { 0 },
        ).atZone(zoneId).toInstant()
    }.getOrNull()

    private data class Header(val speaker: String, val observedAt: Instant, val text: String)
    private data class MutableTurn(
        val speakerName: String,
        val observedAt: Instant,
        val text: StringBuilder,
    )

    private companion object {
        val BRACKET_HEADER = Regex(
            """^\[(\d{1,2}[./-]\d{1,2}[./-]\d{2,4}),\s*(\d{1,2}:\d{2}(?::\d{2})?)\]\s*([^:]+):\s?(.*)$"""
        )
        val DASH_HEADER = Regex(
            """^(\d{1,2}[./-]\d{1,2}[./-]\d{2,4}),\s*(\d{1,2}:\d{2}(?::\d{2})?)\s*-\s*([^:]+):\s?(.*)$"""
        )
    }
}

data class GeminiConversationRecord(
    val conversationId: String,
    val observedAt: Instant,
    val prompt: String,
    val response: String?,
    val externalId: String? = null,
)

object GeminiConversationRecordAdapter {
    fun adapt(records: Iterable<GeminiConversationRecord>): List<PersonalConversationTurn> =
        records.flatMap { record ->
            buildList {
                if (record.prompt.isNotBlank()) {
                    add(
                        PersonalConversationTurn(
                            source = PersonalConversationSource.GEMINI,
                            conversationId = record.conversationId,
                            speaker = PersonalConversationSpeaker.OWNER,
                            text = record.prompt,
                            observedAt = record.observedAt,
                            externalMessageId = record.externalId?.let { "$it:prompt" },
                        )
                    )
                }
                record.response?.takeIf { it.isNotBlank() }?.let { response ->
                    add(
                        PersonalConversationTurn(
                            source = PersonalConversationSource.GEMINI,
                            conversationId = record.conversationId,
                            speaker = PersonalConversationSpeaker.ASSISTANT,
                            text = response,
                            observedAt = record.observedAt,
                            externalMessageId = record.externalId?.let { "$it:response" },
                        )
                    )
                }
            }
        }
}
