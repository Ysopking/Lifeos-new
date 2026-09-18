package app.lifeos.core.runtime.extension

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.field.world.WorldNodeKind
import app.lifeos.core.field.world.WorldSignalDimension

enum class ExtensionGapKind {
    WORLD_REPRESENTATION_GAP,
    WORLD_SIGNAL_GAP,
    COUPLING_GAP,
    CAUSAL_MODEL_GAP,
    EQUATION_COVERAGE_GAP,
}

data class WorldCouplingRequirement(
    val sourceDimension: WorldSignalDimension,
    val targetDimension: WorldSignalDimension,
) {
    init {
        require(sourceDimension != targetDimension) {
            "World coupling requirement must connect distinct dimensions"
        }
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "world-coupling-requirement/v1",
        sourceDimension.name,
        targetDimension.name,
    )
}

data class CausalModelRequirement(
    val sourceSemanticKey: String,
    val targetSemanticKey: String,
) {
    init {
        require(sourceSemanticKey.isNotBlank()) { "Causal source key must not be blank" }
        require(targetSemanticKey.isNotBlank()) { "Causal target key must not be blank" }
        require(sourceSemanticKey != targetSemanticKey) {
            "Causal model requirement must connect distinct semantic keys"
        }
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "causal-model-requirement/v1",
        sourceSemanticKey,
        targetSemanticKey,
    )
}

data class ExtensionGapDetectionInput(
    val sourceFingerprint: String,
    val representedNodeKinds: Set<WorldNodeKind>,
    val requiredNodeKinds: Set<WorldNodeKind> = emptySet(),
    val representedSignalDimensions: Set<WorldSignalDimension>,
    val requiredSignalDimensions: Set<WorldSignalDimension> = emptySet(),
    val availableCouplings: Set<WorldCouplingRequirement> = emptySet(),
    val requiredCouplings: Set<WorldCouplingRequirement> = emptySet(),
    val availableCausalRelations: Set<CausalModelRequirement> = emptySet(),
    val requiredCausalRelations: Set<CausalModelRequirement> = emptySet(),
    val equationCoveredCouplings: Set<WorldCouplingRequirement> = emptySet(),
) {
    init {
        require(sourceFingerprint.isNotBlank()) { "Gap detection source fingerprint must not be blank" }
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "extension-gap-detection-input/v1",
        sourceFingerprint,
        *representedNodeKinds.map { "node:represented:${it.name}" }.sorted().toTypedArray(),
        *requiredNodeKinds.map { "node:required:${it.name}" }.sorted().toTypedArray(),
        *representedSignalDimensions.map { "signal:represented:${it.name}" }.sorted().toTypedArray(),
        *requiredSignalDimensions.map { "signal:required:${it.name}" }.sorted().toTypedArray(),
        *availableCouplings.map { "coupling:available:${it.fingerprint()}" }.sorted().toTypedArray(),
        *requiredCouplings.map { "coupling:required:${it.fingerprint()}" }.sorted().toTypedArray(),
        *availableCausalRelations.map { "causal:available:${it.fingerprint()}" }.sorted().toTypedArray(),
        *requiredCausalRelations.map { "causal:required:${it.fingerprint()}" }.sorted().toTypedArray(),
        *equationCoveredCouplings.map { "equation:covered:${it.fingerprint()}" }.sorted().toTypedArray(),
    )
}

data class ExtensionGap(
    val id: String,
    val kind: ExtensionGapKind,
    val semanticKey: String,
    val reason: String,
    val sourceFingerprint: String,
    val requiredExtensionKinds: Set<ExtensionKind>,
) {
    init {
        require(id.isNotBlank())
        require(semanticKey.isNotBlank())
        require(reason.isNotBlank())
        require(sourceFingerprint.isNotBlank())
        require(requiredExtensionKinds.isNotEmpty())
        require(id == expectedId()) { "Extension gap id does not match content" }
    }

    private fun expectedId(): String = "extension-gap:${StableFieldIds.fingerprint(
        "extension-gap/v1",
        kind.name,
        semanticKey,
        reason,
        sourceFingerprint,
        *requiredExtensionKinds.map { it.name }.sorted().toTypedArray(),
    )}"

    companion object {
        fun create(
            kind: ExtensionGapKind,
            semanticKey: String,
            reason: String,
            sourceFingerprint: String,
            requiredExtensionKinds: Set<ExtensionKind>,
        ): ExtensionGap {
            val id = "extension-gap:${StableFieldIds.fingerprint(
                "extension-gap/v1",
                kind.name,
                semanticKey,
                reason,
                sourceFingerprint,
                *requiredExtensionKinds.map { it.name }.sorted().toTypedArray(),
            )}"
            return ExtensionGap(
                id = id,
                kind = kind,
                semanticKey = semanticKey,
                reason = reason,
                sourceFingerprint = sourceFingerprint,
                requiredExtensionKinds = requiredExtensionKinds,
            )
        }
    }
}

enum class ExtensionGapWorkType {
    ASSESS_EXTENSION_GAP,
}

