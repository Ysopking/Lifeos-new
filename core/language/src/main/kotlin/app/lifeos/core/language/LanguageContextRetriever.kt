package app.lifeos.core.language

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonIndexOrder
import app.lifeos.core.model.PhotonIndexQuery
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.RevisionedPhotonRepository
import java.time.Duration
import java.time.Instant
import java.util.Locale

data class LanguageContextRetrievalTrace(
    val candidateRefs: Int,
    val loadedPhotons: Int,
    val selectedPhotons: Int,
    val queryFingerprints: List<String>,
    val needsFingerprint: String? = null,
    val exactRequestedRefs: Int = 0,
)

data class RetrievedLanguageContext(
    val context: LanguageContext,
    val trace: LanguageContextRetrievalTrace,
)

class LanguageContextRetriever(
    private val photons: RevisionedPhotonRepository,
    private val builder: PhotonLanguageContextBuilder = PhotonLanguageContextBuilder(),
    private val semanticPlanner: SemanticContextQueryPlanner = SemanticContextQueryPlanner(),
    private val maxCandidates: Int = 160,
    private val maxSelected: Int = 96,
    private val excludedTags: Set<String> = setOf("corpus:archive", "language-runtime-state", "language-learning-state", "web-evidence-cache"),
) {
    init {
        require(maxCandidates >= 32)
        require(maxSelected in 16..maxCandidates)
        require(excludedTags.none { it.isBlank() })
    }

    suspend fun retrieve(
        utterance: String,
        now: Instant,
        excludeIds: Set<PhotonId> = emptySet(),
        zoneId: String = "Europe/Berlin",
        needs: LanguageContextRetrievalNeeds = LanguageContextRetrievalNeeds.empty(),
    ): RetrievedLanguageContext {
        val terms = normalizedTerms(utterance)
        val semanticQuery = semanticPlanner.plan(utterance)
        val querySpecs = buildList {
            add("recent" to PhotonIndexQuery(
                latestOnly = true,
                excludedTags = excludedTags,
                order = PhotonIndexOrder.NEWEST_FIRST,
                limit = 64,
            ))
            add("semantic" to PhotonIndexQuery(
                latestOnly = true,
                excludedTags = excludedTags,
                order = PhotonIndexOrder.HIGHEST_SEMANTIC_MASS,
                limit = 48,
            ))
            add("confidence" to PhotonIndexQuery(
                latestOnly = true,
                excludedTags = excludedTags,
                order = PhotonIndexOrder.HIGHEST_CONFIDENCE,
                limit = 24,
            ))
            add("goal" to PhotonIndexQuery(
                allTags = setOf("goal"),
                latestOnly = true,
                excludedTags = excludedTags,
                order = PhotonIndexOrder.NEWEST_FIRST,
                limit = 16,
            ))
            add("matter" to PhotonIndexQuery(
                allTags = setOf("life-matter"),
                latestOnly = true,
                excludedTags = excludedTags,
                order = PhotonIndexOrder.NEWEST_FIRST,
                limit = 16,
            ))
            add("result" to PhotonIndexQuery(
                allTags = setOf("result"),
                latestOnly = true,
                excludedTags = excludedTags,
                order = PhotonIndexOrder.NEWEST_FIRST,
                limit = 16,
            ))
            inferredTag(utterance)?.let { tag ->
                add("inferred:$tag" to PhotonIndexQuery(
                    allTags = setOf(tag),
                    latestOnly = true,
                    excludedTags = excludedTags,
                    order = PhotonIndexOrder.NEWEST_FIRST,
                    limit = 24,
                ))
            }
            needs.stateDimensionKeys.sorted().take(MAX_TARGETED_STATE_DIMENSIONS).forEach { dimension ->
                add("state:$dimension" to PhotonIndexQuery(
                    allTags = setOf("state-dimension:$dimension"),
                    latestOnly = true,
                    excludedTags = excludedTags,
                    order = PhotonIndexOrder.NEWEST_FIRST,
                    limit = 16,
                ))
            }
            needs.episodeRefs.sorted().take(MAX_TARGETED_EPISODES).forEach { episode ->
                val tag = if (
                    episode.startsWith("temporal-episode:") ||
                    episode.startsWith("episode:")
                ) {
                    episode
                } else {
                    "temporal-episode:$episode"
                }
                add("episode:$tag" to PhotonIndexQuery(
                    allTags = setOf(tag),
                    latestOnly = true,
                    excludedTags = excludedTags,
                    order = PhotonIndexOrder.NEWEST_FIRST,
                    limit = 16,
                ))
            }
            needs.semanticTypes.sorted().take(MAX_TARGETED_SEMANTIC_TYPES).forEach { semantic ->
                add("semantic-need:$semantic" to PhotonIndexQuery(
                    allTags = setOf("semantic:$semantic"),
                    latestOnly = true,
                    excludedTags = excludedTags,
                    order = PhotonIndexOrder.HIGHEST_CONFIDENCE,
                    limit = 16,
                ))
            }
            needs.preferredKinds.sorted().take(MAX_TARGETED_KINDS).forEach { kind ->
                add("kind-need:$kind" to PhotonIndexQuery(
                    allTags = setOf(kind),
                    latestOnly = true,
                    excludedTags = excludedTags,
                    order = PhotonIndexOrder.NEWEST_FIRST,
                    limit = 16,
                ))
            }
            needs.realizationKeys.sorted().take(MAX_TARGETED_REALIZATION_KEYS).forEach { key ->
                add("realization:$key" to PhotonIndexQuery(
                    allTags = setOf(key),
                    latestOnly = true,
                    excludedTags = excludedTags,
                    order = PhotonIndexOrder.NEWEST_FIRST,
                    limit = 12,
                ))
            }
        }

        val perQueryRefs = querySpecs.map { (name, query) ->
            name to photons.query(query).filterNot { it.photonId in excludeIds }
        }
        val refs = linkedSetOf<PhotonRevisionRef>()
        needs.exactRevisionRefs
            .sortedWith(
                compareBy<PhotonRevisionRef> { it.photonId.value }
                    .thenBy { it.revision }
            )
            .filterNot { it.photonId in excludeIds }
            .forEach { ref ->
                if (refs.size < maxCandidates) refs += ref
            }
        // First pass guarantees each contextual authority class can contribute before broad pools.
        perQueryRefs.forEach { (_, candidates) ->
            candidates.take(MIN_PER_QUERY).forEach(refs::add)
        }
        // Second pass fills the remaining bounded pool in deterministic query order.
        perQueryRefs.forEach { (_, candidates) ->
            candidates.forEach { ref ->
                if (refs.size < maxCandidates) refs += ref
            }
        }

        val loaded = refs.mapNotNull { photons.load(it) }
        val ranked = loaded
            .map { it to score(it, terms, semanticQuery, needs, now) }
            .sortedWith(
                compareByDescending<Pair<Photon, Double>> { it.second }
                    .thenByDescending { it.first.provenance.createdAt }
                    .thenByDescending { it.first.revision }
                    .thenBy { it.first.id.value }
            )
            .map { it.first }
        val exactRequested = needs.exactRevisionRefs
            .mapNotNull { requested -> loaded.firstOrNull {
                it.id == requested.photonId && it.revision == requested.revision
            } }
            .sortedWith(
                compareBy<Photon> { it.id.value }.thenBy { it.revision }
            )
        val selected = (exactRequested + ranked)
            .distinctBy { PhotonRevisionRef(it.id, it.revision) }
            .take(maxSelected)

        val context = builder.build(
            photons = selected,
            now = now,
            excludeIds = excludeIds,
        ).copy(zoneId = zoneId)

        return RetrievedLanguageContext(
            context = context,
            trace = LanguageContextRetrievalTrace(
                candidateRefs = refs.size,
                loadedPhotons = loaded.size,
                selectedPhotons = selected.size,
                queryFingerprints = querySpecs.map { (name, query) ->
                    listOf(
                        name,
                        query.order.name,
                        query.limit.toString(),
                        query.allTags.sorted().joinToString(","),
                        query.excludedTags.sorted().joinToString(","),
                    ).joinToString(":")
                },
                needsFingerprint = needs
                    .takeIf { it != LanguageContextRetrievalNeeds.empty() }
                    ?.fingerprint,
                exactRequestedRefs = needs.exactRevisionRefs.size,
            ),
        )
    }

    private fun score(
        photon: Photon,
        utteranceTerms: Set<String>,
        semanticQuery: SemanticContextQuery,
        needs: LanguageContextRetrievalNeeds,
        now: Instant,
    ): Double {
        val photonTerms = normalizedTerms(photon.content).take(MAX_CONTENT_TERMS).toSet()
        val overlap = if (utteranceTerms.isEmpty()) 0.0 else {
            utteranceTerms.count(photonTerms::contains).toDouble() / utteranceTerms.size.toDouble()
        }
        val ageHours = Duration.between(photon.provenance.createdAt, now)
            .toMinutes()
            .coerceAtLeast(0L)
            .toDouble() / 60.0
        val recency = (1.0 - ageHours / (24.0 * 30.0)).coerceIn(0.0, 1.0)
        val active = if (photon.phase.name in setOf("ACTIVE", "REFLECTING")) 1.0 else 0.0
        val goalMatter = if (
            "goal" in photon.tags ||
            "life-matter" in photon.tags ||
            photon.tags.any { it.startsWith("matter:") || it.startsWith("goal:") }
        ) 1.0 else 0.0
        val result = if ("result" in photon.tags) 1.0 else 0.0
        val semanticMass = (photon.semanticMass / 8.0).coerceIn(0.0, 1.0)

        val base = (
            overlap * 0.38 +
                recency * 0.20 +
                photon.confidence * 0.12 +
                semanticMass * 0.10 +
                active * 0.08 +
                goalMatter * 0.08 +
                result * 0.04
            ).coerceIn(0.0, 1.0)

        val conceptTags = photon.tags
            .filter { it.startsWith("concept:") }
            .mapTo(linkedSetOf()) { it.substringAfter(':') }
        val semanticTags = photon.tags
            .filter { it.startsWith("semantic:") }
            .mapTo(linkedSetOf()) { normalizeFieldText(it.substringAfter(':')) }
        val conceptMatch = if (semanticQuery.concepts.isEmpty()) 0.0 else {
            semanticQuery.concepts.count(conceptTags::contains).toDouble() /
                semanticQuery.concepts.size.toDouble()
        }
        val semanticMatch = if (semanticQuery.semanticTypes.isEmpty()) 0.0 else {
            semanticQuery.semanticTypes.count(semanticTags::contains).toDouble() /
                semanticQuery.semanticTypes.size.toDouble()
        }
        val kindMatch = if (semanticQuery.preferredKinds.any { it in photon.tags }) 1.0 else 0.0
        val structural = (
            conceptMatch * 0.50 +
                semanticMatch * 0.35 +
                kindMatch * 0.15
            ).coerceIn(0.0, 1.0)

        val exactRevisionBoost = if (
            PhotonRevisionRef(photon.id, photon.revision) in needs.exactRevisionRefs
        ) 0.28 else 0.0
        val stateDimensionBoost = if (
            needs.stateDimensionKeys.any { "state-dimension:$it" in photon.tags }
        ) 0.22 else 0.0
        val episodeBoost = if (
            needs.episodeRefs.any { episode ->
                episode in photon.tags ||
                    "temporal-episode:$episode" in photon.tags ||
                    "episode:$episode" in photon.tags
            }
        ) 0.18 else 0.0
        val realizationBoost = if (
            needs.realizationKeys.any(photon.tags::contains)
        ) 0.10 else 0.0

        return (
            base +
                structural * 0.12 +
                exactRevisionBoost +
                stateDimensionBoost +
                episodeBoost +
                realizationBoost
            ).coerceIn(0.0, 1.0)
    }

    private fun inferredTag(text: String): String? {
        val normalized = text.lowercase(Locale.ROOT)
        return when {
            listOf("bild", "foto", "image", "photo").any(normalized::contains) -> "image"
            listOf("bescheid", "behörde", "behoerde", "jobcenter").any(normalized::contains) -> "life-matter"
            listOf("ziel", "goal", "weiter", "fortsetzen").any(normalized::contains) -> "goal"
            listOf("ergebnis", "result", "antwort").any(normalized::contains) -> "result"
            else -> null
        }
    }

    private fun normalizedTerms(text: String): Set<String> =
        TERM_REGEX.findAll(text)
            .map { it.value.lowercase(Locale.ROOT).replace("ß", "ss") }
            .filter { it.length > 1 && it !in STOP_WORDS }
            .take(MAX_CONTENT_TERMS)
            .toSet()

    private companion object {
        const val MAX_CONTENT_TERMS = 128
        const val MIN_PER_QUERY = 8
        const val MAX_TARGETED_STATE_DIMENSIONS = 12
        const val MAX_TARGETED_EPISODES = 12
        const val MAX_TARGETED_SEMANTIC_TYPES = 8
        const val MAX_TARGETED_KINDS = 8
        const val MAX_TARGETED_REALIZATION_KEYS = 12
        val TERM_REGEX = Regex("[\\p{L}\\p{N}]+")
        val STOP_WORDS = setOf(
            "der", "die", "das", "den", "dem", "des", "ein", "eine", "einen", "und", "oder",
            "ich", "du", "wir", "sie", "es", "ist", "sind", "mit", "von", "zu", "in", "auf",
            "the", "a", "an", "and", "or", "i", "you", "we", "it", "is", "are", "with", "from", "to",
        )
    }
}
