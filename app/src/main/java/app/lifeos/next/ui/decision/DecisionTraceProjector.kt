package app.lifeos.next.ui.decision

import app.lifeos.core.runtime.trace.DecisionTrace
import app.lifeos.core.runtime.trace.DecisionTraceNode
import app.lifeos.core.runtime.trace.DecisionTraceNodeType
import app.lifeos.core.runtime.trace.DecisionTraceProjector as CoreDecisionTraceProjector

/** Pure read-only UI projection over the authoritative durable DecisionTrace graph. */
object DecisionTraceProjector {
    private val coreProjector = CoreDecisionTraceProjector()

    fun project(traces: Iterable<DecisionTrace>): DecisionTraceWorkspaceUiModel =
        DecisionTraceWorkspaceUiModel(
            traces = traces
                .map(::projectTrace)
                .sortedWith(
                    compareByDescending<DecisionTraceUiModel> {
                        it.lastRecordedAt?.toEpochMilli() ?: Long.MIN_VALUE
                    }.thenBy { it.traceId.value }
                ),
        )

    private fun projectTrace(trace: DecisionTrace): DecisionTraceUiModel {
        val core = coreProjector.project(trace)
        val nodes = trace.nodes.map(::projectNode)
        val firstRecordedAt = trace.nodes.minOfOrNull { it.recordedAt }
        val lastRecordedAt = trace.nodes.maxOfOrNull { it.recordedAt }
        val kind = kind(trace)
        return DecisionTraceUiModel(
            traceId = trace.id,
            revision = trace.revision,
            kind = kind,
            title = trace.nodes.mapNotNull { it.displayLabel }.firstOrNull() ?: title(kind),
            summary = summary(core.unresolved, nodes),
            firstRecordedAt = firstRecordedAt,
            lastRecordedAt = lastRecordedAt,
            unresolved = core.unresolved,
            facts = nodes.filter { it.section == DecisionTraceSection.FACT },
            constraints = nodes.filter { it.section == DecisionTraceSection.CONSTRAINT },
            alternatives = nodes.filter { it.section == DecisionTraceSection.ALTERNATIVE },
            outcomes = nodes.filter { it.section == DecisionTraceSection.OUTCOME },
            uncertainties = nodes.filter { it.section == DecisionTraceSection.UNCERTAINTY },
            links = trace.links
                .sortedWith(compareBy({ it.from.value }, { it.to.value }, { it.type.name }))
                .map { link ->
                    DecisionTraceLinkUiModel(
                        from = link.from,
                        to = link.to,
                        type = link.type,
                    )
                },
        )
    }

    private fun projectNode(node: DecisionTraceNode): DecisionTraceNodeUiModel =
        DecisionTraceNodeUiModel(
            id = node.id,
            type = node.type,
            sourceType = node.sourceType,
            sourceId = node.sourceId,
            sourceRevision = node.sourceRevision,
            label = node.displayLabel ?: sourceLabel(node.sourceType),
            reasons = node.reasonCodes.map { raw ->
                DecisionTraceReasonUiModel(raw = raw, summary = reasonSummary(raw))
            },
            recordedAt = node.recordedAt,
            section = section(node.type),
            tone = tone(node),
        )

    private fun kind(trace: DecisionTrace): DecisionTraceKind {
        val sourceTypes = trace.nodes.mapTo(hashSetOf()) { it.sourceType }
        return when {
            "goal-photon" in sourceTypes -> DecisionTraceKind.GOAL
            sourceTypes.any { it.startsWith("self-healing-") || it == "health-node" } ->
                DecisionTraceKind.SELF_HEALING
            sourceTypes.any { it.startsWith("evolution-") || it == "generated-tool" } ->
                DecisionTraceKind.EVOLUTION
            sourceTypes.any { it.startsWith("artifact-") } -> DecisionTraceKind.ARTIFACT
            else -> DecisionTraceKind.SYSTEM
        }
    }

