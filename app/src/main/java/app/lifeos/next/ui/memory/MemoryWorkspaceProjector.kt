package app.lifeos.next.ui.memory

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.runtime.life.DurableLifeMemorySnapshot
import app.lifeos.core.runtime.life.MemoryAtom
import app.lifeos.core.runtime.life.MemoryCrystal
import app.lifeos.core.runtime.life.MemoryEpisode
import app.lifeos.core.runtime.life.MemoryStage
import app.lifeos.core.runtime.life.isLifeMemoryManagementPhoton
import java.util.Locale

/** Pure read-only projection from durable LIFEOS memory + current authoritative Photon evidence. */
object MemoryWorkspaceProjector {
    fun project(
        snapshot: DurableLifeMemorySnapshot?,
        photons: Iterable<Photon>,
        query: String = "",
    ): MemoryWorkspaceUiModel = project(
        snapshot = snapshot,
        searchIndex = MemorySearchIndex.build(photons),
        query = query,
    )

    fun project(
        snapshot: DurableLifeMemorySnapshot?,
        searchIndex: MemorySearchIndex,
        query: String = "",
    ): MemoryWorkspaceUiModel {
        val latest = searchIndex.latestRevisions()
        val sourceById = latest
            .filterNot(::isLifeMemoryManagementPhoton)
            .associateBy { it.id }
        val normalizedQuery = query.trim().lowercase(Locale.ROOT)
        val projection = snapshot?.memory
        val stageById = projection?.decisions.orEmpty().associate { it.photonId to it.toStage }

        val projectedNow = stageById.entries
            .asSequence()
            .filter { (_, stage) -> stage == MemoryStage.HOT || stage == MemoryStage.WARM }
            .mapNotNull { (id, stage) -> sourceById[id]?.takeIf(::browsableSource)?.toUi(stage, false) }

        val newEvidence = sourceById.values
            .asSequence()
            .filter(::browsableSource)
            .filter { it.id !in stageById }
            .map { it.toUi(stage = null, isNew = true) }

        val now = (projectedNow + newEvidence)
            .filter { source ->
                normalizedQuery.isBlank() || searchIndex.matchesSource(source.photonId, normalizedQuery)
            }
            .sortedWith(
                compareByDescending<MemorySourceUi> { it.createdAt }
                    .thenBy { it.photonId.value }
            )
            .toList()

        val atoms = projection?.atoms.orEmpty()
            .asSequence()
            .filter { atom -> atom.matches(normalizedQuery, sourceById, searchIndex) }
            .map { atom -> atom.toUi(sourceById) }
            .toList()
        val topicGroups = atoms
            .groupBy { it.kind }
            .entries
            .sortedBy { it.key.ordinal }
            .map { (kind, grouped) ->
                MemoryTopicGroupUi(
                    kind = kind,
                    label = kind.germanLabel(),
                    atoms = grouped.sortedWith(
                        compareByDescending<MemoryAtomUi> { it.observedAt }
                            .thenBy { it.atomId }
                    ),
                )
            }

        val crystals = projection?.crystals.orEmpty()
            .asSequence()
            .filter { crystal -> crystal.matches(normalizedQuery, sourceById, searchIndex) }
            .map { crystal -> crystal.toUi(sourceById) }
            .sortedWith(
                compareByDescending<MemoryCrystalUi> { it.endedAt }
                    .thenBy { it.crystalId }
            )
            .toList()

        val episodes = projection?.episodes.orEmpty()
            .asSequence()
            .filter { episode -> episode.matches(normalizedQuery, sourceById, searchIndex) }
            .map { episode -> episode.toUi(sourceById) }
            .sortedWith(
                compareByDescending<MemoryEpisodeUi> { it.endedAt }
                    .thenByDescending { it.startedAt }
                    .thenBy { it.episodeId }
            )
            .toList()

        return MemoryWorkspaceUiModel(
            projectionAvailable = snapshot != null,
            projectionEvaluatedAt = projection?.evaluatedAt,
            authoritativePhotonCount = snapshot?.authoritativePhotonCount ?: sourceById.size,
            now = now,
            topicGroups = topicGroups,
            crystals = crystals,
            episodes = episodes,
            query = query,
        )
    }

    fun resolveSource(
        photonId: PhotonId,
        photons: Iterable<Photon>,
        snapshot: DurableLifeMemorySnapshot?,
    ): MemorySourceUi? = resolveSource(
        photonId = photonId,
        searchIndex = MemorySearchIndex.build(photons),
        snapshot = snapshot,
    )

