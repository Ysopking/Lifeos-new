package app.lifeos.core.runtime.life

import app.lifeos.core.model.CanonicalPhotonState
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonPhase
import app.lifeos.core.model.PhotonRelation
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.RelationType
import app.lifeos.core.model.StableCognitiveIds
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * Four temporal memory stages. Source evidence is never deleted or rewritten by this runtime.
 * Compression products are deterministic derived records/photons that retain source lineage.
 */
enum class MemoryStage {
    HOT,
    WARM,
    COLD,
    CRYSTALLIZED,
}

enum class MemoryProtectionReason {
    OPEN_DEBT,
    CONTRACT,
    DEADLINE,
    UNRESOLVED_GOAL,
    IMPORTANT_RELATIONSHIP,
    EXPECTED_EVENT,
}

enum class MemoryAtomKind {
    PERSON,
    RELATIONSHIP,
    EVENT,
    STATEMENT,
    OBLIGATION,
    AMOUNT,
    DEADLINE,
    PLACE,
    DECISION,
    OUTCOME,
    GOAL,
    FACT,
}

data class MemoryUsageProfile(
    val photonId: PhotonId,
    val lastAccessAt: Instant,
    val accessCount: Long = 0,
    val goalRelevance: Double = 0.0,
    val relationshipWeight: Double = 0.0,
    val futureRelevance: Double = 0.0,
    val seinRelevance: Double = 0.0,
) {
    init {
        require(accessCount >= 0) { "accessCount must not be negative" }
        listOf(goalRelevance, relationshipWeight, futureRelevance, seinRelevance).forEach {
            require(it.isFinite() && it in 0.0..1.0) { "memory relevance values must be in 0..1" }
        }
    }
}

/** Immutable access projection; a durable store can persist snapshots without changing policy semantics. */
data class MemoryAccessLedger(
    val profiles: Map<PhotonId, MemoryUsageProfile> = emptyMap(),
) {
    fun profileFor(photon: Photon): MemoryUsageProfile = profiles[photon.id]
        ?: MemoryUsageProfile(photon.id, photon.provenance.createdAt)

    fun recordAccess(
        photonId: PhotonId,
        at: Instant,
        goalRelevance: Double? = null,
        relationshipWeight: Double? = null,
        futureRelevance: Double? = null,
        seinRelevance: Double? = null,
    ): MemoryAccessLedger {
        val previous = profiles[photonId]
        val next = MemoryUsageProfile(
            photonId = photonId,
            lastAccessAt = maxOf(previous?.lastAccessAt ?: at, at),
            accessCount = Math.addExact(previous?.accessCount ?: 0L, 1L),
            goalRelevance = goalRelevance ?: previous?.goalRelevance ?: 0.0,
            relationshipWeight = relationshipWeight ?: previous?.relationshipWeight ?: 0.0,
            futureRelevance = futureRelevance ?: previous?.futureRelevance ?: 0.0,
            seinRelevance = seinRelevance ?: previous?.seinRelevance ?: 0.0,
        )
        return copy(profiles = profiles + (photonId to next))
    }
}

data class LongTermMemoryPolicy(
    val hotIdle: Duration = Duration.ofDays(3),
    val warmIdle: Duration = Duration.ofDays(30),
    val coldIdle: Duration = Duration.ofDays(180),
    val hotScore: Double = 0.82,
    val warmScore: Double = 0.55,
    val rehydrateScore: Double = 0.72,
    val maxAtomCharacters: Int = 1024,
    val maxCrystalCharacters: Int = 2048,
) {
    init {
        require(!hotIdle.isNegative && !hotIdle.isZero)
        require(warmIdle > hotIdle)
        require(coldIdle > warmIdle)
        require(hotScore in 0.0..1.0 && warmScore in 0.0..1.0 && rehydrateScore in 0.0..1.0)
        require(hotScore >= warmScore)
        require(maxAtomCharacters in 64..16_384)
        require(maxCrystalCharacters in 128..65_536)
    }
}