    private fun title(kind: DecisionTraceKind): String = when (kind) {
        DecisionTraceKind.GOAL -> "Zielentscheidung"
        DecisionTraceKind.SELF_HEALING -> "Self-Healing-Entscheidung"
        DecisionTraceKind.EVOLUTION -> "Evolution-Entscheidung"
        DecisionTraceKind.ARTIFACT -> "Artefakt-Entscheidung"
        DecisionTraceKind.SYSTEM -> "Systementscheidung"
    }

    private fun summary(
        unresolved: Boolean,
        nodes: List<DecisionTraceNodeUiModel>,
    ): String {
        val constraints = nodes.count { it.section == DecisionTraceSection.CONSTRAINT }
        val alternatives = nodes.count { it.section == DecisionTraceSection.ALTERNATIVE }
        val outcomes = nodes.count { it.section == DecisionTraceSection.OUTCOME }
        return when {
            unresolved -> "Mindestens eine Unsicherheit ist in dieser Entscheidung noch ausdrücklich offen."
            outcomes > 0 -> "Die Entscheidungskette enthält dokumentierte Ausführungs- oder Recovery-Ergebnisse."
            alternatives > 0 && constraints > 0 ->
                "Alternativen wurden unter dokumentierten Policy- oder Ressourcenbedingungen bewertet."
            alternatives > 0 -> "Die Entscheidungskette enthält dokumentierte Alternativen oder Selektionen."
            constraints > 0 -> "Die Entscheidung wurde durch dokumentierte Bedingungen eingeschränkt."
            else -> "Die Entscheidungskette enthält ${nodes.size} dokumentierte Evidenzknoten."
        }
    }

    private fun section(type: DecisionTraceNodeType): DecisionTraceSection = when (type) {
        DecisionTraceNodeType.OBSERVED_FACT -> DecisionTraceSection.FACT
        DecisionTraceNodeType.POLICY_CONSTRAINT,
        DecisionTraceNodeType.RESOURCE_CONSTRAINT -> DecisionTraceSection.CONSTRAINT
        DecisionTraceNodeType.INFERRED_HYPOTHESIS,
        DecisionTraceNodeType.CANDIDATE_ALTERNATIVE,
        DecisionTraceNodeType.REJECTION,
        DecisionTraceNodeType.SELECTION -> DecisionTraceSection.ALTERNATIVE
        DecisionTraceNodeType.EXECUTION_OUTCOME,
        DecisionTraceNodeType.RECOVERY_OUTCOME -> DecisionTraceSection.OUTCOME
        DecisionTraceNodeType.UNRESOLVED_UNCERTAINTY -> DecisionTraceSection.UNCERTAINTY
    }

    private fun tone(node: DecisionTraceNode): DecisionTraceTone = when {
        node.type == DecisionTraceNodeType.REJECTION -> DecisionTraceTone.NEGATIVE
        node.type == DecisionTraceNodeType.UNRESOLVED_UNCERTAINTY -> DecisionTraceTone.WARNING
        node.type == DecisionTraceNodeType.POLICY_CONSTRAINT ||
            node.type == DecisionTraceNodeType.RESOURCE_CONSTRAINT -> DecisionTraceTone.WARNING
        node.type == DecisionTraceNodeType.SELECTION -> DecisionTraceTone.POSITIVE
        node.type == DecisionTraceNodeType.EXECUTION_OUTCOME ||
            node.type == DecisionTraceNodeType.RECOVERY_OUTCOME -> {
            if (node.reasonCodes.any(::isFailureReason)) {
                DecisionTraceTone.NEGATIVE
            } else {
                DecisionTraceTone.POSITIVE
            }
        }
        else -> DecisionTraceTone.NEUTRAL
    }

    private fun isFailureReason(reason: String): Boolean =
        reason == "FAILED" ||
            reason == "EXPECTED_OUTPUT_MISSING" ||
            reason == "BLOCKED" ||
            reason == "EXHAUSTED" ||
            reason == "QUARANTINED" ||
            reason.startsWith("HARD_FAILURE_")

