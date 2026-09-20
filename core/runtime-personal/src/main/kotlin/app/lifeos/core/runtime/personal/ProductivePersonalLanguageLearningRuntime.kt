package app.lifeos.core.runtime.personal

import app.lifeos.core.language.LanguageUnderstandingResult
import app.lifeos.core.language.LinguisticLexiconSnapshot
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
import java.util.Base64

data class PersonalLanguageLearningTurnResult(
    val staged: Int = 0,
    val observations: Int = 0,
    val promotions: Int = 0,
    val rejectedPromotions: Int = 0,
) {
    init {
        require(staged >= 0)
        require(observations >= 0)
        require(promotions >= 0)
        require(rejectedPromotions >= 0)
    }
}

/**
 * Productive, model-free owner-language feedback loop.
 *
 * A high-confidence deterministic interpretation may stage a previously unknown surface form, but
 * the staged form remains inert. A later owner confirmation/rejection/correction is the only thing
 * that turns it into durable evidence. Promotion still goes through policy, shadow holdout and the
 * encrypted durable LanguageRuntime CAS head.
 */
class ProductivePersonalLanguageLearningRuntime(
    private val photons: RevisionedPhotonRepository,
    private val promotion: DurablePersonalLanguagePromotionCoordinator,
    private val currentLexicon: () -> LinguisticLexiconSnapshot,
    private val miner: PersonalLanguageCandidateMiner = PersonalLanguageCandidateMiner(),
    private val maxFeedbackAge: Duration = Duration.ofMinutes(30),
) {
    init {
        require(!maxFeedbackAge.isNegative && !maxFeedbackAge.isZero)
    }

    suspend fun observePersistedTurn(
        source: Photon,
        understanding: LanguageUnderstandingResult?,
    ): PersonalLanguageLearningTurnResult {
        val feedback = PersonalLanguageFeedbackProjector.classify(source.content)
        if (feedback != PersonalLanguageFeedbackKind.UNKNOWN) {
            return applyFeedback(source, feedback)
        }
        if (understanding == null) return PersonalLanguageLearningTurnResult()
        return stage(source, understanding)
    }

    private suspend fun stage(
        source: Photon,
        understanding: LanguageUnderstandingResult,
    ): PersonalLanguageLearningTurnResult {
        val conversationId = conversationId(source)
        val lexicon = currentLexicon()
        val proposals = miner.propose(source.content, understanding, lexicon)
        if (proposals.isEmpty()) return PersonalLanguageLearningTurnResult()

        var staged = 0
        for (proposal in proposals) {
            val pending = PendingAlias(
                id = StableCognitiveIds.fingerprint(
                    "personal-language-pending/v1",
                    conversationId,
                    source.id.value,
                    source.revision.toString(),
                    lexicon.fingerprint,
                    proposal.targetConceptId,
                    proposal.surface,
                ),
                conversationId = conversationId,
                sourceRef = PhotonRevisionRef(source.id, source.revision),
                lexiconFingerprint = lexicon.fingerprint,
                surface = proposal.surface,
                targetConceptId = proposal.targetConceptId,
                createdAt = source.provenance.createdAt,
            )
            val photon = pending.toPhoton()
            if (photons.load(photon.id) != null) continue
            saveImmutable(photon)
            staged += 1
        }
        return PersonalLanguageLearningTurnResult(staged = staged)
    }

    private suspend fun applyFeedback(
        feedbackSource: Photon,
        feedback: PersonalLanguageFeedbackKind,
    ): PersonalLanguageLearningTurnResult {
        val accepted = when (feedback) {
            PersonalLanguageFeedbackKind.CONFIRMED -> true
            PersonalLanguageFeedbackKind.REJECTED,
            PersonalLanguageFeedbackKind.REFERENCE_CORRECTION,
            PersonalLanguageFeedbackKind.INTENT_CORRECTION -> false
            PersonalLanguageFeedbackKind.UNKNOWN -> return PersonalLanguageLearningTurnResult()
        }

        val conversationId = conversationId(feedbackSource)
        val pendingRefs = photons.query(
            PhotonIndexQuery(
                allTags = setOf(TAG_STATE, TAG_PENDING, conversationTag(conversationId)),
                latestOnly = true,
                includeTombstoned = false,
                order = PhotonIndexOrder.NEWEST_FIRST,
                limit = MAX_PENDING_SCAN,
            )
        )
        if (pendingRefs.isEmpty()) return PersonalLanguageLearningTurnResult()

        val alreadyResolved = photons.query(
            PhotonIndexQuery(
                allTags = setOf(TAG_STATE, TAG_OBSERVATION, conversationTag(conversationId)),
                latestOnly = true,
                includeTombstoned = false,
                order = PhotonIndexOrder.NEWEST_FIRST,
                limit = MAX_OBSERVATION_SCAN,
            )
        )
            .mapNotNull { ref -> photons.load(ref) }
            .flatMap { photon ->
                photon.tags.filter { tag -> tag.startsWith(TAG_PENDING_REF_PREFIX) }
            }
            .mapTo(mutableSetOf()) { tag -> tag.removePrefix(TAG_PENDING_REF_PREFIX) }

        val pending = pendingRefs
            .mapNotNull { ref -> photons.load(ref) }
            .mapNotNull(::decodePending)
            .filterNot { item -> item.id in alreadyResolved }
            .filter { item ->
                val age = Duration.between(item.createdAt, feedbackSource.provenance.createdAt)
                !age.isNegative && age <= maxFeedbackAge
            }

        if (pending.isEmpty()) return PersonalLanguageLearningTurnResult()

        val latestSource = pending.maxByOrNull { item -> item.createdAt }!!.sourceRef
        val selected = pending
            .filter { item -> item.sourceRef == latestSource }
            .sortedBy { item -> item.id }
            .take(MAX_PENDING_PER_TURN)

        var observations = 0
        var promotions = 0
        var rejectedPromotions = 0

        for (item in selected) {
            val observation = PersonalLanguageObservation(
                conversationId = item.conversationId,
                surface = item.surface,
                targetConceptId = item.targetConceptId,
                accepted = accepted,
                sourceRef = item.sourceRef,
            )
            val observationPhoton = observationPhoton(
                pending = item,
                observation = observation,
                feedbackSource = feedbackSource,
                feedback = feedback,
            )
            if (photons.load(observationPhoton.id) == null) {
                saveImmutable(observationPhoton)
                observations += 1
            }

            if (accepted) {
                val candidate = aggregateCandidate(item) ?: continue
                when (promotion.promote(candidate)) {
                    is DurablePersonalLanguagePromotionResult.Promoted -> promotions += 1
                    is DurablePersonalLanguagePromotionResult.Rejected -> rejectedPromotions += 1
                }
            }
        }

        return PersonalLanguageLearningTurnResult(
            observations = observations,
            promotions = promotions,
            rejectedPromotions = rejectedPromotions,
        )
    }

    private suspend fun aggregateCandidate(
        pending: PendingAlias,
    ): PersonalLanguageCandidate? {
        val refs = photons.query(
            PhotonIndexQuery(
                allTags = setOf(
                    TAG_STATE,
                    TAG_OBSERVATION,
                    surfaceTag(pending.surface),
                    targetTag(pending.targetConceptId),
                ),
                latestOnly = true,
                includeTombstoned = false,
                order = PhotonIndexOrder.NEWEST_FIRST,
                limit = MAX_CANDIDATE_OBSERVATIONS,
            )
        )
        val observations = refs
            .mapNotNull { ref -> photons.load(ref) }
            .mapNotNull(::decodeObservation)
            .distinctBy { observation -> observation.id }
        if (observations.isEmpty()) return null
        return PersonalLanguageCandidate.create(
            surface = pending.surface,
            targetConceptId = pending.targetConceptId,
            observations = observations,
        )
    }

    private suspend fun saveImmutable(photon: Photon) {
        when (val result = photons.saveRevision(photon, expectedPreviousRevision = null)) {
            is PhotonRevisionWriteResult.Created,
            is PhotonRevisionWriteResult.Idempotent -> Unit
            is PhotonRevisionWriteResult.Advanced ->
                error("Personal language learning state unexpectedly advanced an immutable Photon")
            is PhotonRevisionWriteResult.Conflict ->
                error("Personal language learning state conflict: " + result.reason)
        }
    }

    private fun observationPhoton(
        pending: PendingAlias,
        observation: PersonalLanguageObservation,
        feedbackSource: Photon,
        feedback: PersonalLanguageFeedbackKind,
    ): Photon {
        val id = PhotonId(
            "personal-language-observation-" + StableCognitiveIds.fingerprint(
                "personal-language-observation-photon/v1",
                pending.id,
                feedbackSource.id.value,
                feedbackSource.revision.toString(),
                observation.accepted.toString(),
            )
        )
        return Photon(
            id = id,
            content = listOf(
                "personal-language-observation/v1",
                "conversation=" + encode(pending.conversationId),
                "surface=" + encode(pending.surface),
                "target=" + encode(pending.targetConceptId),
                "accepted=" + observation.accepted,
                "source_id=" + encode(pending.sourceRef.photonId.value),
                "source_revision=" + pending.sourceRef.revision,
                "feedback=" + feedback.name,
            ).joinToString("\n"),
            phase = PhotonPhase.ACTIVE,
            semanticMass = 0.10,
            energy = 0.0,
            confidence = 1.0,
            provenance = Provenance(
                source = "personal-language-learning",
                actor = "lifeos",
                createdAt = feedbackSource.provenance.createdAt,
                parentIds = setOf(pending.sourceRef.photonId, feedbackSource.id),
            ),
            tags = setOf(
                TAG_STATE,
                TAG_OBSERVATION,
                conversationTag(pending.conversationId),
                TAG_PENDING_REF_PREFIX + pending.id,
                surfaceTag(pending.surface),
                targetTag(pending.targetConceptId),
                if (observation.accepted) {
                    "language-learning:accepted"
                } else {
                    "language-learning:rejected"
                },
            ),
        )
    }

    private fun decodeObservation(photon: Photon): PersonalLanguageObservation? =
        runCatching {
            if (photon.content.lineSequence().firstOrNull() != "personal-language-observation/v1") {
                return@runCatching null
            }
            val values = fields(photon.content)
            PersonalLanguageObservation(
                conversationId = decode(requireNotNull(values["conversation"])),
                surface = decode(requireNotNull(values["surface"])),
                targetConceptId = decode(requireNotNull(values["target"])),
                accepted = requireNotNull(values["accepted"]).toBooleanStrict(),
                sourceRef = PhotonRevisionRef(
                    photonId = PhotonId(decode(requireNotNull(values["source_id"]))),
                    revision = requireNotNull(values["source_revision"]).toLong(),
                ),
            )
        }.getOrNull()

    private fun decodePending(photon: Photon): PendingAlias? =
        runCatching {
            if (photon.content.lineSequence().firstOrNull() != "personal-language-pending/v1") {
                return@runCatching null
            }
            val values = fields(photon.content)
            val pending = PendingAlias(
                id = requireNotNull(values["id"]),
                conversationId = decode(requireNotNull(values["conversation"])),
                sourceRef = PhotonRevisionRef(
                    PhotonId(decode(requireNotNull(values["source_id"]))),
                    requireNotNull(values["source_revision"]).toLong(),
                ),
                lexiconFingerprint = requireNotNull(values["lexicon"]),
                surface = decode(requireNotNull(values["surface"])),
                targetConceptId = decode(requireNotNull(values["target"])),
                createdAt = photon.provenance.createdAt,
            )
            require(photon.id == pendingPhotonId(pending.id))
            pending
        }.getOrNull()

    private fun fields(content: String): Map<String, String> =
        content.lineSequence().drop(1).associate { line ->
            val split = line.indexOf('=')
            require(split > 0)
            line.substring(0, split) to line.substring(split + 1)
        }

    private fun conversationId(photon: Photon): String =
        photon.tags.firstOrNull { tag -> tag.startsWith("conversation:") }
            ?.substringAfter(':')
            ?.takeIf { value -> value.isNotBlank() }
            ?: "default"

    private fun conversationTag(id: String): String = "conversation:" + id

    private fun surfaceTag(surface: String): String =
        "language-learning-surface:" + StableCognitiveIds.fingerprint(
            "personal-language-surface/v1",
            surface,
        )

    private fun targetTag(target: String): String =
        "language-learning-target:" + StableCognitiveIds.fingerprint(
            "personal-language-target/v1",
            target,
        )

    private fun pendingPhotonId(id: String): PhotonId =
        PhotonId("personal-language-pending-" + id)

    private fun encode(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.encodeToByteArray())

    private fun decode(value: String): String =
        Base64.getUrlDecoder().decode(value).decodeToString()

    private data class PendingAlias(
        val id: String,
        val conversationId: String,
        val sourceRef: PhotonRevisionRef,
        val lexiconFingerprint: String,
        val surface: String,
        val targetConceptId: String,
        val createdAt: Instant,
    ) {
        fun toPhoton(): Photon = Photon(
            id = PhotonId("personal-language-pending-" + id),
            content = listOf(
                "personal-language-pending/v1",
                "id=" + id,
                "conversation=" + encodeStatic(conversationId),
                "source_id=" + encodeStatic(sourceRef.photonId.value),
                "source_revision=" + sourceRef.revision,
                "lexicon=" + lexiconFingerprint,
                "surface=" + encodeStatic(surface),
                "target=" + encodeStatic(targetConceptId),
            ).joinToString("\n"),
            phase = PhotonPhase.ACTIVE,
            semanticMass = 0.05,
            energy = 0.0,
            confidence = 1.0,
            provenance = Provenance(
                source = "personal-language-learning",
                actor = "lifeos",
                createdAt = createdAt,
                parentIds = setOf(sourceRef.photonId),
            ),
            tags = setOf(
                TAG_STATE,
                TAG_PENDING,
                "conversation:" + conversationId,
                "language-learning-lexicon:" + lexiconFingerprint,
                "language-learning-surface:" + StableCognitiveIds.fingerprint(
                    "personal-language-surface/v1",
                    surface,
                ),
                "language-learning-target:" + StableCognitiveIds.fingerprint(
                    "personal-language-target/v1",
                    targetConceptId,
                ),
            ),
        )
    }

    companion object {
        const val TAG_STATE = "language-learning-state"
        const val TAG_PENDING = "language-learning-pending"
        const val TAG_OBSERVATION = "language-learning-observation"
        const val TAG_PENDING_REF_PREFIX = "language-learning-pending-ref:"
        private const val MAX_PENDING_SCAN = 24
        private const val MAX_OBSERVATION_SCAN = 64
        private const val MAX_PENDING_PER_TURN = 3
        private const val MAX_CANDIDATE_OBSERVATIONS = 64

        private fun encodeStatic(value: String): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(value.encodeToByteArray())
    }
}