data class MemoryCompactionDecision(
    val photonId: PhotonId,
    val fromStage: MemoryStage,
    val toStage: MemoryStage,
    val inactivity: Duration,
    val relevanceScore: Double,
    val protections: Set<MemoryProtectionReason>,
    val reason: String,
    val decisionId: String,
)

data class MemoryEpisode(
    val episodeId: String,
    val sourcePhotonIds: Set<PhotonId>,
    val stage: MemoryStage,
    val startedAt: Instant,
    val endedAt: Instant,
    val semanticKeys: Set<String>,
)

data class MemoryAtom(
    val atomId: String,
    val kind: MemoryAtomKind,
    val content: String,
    val sourcePhotonIds: Set<PhotonId>,
    val sourceStateHash: String,
    val episodeId: String,
    val confidence: Double,
    val observedAt: Instant,
    val stage: MemoryStage,
    val producerVersion: String = LongTermMemoryEngine.RUNTIME_VERSION,
)

data class MemoryCrystal(
    val crystalId: String,
    val semanticCore: String,
    val atomIds: Set<String>,
    val sourcePhotonIds: Set<PhotonId>,
    val startedAt: Instant,
    val endedAt: Instant,
    val confidence: Double,
    val producerVersion: String = LongTermMemoryEngine.RUNTIME_VERSION,
)

data class LongTermMemoryProjection(
    val decisions: List<MemoryCompactionDecision>,
    val episodes: List<MemoryEpisode>,
    val atoms: List<MemoryAtom>,
    val crystals: List<MemoryCrystal>,
    val derivedPhotons: List<Photon>,
    val evaluatedAt: Instant,
    val fingerprint: String,
) {
    fun stageOf(photonId: PhotonId): MemoryStage? = decisions.firstOrNull { it.photonId == photonId }?.toStage
}

/**
 * Deterministic temporal memory projection:
 * HOT -> WARM episode -> COLD atoms -> CRYSTALLIZED semantic core.
 *
 * The encrypted/source Photon repository remains authoritative. This engine only derives compact
 * memory representations and explicit stage decisions. Old evidence can therefore be rehydrated
 * and reinterpreted by newer module versions without losing its original provenance.
 */