    private fun sourceLabel(sourceType: String): String = when (sourceType) {
        "goal-photon" -> "Ziel"
        "goal-plan" -> "Plan"
        "convergence-decision" -> "Konvergenzentscheidung"
        "convergence-candidate" -> "Konvergenzalternative"
        "owner-policy-decision" -> "Owner-Policy"
        "world-formula-resource-allocation" -> "Ressourcenzuteilung"
        "resource-decision" -> "Ressourcenentscheidung"
        "resource-reservation" -> "Ressourcenreservierung"
        "capability-provider-selection" -> "Fähigkeitsanbieter"
        "capability-gap" -> "Fähigkeitslücke"
        "language-goal-routing" -> "Sprachrouting"
        "deepsearch-request" -> "Deep Search"
        "deepsearch-mission" -> "Deep-Search-Mission"
        "deepsearch-branch" -> "Deep-Search-Alternative"
        "deepsearch-evidence" -> "Deep-Search-Evidenz"
        "deepsearch-source" -> "Deep-Search-Quelle"
        "self-healing-incident" -> "Self-Healing-Incident"
        "self-healing-action" -> "Self-Healing-Aktion"
        "self-healing-verification-evidence" -> "Recovery-Evidenz"
        "health-node" -> "Health Node"
        "evolution-adoption-evidence" -> "Evolution-Adoption"
        "evolution-canary-invocation" -> "Evolution-Canary"
        "evolution-canary-reservation" -> "Canary-Ressource"
        "evolution-canary-outcome" -> "Canary-Ergebnis"
        "evolution-kill-switch" -> "Kill Switch"
        "generated-tool" -> "Generiertes Tool"
        "artifact-request" -> "Artefakt-Anfrage"
        "artifact-contribution" -> "Artefakt-Beitrag"
        "artifact-parent-photon" -> "Quell-Photon"
        "artifact-photon" -> "Artefakt"
        else -> sourceType
    }

    private fun reasonSummary(raw: String): String = when {
        raw == "ALLOWED" -> "Die Owner-Policy erlaubt diesen Schritt."
        raw == "SUCCEEDED" || raw == "SUCCESS" -> "Der dokumentierte Schritt war erfolgreich."
        raw == "FAILED" -> "Der dokumentierte Schritt ist fehlgeschlagen."
        raw == "EXPECTED_OUTPUT" -> "Die erwartete Ausgabe wurde erzeugt."
        raw == "EXPECTED_OUTPUT_MISSING" -> "Die erwartete Ausgabe fehlt."
        raw == "LANGUAGE_BLOCKING" -> "Die Sprachauflösung blockiert den nächsten Schritt."
        raw == "SOURCE_BLOCKED" -> "Die Quelle wurde blockiert."
        raw == "SOURCE_FAILED" -> "Die Quelle ist fehlgeschlagen."
        raw == "CONTRADICTION" -> "Diese Evidenz widerspricht der ausgewählten Richtung."
        raw == "SUPPORTING" -> "Diese Evidenz stützt die ausgewählte Richtung."
        raw.startsWith("STATUS_") -> "Status: ${raw.removePrefix("STATUS_")}"
        raw.startsWith("CAPABILITY_") -> "Fähigkeit: ${raw.removePrefix("CAPABILITY_")}"
        raw.startsWith("PROVIDER_STATE_") -> "Provider-Status: ${raw.removePrefix("PROVIDER_STATE_")}"
        raw.startsWith("PROVIDER_TYPE_") -> "Provider-Typ: ${raw.removePrefix("PROVIDER_TYPE_")}"
        raw.startsWith("TRUST_") -> "Vertrauensstufe: ${raw.removePrefix("TRUST_")}"
        raw.startsWith("DOMAIN_") -> "Ressourcendomäne: ${raw.removePrefix("DOMAIN_")}"
        raw.startsWith("ATTEMPT_") -> "Recovery-Versuch ${raw.removePrefix("ATTEMPT_")}"
        raw.startsWith("HARD_FAILURE_") -> "Harter Fehler: ${raw.removePrefix("HARD_FAILURE_")}"
        else -> raw
    }
}