data class ExtensionGapWorkItem(
    val id: String,
    val type: ExtensionGapWorkType,
    val gapId: String,
    val sourceFingerprint: String,
    val requiredExtensionKinds: Set<ExtensionKind>,
) {
    init {
        require(id.isNotBlank())
        require(gapId.isNotBlank())
        require(sourceFingerprint.isNotBlank())
        require(requiredExtensionKinds.isNotEmpty())
    }

    companion object {
        fun from(gap: ExtensionGap): ExtensionGapWorkItem = ExtensionGapWorkItem(
            id = "extension-gap-work:${StableFieldIds.fingerprint(
                "extension-gap-work/v1",
                gap.id,
                gap.sourceFingerprint,
                *gap.requiredExtensionKinds.map { it.name }.sorted().toTypedArray(),
            )}",
            type = ExtensionGapWorkType.ASSESS_EXTENSION_GAP,
            gapId = gap.id,
            sourceFingerprint = gap.sourceFingerprint,
            requiredExtensionKinds = gap.requiredExtensionKinds,
        )
    }
}

data class ExtensionGapDetectionResult(
    val inputFingerprint: String,
    val gaps: List<ExtensionGap>,
    val bootWork: List<ExtensionGapWorkItem>,
) {
    init {
        require(inputFingerprint.isNotBlank())
        require(gaps.map { it.id }.distinct().size == gaps.size)
        require(bootWork.map { it.id }.distinct().size == bootWork.size)
        require(bootWork.map { it.gapId }.toSet() == gaps.map { it.id }.toSet())
    }
}

/**
 * B152 is a pure detector. It observes explicit missing representation/coupling coverage and emits
 * durable-work descriptions for the BootEngine. It does not start DeepSearch, Workshop, Evolution,
 * HotSwap or registry mutation directly.
 */
class ExtensionGapDetector {
    fun detect(input: ExtensionGapDetectionInput): ExtensionGapDetectionResult {
        val gaps = buildList {
            (input.requiredNodeKinds - input.representedNodeKinds)
                .sortedBy { it.name }
                .forEach { kind ->
                    add(
                        ExtensionGap.create(
                            kind = ExtensionGapKind.WORLD_REPRESENTATION_GAP,
                            semanticKey = "world-node-kind:${kind.name}",
                            reason = "required-world-node-kind-not-represented",
                            sourceFingerprint = input.sourceFingerprint,
                            requiredExtensionKinds = setOf(
                                ExtensionKind.WORLD_PROJECTION_PACK,
                                ExtensionKind.WORLD_SIGNAL_PACK,
                            ),
                        )
                    )
                }

            (input.requiredSignalDimensions - input.representedSignalDimensions)
                .sortedBy { it.name }
                .forEach { dimension ->
                    add(
                        ExtensionGap.create(
                            kind = ExtensionGapKind.WORLD_SIGNAL_GAP,
                            semanticKey = "world-signal-dimension:${dimension.name}",
                            reason = "required-world-signal-dimension-not-represented",
                            sourceFingerprint = input.sourceFingerprint,
                            requiredExtensionKinds = setOf(ExtensionKind.WORLD_SIGNAL_PACK),
                        )
                    )
                }

            (input.requiredCouplings - input.availableCouplings)
                .sortedBy { it.fingerprint() }
                .forEach { coupling ->
                    add(
                        ExtensionGap.create(
                            kind = ExtensionGapKind.COUPLING_GAP,
                            semanticKey = "world-coupling:${coupling.fingerprint()}",
                            reason = "required-world-coupling-not-available",
                            sourceFingerprint = input.sourceFingerprint,
                            requiredExtensionKinds = setOf(ExtensionKind.WORLD_TOPOLOGY_PACK),
                        )
                    )
                }

            (input.requiredCausalRelations - input.availableCausalRelations)
                .sortedBy { it.fingerprint() }
                .forEach { relation ->
                    add(
                        ExtensionGap.create(
                            kind = ExtensionGapKind.CAUSAL_MODEL_GAP,
                            semanticKey = "causal-model:${relation.fingerprint()}",
                            reason = "required-causal-relation-not-supported",
                            sourceFingerprint = input.sourceFingerprint,
                            requiredExtensionKinds = setOf(
                                ExtensionKind.WORLD_TOPOLOGY_PACK,
                                ExtensionKind.WORLD_PROJECTION_PACK,
                            ),
                        )
                    )
                }

            (input.requiredCouplings - input.equationCoveredCouplings)
                .sortedBy { it.fingerprint() }
                .forEach { coupling ->
                    add(
                        ExtensionGap.create(
                            kind = ExtensionGapKind.EQUATION_COVERAGE_GAP,
                            semanticKey = "equation-coupling:${coupling.fingerprint()}",
                            reason = "required-world-coupling-not-covered-by-active-equation-contract",
                            sourceFingerprint = input.sourceFingerprint,
                            requiredExtensionKinds = setOf(ExtensionKind.WORLD_EQUATION_PACK),
                        )
                    )
                }
        }
            .distinctBy { it.id }
            .sortedWith(compareBy({ it.kind.name }, { it.semanticKey }, { it.id }))

        return ExtensionGapDetectionResult(
            inputFingerprint = input.fingerprint(),
            gaps = gaps,
            bootWork = gaps.map(ExtensionGapWorkItem::from),
        )
    }
}
