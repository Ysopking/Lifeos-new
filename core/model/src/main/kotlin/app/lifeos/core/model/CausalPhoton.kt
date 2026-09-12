package app.lifeos.core.model

/**
 * Canonical replay representation of a Photon.
 * Wall-clock creation time and the Photon id itself are intentionally excluded from the
 * semantic state hash so a module can deterministically derive the same output identity
 * from the same causal branch and the same semantic output.
 */
object CanonicalPhotonState {
    fun semanticHash(photon: Photon): CognitiveStateHash = StableCognitiveIds.stateHash(
        buildList {
            add(photon.revision.toString())
            add(photon.content)
            add(photon.mimeType)
            add(photon.phase.name)
            add(photon.semanticMass.toString())
            add(photon.energy.toString())
            add(photon.confidence.toString())
            add(photon.provenance.source)
            add(photon.provenance.actor)
            photon.provenance.parentIds.map { it.value }.sorted().forEach(::add)
            photon.relations
                .sortedWith(
                    compareBy<PhotonRelation> { it.target.value }
                        .thenBy { it.type.name }
                        .thenBy { it.weight },
                )
                .forEach { relation ->
                    add(relation.target.value)
                    add(relation.type.name)
                    add(relation.weight.toString())
                }
            photon.tags.sorted().forEach(::add)
        },
    )

    /** Exact input-state hash keeps the persisted Photon identity and revision in scope. */
    fun inputHash(photon: Photon): CognitiveStateHash = StableCognitiveIds.stateHash(
        photon.id.value,
        photon.revision.toString(),
        semanticHash(photon).value,
    )

    fun derivedPhotonId(
        branchId: PhotonBranchId,
        outputOrdinal: Int,
        candidate: Photon,
    ): PhotonId {
        require(outputOrdinal >= 0) { "Output ordinal must not be negative" }
        return PhotonId(
            "derived-" + StableCognitiveIds.fingerprint(
                branchId.value,
                outputOrdinal.toString(),
                semanticHash(candidate).value,
            ),
        )
    }

    /**
     * Makes a module output causally self-describing without mutating the source Photon.
     * Existing candidate timestamps are retained for human chronology but do not affect replay ids.
     * Module ancestry is propagated so a later recursive pass cannot cycle back through a module
     * that already contributed to this causal branch.
     */
    fun normalizeDerived(
        source: Photon,
        traceId: CausalTraceId,
        branchId: PhotonBranchId,
        module: ModuleIdentity,
        outputOrdinal: Int,
        candidate: Photon,
    ): Photon {
        val deterministicId = derivedPhotonId(branchId, outputOrdinal, candidate)
        val lineage = candidate.provenance.parentIds + source.id
        val relations = candidate.relations + PhotonRelation(
            target = source.id,
            type = RelationType.DERIVED_FROM,
        )
        val inheritedModuleTags = source.tags.filterTo(linkedSetOf()) { it.startsWith("module:") }
        return candidate.copy(
            id = deterministicId,
            provenance = candidate.provenance.copy(parentIds = lineage),
            relations = relations,
            tags = candidate.tags + inheritedModuleTags + setOf(
                "causal-trace:${traceId.value}",
                "causal-branch:${branchId.value}",
                "module:${module.moduleId}",
                "module-version:${module.version}",
            ),
        )
    }

    fun outputsHash(outputs: Collection<Photon>): CognitiveStateHash = StableCognitiveIds.stateHash(
        outputs
            .sortedBy { it.id.value }
            .flatMap { output -> listOf(output.id.value, semanticHash(output).value) },
    )
}
