package app.lifeos.core.field

/**
 * Canonical fingerprints for every value that can change field physics or the observable result.
 * These deliberately include more than stable entity ids: changing confidence, reliability,
 * authority, semantic mass, relation weights, conflict strength or convergence configuration must
 * produce a different run identity.
 */
internal fun FieldConvergenceRequest.physicsFingerprint(
    config: ConvergenceConfig,
    forceCalculatorFingerprint: String,
    resolvedFields: List<DomainField>,
): String {
    val parts = buildList {
        add("field-input/v2")
        add(domainId.value)
        add(context.fingerprint())
        add(config.fingerprint())
        add(forceCalculatorFingerprint)
        add(DomainFieldRegistry.fingerprintOf(resolvedFields))

        graph.stableNodes().forEach { node ->
            add("node")
            add(node.id.value)
            add(node.domainId.value)
            add(node.kind.name)
            add(node.semanticKey)
            add(fieldDouble(node.semanticMass))
            add(fieldDouble(node.baseEnergy))
            node.evidenceIds.sortedBy { it.value }.forEach { add(it.value) }
            node.attributes.toSortedMap().forEach { (key, value) ->
                add("node-attribute")
                add(key)
                add(value)
            }
        }
        graph.stableRelations().forEach { relation ->
            add("relation")
            add(relation.id.value)
            add(relation.domainId.value)
            add(relation.source.value)
            add(relation.target.value)
            add(relation.type.name)
            add(fieldDouble(relation.weight))
            add(relation.explanation)
        }
        graph.competitionGroups.sortedBy { it.key }.forEach { group ->
            add("competition")
            add(group.key)
            add(group.allowUnresolved.toString())
            group.nodeIds.sortedBy { it.value }.forEach { add(it.value) }
        }
        graph.conflicts.sortedBy { it.key }.forEach { conflict ->
            add("graph-conflict")
            add(conflict.key)
            add(fieldDouble(conflict.severity))
            add(conflict.explanation)
            conflict.nodeIds.sortedBy { it.value }.forEach { add(it.value) }
            conflict.evidenceIds.sortedBy { it.value }.forEach { add(it.value) }
        }

        evidence.stableEvidenceOrder().forEach { value ->
            add("evidence")
            add(value.id.value)
            add(value.sourceFingerprint)
            add(fieldDouble(value.confidence))
            add(fieldDouble(value.reliability.score))
            add(value.reliability.reason)
            add(value.authority.name)
            add(value.explanation)
        }
        hypotheses.sortedBy { it.id.value }.forEach { hypothesis ->
            add("hypothesis")
            add(hypothesis.id.value)
            add(hypothesis.domainId.value)
            add(hypothesis.semanticKey)
            add(hypothesis.scope.name)
            add(hypothesis.state.name)
            add(hypothesis.explanation)
            hypothesis.nodeIds.sortedBy { it.value }.forEach { add(it.value) }
            hypothesis.evidenceLinks
                .sortedWith(compareBy<HypothesisEvidenceLink> { it.evidenceId.value }.thenBy { it.relation.name })
                .forEach { link ->
                    add("hypothesis-evidence")
                    add(link.evidenceId.value)
                    add(link.relation.name)
                    add(fieldDouble(link.weight))
                }
            hypothesis.conflicts.sortedBy { it.competingHypothesisId.value }.forEach { conflict ->
                add("hypothesis-conflict")
                add(conflict.competingHypothesisId.value)
                add(fieldDouble(conflict.strength))
                add(conflict.reason)
            }
        }
    }
    return StableFieldIds.fingerprint(*parts.toTypedArray())
}

internal fun ConvergenceConfig.fingerprint(): String = StableFieldIds.fingerprint(
    "convergence-config/v1",
    maxIterations.toString(),
    requiredStableRounds.toString(),
    fieldDouble(epsilon),
    fieldDouble(minConvergence),
    fieldDouble(minWinnerMargin),
    fieldDouble(damping),
)

internal fun fieldDouble(value: Double): String = java.lang.Double.toHexString(value)