class LongTermMemoryEngine(
    private val policy: LongTermMemoryPolicy = LongTermMemoryPolicy(),
) {
    fun project(
        photons: Collection<Photon>,
        accessLedger: MemoryAccessLedger = MemoryAccessLedger(),
        now: Instant,
    ): LongTermMemoryProjection {
        val latest = photons
            .groupBy { it.id }
            .mapValues { (_, revisions) -> revisions.maxBy { it.revision } }
            .values
            .sortedWith(compareBy<Photon> { it.provenance.createdAt }.thenBy { it.id.value })

        val decisions = latest.map { photon -> decide(photon, accessLedger.profileFor(photon), now) }
        val decisionsById = decisions.associateBy { it.photonId }
        val episodes = buildEpisodes(latest, decisionsById)
        val episodeBySource = episodes.flatMap { episode ->
            episode.sourcePhotonIds.map { it to episode }
        }.toMap()

        val atoms = latest.flatMap { photon ->
            val stage = decisionsById.getValue(photon.id).toStage
            if (stage < MemoryStage.COLD) emptyList()
            else atomize(photon, episodeBySource.getValue(photon.id), stage)
        }.sortedBy { it.atomId }

        val crystals = episodes
            .filter { it.stage == MemoryStage.CRYSTALLIZED }
            .mapNotNull { episode -> crystallize(episode, atoms.filter { it.episodeId == episode.episodeId }) }
            .sortedBy { it.crystalId }

        val derived = buildDerivedPhotons(atoms, crystals, now)
        val fingerprint = StableCognitiveIds.fingerprint(
            "long-term-memory-projection/v1",
            now.toString(),
            *decisions.flatMap {
                listOf(it.decisionId, it.photonId.value, it.toStage.name, it.reason)
            }.toTypedArray(),
            *atoms.map { it.atomId }.toTypedArray(),
            *crystals.map { it.crystalId }.toTypedArray(),
        )
        return LongTermMemoryProjection(decisions, episodes, atoms, crystals, derived, now, fingerprint)
    }

    /**
     * Incremental projection for hot-path memory changes. Unchanged decisions and episode material
     * are preserved; only changed source ids and their affected episode/crystal are recomputed.
     */
    fun projectDelta(
        current: LongTermMemoryProjection,
        changed: Collection<Photon>,
        accessChanges: MemoryAccessLedger,
        now: Instant,
    ): LongTermMemoryProjection {
        if (changed.isEmpty()) return current

        val latestChanged = changed
            .groupBy { it.id }
            .mapValues { (_, revisions) -> revisions.maxBy { it.revision } }
            .values
            .sortedWith(compareBy<Photon> { it.provenance.createdAt }.thenBy { it.id.value })
        val changedIds = latestChanged.mapTo(linkedSetOf()) { it.id }
        val replacementDecisions = latestChanged.associate { photon ->
            photon.id to decide(photon, accessChanges.profileFor(photon), now)
        }
        val decisions = (
            current.decisions.filterNot { it.photonId in changedIds } +
                replacementDecisions.values
            ).sortedBy { it.photonId.value }
        val decisionsById = decisions.associateBy { it.photonId }

        val episodes = current.episodes.toMutableList()
        val episodeIdRemap = linkedMapOf<String, String?>()
        latestChanged.forEach { photon ->
            val decision = replacementDecisions.getValue(photon.id)
            val existingIndex = episodes.indexOfFirst { photon.id in it.sourcePhotonIds }
            val existing = episodes.getOrNull(existingIndex)

            if (decision.toStage < MemoryStage.WARM) {
                if (existing != null) {
                    val remaining = existing.sourcePhotonIds - photon.id
                    if (remaining.isEmpty()) {
                        episodes.removeAt(existingIndex)
                        episodeIdRemap[existing.episodeId] = null
                    } else {
                        val stage = remaining.mapNotNull { decisionsById[it]?.toStage }
                            .maxOrNull() ?: MemoryStage.WARM
                        val newId = StableCognitiveIds.fingerprint(
                            "memory-episode/v1",
                            episodeKey(existing),
                            *remaining.map { it.value }.sorted().toTypedArray(),
                        )
                        episodes[existingIndex] = existing.copy(
                            episodeId = newId,
                            sourcePhotonIds = remaining,
                            stage = stage,
                        )
                        episodeIdRemap[existing.episodeId] = newId
                    }
                }
            } else if (existing != null) {
                episodes[existingIndex] = existing.copy(
                    stage = maxOf(existing.stage, decision.toStage),
                    startedAt = minOf(existing.startedAt, photon.provenance.createdAt),
                    endedAt = maxOf(existing.endedAt, photon.provenance.createdAt),
                    semanticKeys = (existing.semanticKeys + semanticKeys(photon)).toSortedSet(),
                )
            } else {
                val created = buildEpisodes(
                    listOf(photon),
                    mapOf(photon.id to decision),
                ).single()
                episodes += created
            }
        }

        val canonicalEpisodes = episodes.sortedBy { it.episodeId }
        val episodeBySource = canonicalEpisodes.flatMap { episode ->
            episode.sourcePhotonIds.map { it to episode }
        }.toMap()

        val retainedAtoms = current.atoms
            .filterNot { atom -> atom.sourcePhotonIds.any { it in changedIds } }
            .mapNotNull { atom ->
                when (val remap = episodeIdRemap[atom.episodeId]) {
                    null -> if (atom.episodeId in episodeIdRemap) null else atom
                    else -> atom.copy(episodeId = remap)
                }
            }
        val changedAtoms = latestChanged.flatMap { photon ->
            val stage = replacementDecisions.getValue(photon.id).toStage
            val episode = episodeBySource[photon.id]
            if (stage < MemoryStage.COLD || episode == null) emptyList()
            else atomize(photon, episode, stage)
        }
        val atoms = (retainedAtoms + changedAtoms).sortedBy { it.atomId }

        val affectedEpisodeIds = buildSet {
            current.episodes
                .filter { episode -> episode.sourcePhotonIds.any { it in changedIds } }
                .forEach { add(it.episodeId) }
            canonicalEpisodes
                .filter { episode -> episode.sourcePhotonIds.any { it in changedIds } }
                .forEach { add(it.episodeId) }
            addAll(episodeIdRemap.keys)
            addAll(episodeIdRemap.values.filterNotNull())
        }
        val retainedCrystals = current.crystals.filterNot { crystal ->
            crystal.sourcePhotonIds.any { it in changedIds } ||
                crystal.atomIds.any { atomId -> current.atoms.any { it.atomId == atomId && it.episodeId in affectedEpisodeIds } }
        }
        val changedCrystals = canonicalEpisodes
            .filter { it.stage == MemoryStage.CRYSTALLIZED && it.episodeId in affectedEpisodeIds }
            .mapNotNull { episode ->
                crystallize(episode, atoms.filter { it.episodeId == episode.episodeId })
            }
        val crystals = (retainedCrystals + changedCrystals).sortedBy { it.crystalId }
        val derived = buildDerivedPhotons(atoms, crystals, now)
        val fingerprint = StableCognitiveIds.fingerprint(
            "long-term-memory-projection/v2-delta",
            now.toString(),
            *decisions.flatMap {
                listOf(it.decisionId, it.photonId.value, it.toStage.name, it.reason)
            }.toTypedArray(),
            *atoms.map { it.atomId }.toTypedArray(),
            *crystals.map { it.crystalId }.toTypedArray(),
        )
        return LongTermMemoryProjection(
            decisions = decisions,
            episodes = canonicalEpisodes,
            atoms = atoms,
            crystals = crystals,
            derivedPhotons = derived,
            evaluatedAt = now,
            fingerprint = fingerprint,
        )
    }

    private fun episodeKey(episode: MemoryEpisode): String {
        val date = episode.startedAt.atZone(ZoneOffset.UTC).toLocalDate().toString()
        val domain = episode.semanticKeys.firstOrNull() ?: "general"
        return "$date:$domain"
    }

    /** Promote a compacted item when new present relevance exceeds the configured threshold. */
    fun rehydrateTarget(current: MemoryStage, relevanceScore: Double): MemoryStage {
        require(relevanceScore.isFinite() && relevanceScore in 0.0..1.0)
        if (relevanceScore < policy.rehydrateScore) return current
        return when (current) {
            MemoryStage.CRYSTALLIZED -> MemoryStage.WARM
            MemoryStage.COLD -> MemoryStage.WARM
            MemoryStage.WARM -> MemoryStage.HOT
            MemoryStage.HOT -> MemoryStage.HOT
        }
    }

    private fun decide(photon: Photon, usage: MemoryUsageProfile, now: Instant): MemoryCompactionDecision {
        val inactivity = if (now.isAfter(usage.lastAccessAt)) Duration.between(usage.lastAccessAt, now) else Duration.ZERO
        val protections = protectionReasons(photon)
        val score = relevanceScore(photon, usage)
        val accessBoost = when {
            usage.accessCount >= 16 -> 0.16
            usage.accessCount >= 4 -> 0.08
            usage.accessCount > 0 -> 0.03
            else -> 0.0
        }
        val effectiveScore = (score + accessBoost).coerceIn(0.0, 1.0)

        var stage = when {
            inactivity <= policy.hotIdle || effectiveScore >= policy.hotScore -> MemoryStage.HOT
            inactivity <= policy.warmIdle || effectiveScore >= policy.warmScore -> MemoryStage.WARM
            inactivity <= policy.coldIdle -> MemoryStage.COLD
            else -> MemoryStage.CRYSTALLIZED
        }

        // Age by itself must never crystallize unresolved/high-consequence life evidence.
        if (stage == MemoryStage.CRYSTALLIZED && protections.isNotEmpty()) stage = MemoryStage.COLD
        // Strong future/goal relevance reactivates compact memories before a deadline becomes pressure.
        if (stage >= MemoryStage.COLD && maxOf(usage.goalRelevance, usage.futureRelevance) >= policy.rehydrateScore) {
            stage = MemoryStage.WARM
        }

        val reason = when {
            stage == MemoryStage.HOT -> "active-or-high-relevance"
            stage == MemoryStage.WARM && maxOf(usage.goalRelevance, usage.futureRelevance) >= policy.rehydrateScore ->
                "rehydrated-by-future-or-goal-relevance"
            stage == MemoryStage.WARM -> "episodic-compaction"
            stage == MemoryStage.COLD && protections.isNotEmpty() -> "protected-atomized-long-term-memory"
            stage == MemoryStage.COLD -> "atomized-long-term-memory"
            else -> "crystallized-deep-memory"
        }
        val fromStage = inferredCurrentStage(photon)
        return MemoryCompactionDecision(
            photonId = photon.id,
            fromStage = fromStage,
            toStage = stage,
            inactivity = inactivity,
            relevanceScore = effectiveScore,
            protections = protections,
            reason = reason,
            decisionId = StableCognitiveIds.fingerprint(
                "memory-compaction-decision/v1",
                photon.id.value,
                photon.revision.toString(),
                CanonicalPhotonState.inputHash(photon).value,
                usage.lastAccessAt.toString(),
                usage.accessCount.toString(),
                stage.name,
                protections.map { it.name }.sorted().joinToString("\u0000"),
                java.lang.Double.toHexString(effectiveScore),
            ),
        )
    }

    private fun inferredCurrentStage(photon: Photon): MemoryStage = when {
        "memory-stage:crystallized" in photon.tags -> MemoryStage.CRYSTALLIZED
        "memory-stage:cold" in photon.tags || photon.phase == PhotonPhase.ARCHIVED -> MemoryStage.COLD
        "memory-stage:warm" in photon.tags -> MemoryStage.WARM
        else -> MemoryStage.HOT
    }

    private fun relevanceScore(photon: Photon, usage: MemoryUsageProfile): Double {
        val mass = photon.semanticMass.coerceIn(0.0, 1.0)
        val confidence = photon.confidence.coerceIn(0.0, 1.0)
        return (
            mass * 0.28 +
                confidence * 0.16 +
                usage.goalRelevance * 0.18 +
                usage.relationshipWeight * 0.12 +
                usage.futureRelevance * 0.16 +
                usage.seinRelevance * 0.10
            ).coerceIn(0.0, 1.0)
    }

    private fun protectionReasons(photon: Photon): Set<MemoryProtectionReason> {
        val tags = photon.tags.map { it.lowercase() }.toSet()
        val closed = tags.any { it in setOf("resolved", "closed", "paid", "completed", "goal:resolved") }
        return buildSet {
            if (!closed && tags.any { it in setOf("debt", "fact:debt", "fact:claim", "claim") }) add(MemoryProtectionReason.OPEN_DEBT)
            if (!closed && tags.any { it == "contract" || it == "fact:obligation" || it.startsWith("legal") }) add(MemoryProtectionReason.CONTRACT)
            if (!closed && tags.any { it == "deadline" || it == "fact:deadline" || it.startsWith("deadline:") }) add(MemoryProtectionReason.DEADLINE)
            if (!closed && tags.any { it == "goal" || it.startsWith("goal:") }) add(MemoryProtectionReason.UNRESOLVED_GOAL)
            if (tags.any { it == "important-relationship" || it == "relationship:important" }) add(MemoryProtectionReason.IMPORTANT_RELATIONSHIP)
            if (!closed && tags.any { it == "expected-event" || it == "future-actionable" || it.startsWith("future-horizon:") }) add(MemoryProtectionReason.EXPECTED_EVENT)
        }
    }

    private fun buildEpisodes(
        photons: List<Photon>,
        decisions: Map<PhotonId, MemoryCompactionDecision>,
    ): List<MemoryEpisode> {
        val candidates = photons.filter { decisions.getValue(it.id).toStage >= MemoryStage.WARM }
        return candidates.groupBy { episodeKey(it) }.map { (key, group) ->
            val ordered = group.sortedWith(compareBy<Photon> { it.provenance.createdAt }.thenBy { it.id.value })
            val stages = ordered.map { decisions.getValue(it.id).toStage }
            val stage = stages.maxOrNull() ?: MemoryStage.WARM
            val semanticKeys = ordered.flatMap { semanticKeys(it) }.toSortedSet()
            MemoryEpisode(
                episodeId = StableCognitiveIds.fingerprint(
                    "memory-episode/v1",
                    key,
                    *ordered.map { it.id.value }.toTypedArray(),
                ),
                sourcePhotonIds = ordered.map { it.id }.toSet(),
                stage = stage,
                startedAt = ordered.first().provenance.createdAt,
                endedAt = ordered.last().provenance.createdAt,
                semanticKeys = semanticKeys,
            )
        }.sortedBy { it.episodeId }
    }

    private fun episodeKey(photon: Photon): String {
        val date = photon.provenance.createdAt.atZone(ZoneOffset.UTC).toLocalDate().toString()
        val domain = semanticKeys(photon).firstOrNull() ?: "general"
        return "$date:$domain"
    }

    private fun semanticKeys(photon: Photon): Set<String> = photon.tags
        .map { it.lowercase() }
        .filterNot { it.startsWith("causal-trace:") || it.startsWith("memory-stage:") }
        .filter { tag ->
            tag.startsWith("fact:") || tag.startsWith("goal") || tag.startsWith("person") ||
                tag.startsWith("relationship") || tag.startsWith("legal") || tag.startsWith("debt") ||
                tag.startsWith("business") || tag.startsWith("future") || tag.startsWith("project")
        }
        .toSortedSet()

    private fun atomize(photon: Photon, episode: MemoryEpisode, stage: MemoryStage): List<MemoryAtom> {
        val segments = photon.content
            .split(SEGMENT_BOUNDARY)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .ifEmpty { listOf(photon.content.trim()) }
        val sourceHash = CanonicalPhotonState.inputHash(photon).value
        return segments.mapIndexed { ordinal, raw ->
            val content = raw.take(policy.maxAtomCharacters)
            val kind = atomKind(content, photon.tags)
            MemoryAtom(
                atomId = StableCognitiveIds.fingerprint(
                    "memory-atom/v1",
                    photon.id.value,
                    photon.revision.toString(),
                    sourceHash,
                    ordinal.toString(),
                    kind.name,
                    content,
                ),
                kind = kind,
                content = content,
                sourcePhotonIds = setOf(photon.id),
                sourceStateHash = sourceHash,
                episodeId = episode.episodeId,
                confidence = photon.confidence,
                observedAt = photon.provenance.createdAt,
                stage = stage,
            )
        }
    }

    private fun atomKind(content: String, sourceTags: Set<String>): MemoryAtomKind {
        val tags = sourceTags.map { it.lowercase() }.toSet()
        val text = content.lowercase()
        return when {
            tags.any { it == "fact:deadline" || it == "deadline" } || text.contains("frist") || text.contains("deadline") -> MemoryAtomKind.DEADLINE
            tags.any { it == "fact:obligation" || it == "obligation" } || text.contains("verpflicht") -> MemoryAtomKind.OBLIGATION
            tags.any { it == "fact:amount" || it == "amount" } || CURRENCY_PATTERN.containsMatchIn(text) -> MemoryAtomKind.AMOUNT
            tags.any { it.startsWith("person") } -> MemoryAtomKind.PERSON
            tags.any { it.startsWith("relationship") } -> MemoryAtomKind.RELATIONSHIP
            tags.any { it == "goal" || it.startsWith("goal:") } -> MemoryAtomKind.GOAL
            tags.any { it.contains("decision") } || text.contains("entschied") || text.contains("decision") -> MemoryAtomKind.DECISION
            tags.any { it.contains("outcome") } || text.contains("ergebnis") || text.contains("outcome") -> MemoryAtomKind.OUTCOME
            tags.any { it.contains("place") || it.contains("location") } -> MemoryAtomKind.PLACE
            tags.any { it.startsWith("fact:") } -> MemoryAtomKind.FACT
            tags.any { it.contains("event") } -> MemoryAtomKind.EVENT
            else -> MemoryAtomKind.STATEMENT
        }
    }

    private fun crystallize(episode: MemoryEpisode, atoms: List<MemoryAtom>): MemoryCrystal? {
        if (atoms.isEmpty()) return null
        val ordered = atoms.sortedWith(compareBy<MemoryAtom> { it.kind.name }.thenBy { it.atomId })
        val semanticCore = ordered
            .joinToString(" | ") { "${it.kind.name.lowercase()}:${it.content}" }
            .take(policy.maxCrystalCharacters)
            .ifBlank { return null }
        val sourceIds = ordered.flatMap { it.sourcePhotonIds }.toSet()
        val confidence = ordered.map { it.confidence }.average().coerceIn(0.0, 1.0)
        return MemoryCrystal(
            crystalId = StableCognitiveIds.fingerprint(
                "memory-crystal/v1",
                episode.episodeId,
                *ordered.map { it.atomId }.toTypedArray(),
            ),
            semanticCore = semanticCore,
            atomIds = ordered.map { it.atomId }.toSet(),
            sourcePhotonIds = sourceIds,
            startedAt = episode.startedAt,
            endedAt = episode.endedAt,
            confidence = confidence,
        )
    }

    private fun buildDerivedPhotons(
        atoms: List<MemoryAtom>,
        crystals: List<MemoryCrystal>,
        now: Instant,
    ): List<Photon> {
        val atomPhotons = atoms.map { atom ->
            Photon(
                id = PhotonId("memory-atom:${atom.atomId}"),
                content = atom.content,
                mimeType = "application/vnd.lifeos.memory-atom+text",
                phase = PhotonPhase.CONVERGED,
                semanticMass = 0.35,
                energy = 0.1,
                confidence = atom.confidence,
                provenance = Provenance(
                    source = "lifeos-long-term-memory",
                    actor = "lifeos",
                    createdAt = now,
                    parentIds = atom.sourcePhotonIds,
                ),
                relations = atom.sourcePhotonIds.map { PhotonRelation(it, RelationType.DERIVED_FROM) }.toSet(),
                tags = setOf("memory-atom", "memory-kind:${atom.kind.name.lowercase()}", "memory-stage:${atom.stage.name.lowercase()}"),
            )
        }
        val crystalPhotons = crystals.map { crystal ->
            val atomIds = crystal.atomIds.map { PhotonId("memory-atom:$it") }.toSet()
            Photon(
                id = PhotonId("memory-crystal:${crystal.crystalId}"),
                content = crystal.semanticCore,
                mimeType = "application/vnd.lifeos.memory-crystal+text",
                phase = PhotonPhase.CONVERGED,
                semanticMass = 0.7,
                energy = 0.05,
                confidence = crystal.confidence,
                provenance = Provenance(
                    source = "lifeos-long-term-memory",
                    actor = "lifeos",
                    createdAt = now,
                    parentIds = crystal.sourcePhotonIds + atomIds,
                ),
                relations = crystal.sourcePhotonIds.map { PhotonRelation(it, RelationType.DERIVED_FROM) }.toSet() +
                    atomIds.map { PhotonRelation(it, RelationType.REFERENCES) },
                tags = setOf("memory-crystal", "memory-stage:crystallized"),
            )
        }
        return (atomPhotons + crystalPhotons).sortedBy { it.id.value }
    }

    companion object {
        const val RUNTIME_VERSION: String = "lifeos-long-term-memory-v1"
        private val SEGMENT_BOUNDARY = Regex("(?<=[.!?;])\\s+|\\r?\\n+")
        private val CURRENCY_PATTERN = Regex("(?:€|eur|euro|usd|\\$|gbp|£)\\s*\\d|\\d[\\d.,]*\\s*(?:€|eur|euro|usd|\\$|gbp|£)")
    }
}