    fun resolveSource(
        photonId: PhotonId,
        searchIndex: MemorySearchIndex,
        snapshot: DurableLifeMemorySnapshot?,
    ): MemorySourceUi? {
        val source = searchIndex.source(photonId) ?: return null
        if (isLifeMemoryManagementPhoton(source)) return null
        return source.toUi(
            snapshot?.memory?.stageOf(photonId),
            isNew = snapshot?.memory?.stageOf(photonId) == null,
        )
    }

    private fun browsableSource(photon: Photon): Boolean {
        if (isLifeMemoryManagementPhoton(photon)) return false
        if ("causal-ledger" in photon.tags || "life-source-gap" in photon.tags) return false
        if ("goal" in photon.tags || "scene-graph" in photon.tags) return false
        if ("tool-request" in photon.tags || "tool-generation-approval" in photon.tags) return false
        if ("perception-raw-source" in photon.tags) return false
        if (photon.tags.any { it.startsWith("ingress-mode:") }) return false
        return true
    }

    private fun Photon.toUi(stage: MemoryStage?, isNew: Boolean): MemorySourceUi = MemorySourceUi(
        photonId = id,
        content = content,
        mimeType = mimeType,
        stage = stage,
        isNew = isNew,
        confidence = confidence,
        createdAt = provenance.createdAt,
        source = provenance.source,
        actor = provenance.actor,
        tags = tags.toSortedSet(),
        parentCount = provenance.parentIds.size,
        relationCount = relations.size,
    )

    private fun MemoryAtom.matches(
        query: String,
        sources: Map<PhotonId, Photon>,
        searchIndex: MemorySearchIndex,
    ): Boolean =
        query.isBlank() ||
            content.lowercase(Locale.ROOT).contains(query) ||
            kind.name.lowercase(Locale.ROOT).contains(query) ||
            sourcePhotonIds.any { id -> id in sources && searchIndex.matchesSource(id, query) }

    private fun MemoryCrystal.matches(
        query: String,
        sources: Map<PhotonId, Photon>,
        searchIndex: MemorySearchIndex,
    ): Boolean =
        query.isBlank() ||
            semanticCore.lowercase(Locale.ROOT).contains(query) ||
            sourcePhotonIds.any { id -> id in sources && searchIndex.matchesSource(id, query) }

    private fun MemoryEpisode.matches(
        query: String,
        sources: Map<PhotonId, Photon>,
        searchIndex: MemorySearchIndex,
    ): Boolean =
        query.isBlank() ||
            semanticKeys.any { it.lowercase(Locale.ROOT).contains(query) } ||
            sourcePhotonIds.any { id -> id in sources && searchIndex.matchesSource(id, query) }

    private fun MemoryAtom.toUi(sources: Map<PhotonId, Photon>): MemoryAtomUi {
        val resolved = sourcePhotonIds.count { it in sources }
        return MemoryAtomUi(
            atomId = atomId,
            kind = kind,
            content = content,
            stage = stage,
            confidence = confidence,
            observedAt = observedAt,
            sourcePhotonIds = sourcePhotonIds.toSortedSet(compareBy { it.value }),
            resolvedSourceCount = resolved,
            missingSourceCount = sourcePhotonIds.size - resolved,
        )
    }

    private fun MemoryCrystal.toUi(sources: Map<PhotonId, Photon>): MemoryCrystalUi {
        val resolved = sourcePhotonIds.count { it in sources }
        return MemoryCrystalUi(
            crystalId = crystalId,
            semanticCore = semanticCore,
            confidence = confidence,
            startedAt = startedAt,
            endedAt = endedAt,
            sourcePhotonIds = sourcePhotonIds.toSortedSet(compareBy { it.value }),
            resolvedSourceCount = resolved,
            missingSourceCount = sourcePhotonIds.size - resolved,
        )
    }

    private fun MemoryEpisode.toUi(sources: Map<PhotonId, Photon>): MemoryEpisodeUi {
        val resolved = sourcePhotonIds.count { it in sources }
        return MemoryEpisodeUi(
            episodeId = episodeId,
            stage = stage,
            startedAt = startedAt,
            endedAt = endedAt,
            semanticKeys = semanticKeys.toSortedSet(),
            sourcePhotonIds = sourcePhotonIds.toSortedSet(compareBy { it.value }),
            resolvedSourceCount = resolved,
            missingSourceCount = sourcePhotonIds.size - resolved,
        )
    }
}
