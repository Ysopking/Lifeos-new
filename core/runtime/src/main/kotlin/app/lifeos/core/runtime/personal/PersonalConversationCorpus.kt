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
)

class PersonalConversationCorpusImporter(
    private val photons: RevisionedPhotonRepository,
) {
    suspend fun import(turns: Iterable<PersonalConversationTurn>): PersonalConversationImportResult {
        var created = 0
        var replayed = 0
        val refs = mutableListOf<PhotonRevisionRef>()
        turns.forEach { turn ->
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
        }
        return PersonalConversationImportResult(
            created = created,
            replayed = replayed,
            refs = refs.distinct().sortedWith(
                compareBy<PhotonRevisionRef> { it.photonId.value }.thenBy { it.revision }
            ),
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

    suspend fun retrieve(
        query: String,
        now: Instant,
        ownerOnly: Boolean = false,
    ): List<PersonalCorpusMatch> {
        val terms = SemanticSearchTerms.tokens(query).take(maxTerms)
        if (terms.isEmpty()) return emptyList()

        val refs = linkedSetOf<PhotonRevisionRef>()
        for (term in terms) {
            val required = buildSet {
                add("corpus:archive")
                add("corpus-term:$term")
                if (ownerOnly) add("speaker:owner")
            }
            photons.query(
                PhotonIndexQuery(
                    allTags = required,
                    latestOnly = true,
                    includeTombstoned = false,
                    order = PhotonIndexOrder.NEWEST_FIRST,
                    limit = perTermLimit,
                )
            ).forEach { ref ->
                if (refs.size < maxCandidates) refs += ref
            }
            if (refs.size >= maxCandidates) break
        }

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

    fun parse(
        content: String,
        conversationId: String,
    ): List<PersonalConversationTurn> {
        require(conversationId.isNotBlank())
        val turns = mutableListOf<MutableTurn>()
        content.lineSequence().forEach { rawLine ->
            val line = rawLine.trimEnd()
            val header = parseHeader(line)
            if (header != null) {
                turns += MutableTurn(
                    speakerName = header.speaker,
                    observedAt = header.observedAt,
                    text = StringBuilder(header.text),
                )
            } else if (turns.isNotEmpty() && line.isNotBlank()) {
                turns.last().text.append('\n').append(line)
            }
        }
        return turns.mapIndexedNotNull { index, turn ->
            val text = turn.text.toString().trim()
            if (text.isBlank()) return@mapIndexedNotNull null
            PersonalConversationTurn(
                source = PersonalConversationSource.WHATSAPP,
                conversationId = conversationId,
                speaker = if (turn.speakerName in ownerNames) {
                    PersonalConversationSpeaker.OWNER
                } else {
                    PersonalConversationSpeaker.OTHER
                },
                text = text,
                observedAt = turn.observedAt,
                externalMessageId = "whatsapp-$index",
            )
        }
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
