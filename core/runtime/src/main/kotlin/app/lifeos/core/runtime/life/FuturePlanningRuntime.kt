package app.lifeos.core.runtime.life

import app.lifeos.core.model.CausalTraceId
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonPhase
import app.lifeos.core.model.PhotonRelation
import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.RelationType
import app.lifeos.core.model.StableCognitiveIds
import app.lifeos.core.runtime.CausalDerivedPhotonPersistence

/** Lossless reader for persisted future-evidence Photons, including pre-K v1 payloads. */
object FutureEvidencePhotonCodec {
    const val MIME_TYPE = "application/vnd.lifeos.future-evidence+text"
    private const val LEGACY_PROJECTION_VERSION = "future-evidence/v1"

    fun decode(photon: Photon): FutureEvidenceScenario? {
        if (photon.mimeType != MIME_TYPE || "future-evidence" !in photon.tags) return null
        val fields = linkedMapOf<String, String>()
        photon.content.lineSequence().filter { it.isNotBlank() }.forEach { line ->
            val separator = line.indexOf('=')
            require(separator > 0) { "Malformed future-evidence line" }
            val key = line.substring(0, separator)
            val value = line.substring(separator + 1)
            require(fields.put(key, value) == null) { "Duplicate future-evidence field: $key" }
        }
        fun required(key: String): String = fields[key]?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Missing future-evidence field: $key")

        val projectionVersion = fields["projectionVersion"]?.takeIf { it.isNotBlank() }
            ?: photon.tags.firstOrNull { it.startsWith("future-projection:") }
                ?.substringAfter("future-projection:")
            ?: LEGACY_PROJECTION_VERSION
        val allowed = when (val raw = required("allowed")) {
            "true" -> true
            "false" -> false
            else -> throw IllegalArgumentException("Invalid future-evidence allowed flag: $raw")
        }
        val delta = required("delta").takeIf { it.isNotBlank() }?.split(',')
            ?.associate { part ->
                val separator = part.indexOf(':')
                require(separator > 0) { "Malformed future-evidence state delta" }
                val dimension = SeinDimension.valueOf(part.substring(0, separator))
                dimension to part.substring(separator + 1).toDouble()
            }
            ?: emptyMap()
        val scenario = FutureEvidenceScenario(
            id = required("scenario"),
            type = FutureScenarioType.valueOf(required("type")),
            horizon = FutureHorizon.valueOf(required("horizon")),
            probability = required("probability").toDouble(),
            stateDelta = delta,
            resourceCost = required("resourceCost").toDouble(),
            allowed = allowed,
            sourcePhotonIds = photon.provenance.parentIds,
            explanation = required("explanation"),
            projectionVersion = projectionVersion,
        )
        require(
            (scenario.allowed && "future-actionable" in photon.tags) ||
                (!scenario.allowed && "future-observation" in photon.tags)
        ) { "Future-evidence actionability tag mismatch" }
        return scenario
    }
}

data class FuturePlanningAdmissibility(
    val allowed: Boolean,
    val policyRevision: Long,
    val policyFingerprint: String,
    val resourceFingerprint: String,
    val reason: String,
) {
    init {
        require(policyRevision >= 0L)
        require(policyFingerprint.isNotBlank())
        require(resourceFingerprint.isNotBlank())
        require(reason.isNotBlank())
    }
}

/**
 * Policy/resource authority for planning only. An allowed result authorizes creation of a
 * non-executing goal/opportunity candidate; it never authorizes the eventual host-facing effect.
 */
fun interface FuturePlanningAuthority {
    suspend fun assess(scenario: FutureEvidenceScenario): FuturePlanningAdmissibility
}

fun interface FuturePlanningStateProvider {
    suspend fun currentState(scenarios: List<FutureEvidenceScenario>): LifeStateVector
}

object NeutralFuturePlanningStateProvider : FuturePlanningStateProvider {
    override suspend fun currentState(scenarios: List<FutureEvidenceScenario>): LifeStateVector =
        LifeStateVector.neutral()
}

data class FuturePlanningDecision(
    val decisionFingerprint: String,
    val candidateSetFingerprint: String,
    val policyRevision: Long,
    val policyFingerprint: String,
    val resourceSnapshotFingerprint: String,
    val seinDefinitionVersion: String,
    val seinDefinitionFingerprint: String,
    val lifeStateFingerprint: String,
    val selectedScenarioId: String?,
    val reasons: Map<String, String>,
) {
    init {
        require(decisionFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(candidateSetFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(policyRevision >= 0L)
        require(policyFingerprint.isNotBlank())
        require(resourceSnapshotFingerprint.isNotBlank())
        require(seinDefinitionVersion.isNotBlank())
        require(seinDefinitionFingerprint.isNotBlank())
        require(lifeStateFingerprint.isNotBlank())
        require(reasons.isNotEmpty())
        require(reasons.values.none { it.isBlank() })
    }
}

/**
 * Productive future-planning fan-in. Future evidence is grouped by immutable source lineage,
 * filtered through live policy/resource authority, ranked by LifePlanner, and converted only into
 * replayable planning evidence plus a non-executing goal/opportunity candidate.
 */
class FuturePlanningCoordinator(
    private val photons: PhotonRepository,
    private val authority: FuturePlanningAuthority,
    private val planner: LifePlanner = LifePlanner(),
    private val evaluator: SeinModeEvaluator = SeinModeEvaluator(),
    private val stateProvider: FuturePlanningStateProvider = NeutralFuturePlanningStateProvider,
) {
    private data class Record(val photon: Photon, val scenario: FutureEvidenceScenario)

    suspend fun planPersisted(trigger: Photon): List<Photon> {
        val current = FutureEvidencePhotonCodec.decode(trigger) ?: return emptyList()
        val key = lineageKey(current)
        val records = photons.loadAll().mapNotNull { persisted ->
            val scenario = FutureEvidencePhotonCodec.decode(persisted) ?: return@mapNotNull null
            if (lineageKey(scenario) == key) Record(persisted, scenario) else null
        }
        return planGroup(records.ifEmpty { listOf(Record(trigger, current)) })
    }

    suspend fun reconsiderAll(): List<Photon> {
        val groups = photons.loadAll().mapNotNull { persisted ->
            FutureEvidencePhotonCodec.decode(persisted)?.let { Record(persisted, it) }
        }.groupBy { lineageKey(it.scenario) }
        return groups.toSortedMap().values.flatMap { planGroup(it) }
    }

    private suspend fun planGroup(rawRecords: List<Record>): List<Photon> {
        require(rawRecords.isNotEmpty())
        val records = rawRecords.groupBy { it.scenario.id }.map { (id, duplicates) ->
            require(duplicates.map { it.scenario }.distinct().size == 1) {
                "Conflicting future scenario identity: $id"
            }
            duplicates.minBy { it.photon.id.value }
        }.sortedBy { it.scenario.id }
        val scenarios = records.map { it.scenario }
        val assessments = scenarios.associate { scenario ->
            scenario.id to authority.assess(scenario)
        }
        val policyRevisions = assessments.values.map { it.policyRevision }.distinct()
        val policyFingerprints = assessments.values.map { it.policyFingerprint }.distinct()
        require(policyRevisions.size == 1 && policyFingerprints.size == 1) {
            "Owner policy changed during one future-planning decision"
        }

        val currentState = stateProvider.currentState(scenarios)
        val candidates = scenarios.map { scenario ->
            val assessment = assessments.getValue(scenario.id)
            scenario.toPlannerCandidate().copy(allowed = scenario.allowed && assessment.allowed)
        }
        val ranked = planner.rank(currentState, candidates)
        val selected = ranked.firstOrNull { it.expectedImprovement > 0.0 }
        val reasons = scenarios.associate { scenario ->
            val assessment = assessments.getValue(scenario.id)
            val rankedCandidate = ranked.firstOrNull { it.candidate.id == scenario.id }
            scenario.id to when {
                !scenario.allowed -> "observation-only"
                !assessment.allowed -> "blocked:${assessment.reason}"
                selected?.candidate?.id == scenario.id -> "selected"
                rankedCandidate == null -> "not-admissible"
                rankedCandidate.expectedImprovement <= 0.0 -> "no-positive-sein-improvement"
                else -> "lower-ranked"
            }
        }.toSortedMap()

        val resourceSnapshotFingerprint = StableCognitiveIds.fingerprint(
            "future-planning-resource-set/v1",
            *assessments.entries.sortedBy { it.key }.flatMap { (scenarioId, assessment) ->
                listOf(scenarioId, assessment.resourceFingerprint, assessment.allowed.toString(), assessment.reason)
            }.toTypedArray(),
        )
        val candidateSetFingerprint = StableCognitiveIds.fingerprint(
            "future-planning-candidate-set/v1",
            currentState.fingerprint,
            evaluator.definitionFingerprint,
            *scenarios.sortedBy { it.id }.flatMap { scenario ->
                val assessment = assessments.getValue(scenario.id)
                listOf(
                    scenario.id,
                    scenario.projectionVersion,
                    scenario.type.name,
                    scenario.horizon.name,
                    java.lang.Double.toHexString(scenario.probability),
                    java.lang.Double.toHexString(scenario.resourceCost),
                    scenario.allowed.toString(),
                    assessment.allowed.toString(),
                    assessment.resourceFingerprint,
                )
            }.toTypedArray(),
        )
        val policyRevision = policyRevisions.single()
        val policyFingerprint = policyFingerprints.single()
        val decisionFingerprint = StableCognitiveIds.fingerprint(
            "future-planning-decision/v1",
            candidateSetFingerprint,
            "policy-revision:$policyRevision",
            policyFingerprint,
            resourceSnapshotFingerprint,
            evaluator.definitionVersion,
            evaluator.definitionFingerprint,
            currentState.fingerprint,
            selected?.candidate?.id.orEmpty(),
            *reasons.entries.flatMap { listOf(it.key, it.value) }.toTypedArray(),
        )
        val decision = FuturePlanningDecision(
            decisionFingerprint = decisionFingerprint,
            candidateSetFingerprint = candidateSetFingerprint,
            policyRevision = policyRevision,
            policyFingerprint = policyFingerprint,
            resourceSnapshotFingerprint = resourceSnapshotFingerprint,
            seinDefinitionVersion = evaluator.definitionVersion,
            seinDefinitionFingerprint = evaluator.definitionFingerprint,
            lifeStateFingerprint = currentState.fingerprint,
            selectedScenarioId = selected?.candidate?.id,
            reasons = reasons,
        )
        val decisionPhoton = decisionPhoton(decision, records, selected)
        val outputs = buildList {
            add(decisionPhoton)
            if (selected != null) {
                val selectedRecord = records.single { it.scenario.id == selected.candidate.id }
                add(candidatePhoton(decision, decisionPhoton, selectedRecord, selected))
            }
        }
        return outputs.filter { output ->
            val existing = photons.load(output.id)
            if (existing == null) {
                true
            } else {
                check(existing == output) { "Conflicting future-planning Photon identity: ${output.id.value}" }
                false
            }
        }
    }

    private fun decisionPhoton(
        decision: FuturePlanningDecision,
        records: List<Record>,
        selected: RankedFutureDelta?,
    ): Photon {
        val parentIds = records.mapTo(linkedSetOf()) { it.photon.id }
        val createdAt = records.maxOf { it.photon.provenance.createdAt }
        return Photon(
            id = PhotonId("future-plan-decision-${decision.decisionFingerprint}"),
            revision = 1,
            content = buildString {
                appendLine("candidate_set=${decision.candidateSetFingerprint}")
                appendLine("policy_revision=${decision.policyRevision}")
                appendLine("policy_fingerprint=${decision.policyFingerprint}")
                appendLine("resource_snapshot=${decision.resourceSnapshotFingerprint}")
                appendLine("sein_version=${decision.seinDefinitionVersion}")
                appendLine("sein_fingerprint=${decision.seinDefinitionFingerprint}")
                appendLine("life_state=${decision.lifeStateFingerprint}")
                appendLine("selected=${decision.selectedScenarioId.orEmpty()}")
                appendLine("expected_improvement=${selected?.expectedImprovement ?: 0.0}")
                append("reasons=${decision.reasons.entries.joinToString("|") { "${it.key}:${it.value}" }}")
            },
            mimeType = "application/vnd.lifeos.future-planning-decision+text",
            phase = if (selected == null) PhotonPhase.REFLECTING else PhotonPhase.CONVERGED,
            semanticMass = records.maxOf { it.photon.semanticMass },
            energy = records.maxOf { it.photon.energy },
            confidence = records.minOf { it.photon.confidence },
            provenance = Provenance(
                source = "future-planning",
                actor = "lifeos",
                createdAt = createdAt,
                parentIds = parentIds,
            ),
            relations = parentIds.mapTo(linkedSetOf()) { PhotonRelation(it, RelationType.REFERENCES) },
            tags = setOf(
                "future-planning-decision",
                "non-executing",
                if (selected == null) "future-plan:no-action" else "future-plan:selected",
            ),
        )
    }

    private fun candidatePhoton(
        decision: FuturePlanningDecision,
        decisionPhoton: Photon,
        record: Record,
        ranked: RankedFutureDelta,
    ): Photon {
        val scenario = record.scenario
        val kindTag = when (scenario.type) {
            FutureScenarioType.OPPORTUNITY_ACTION -> "opportunity-candidate"
            FutureScenarioType.KNOWLEDGE_ACQUISITION -> "knowledge-gap-candidate"
            FutureScenarioType.PREEMPTIVE_ACTION -> "goal-candidate"
            FutureScenarioType.INACTION_PRESSURE -> error("Observation-only future evidence cannot become a goal candidate")
        }
        val fingerprint = StableCognitiveIds.fingerprint(
            "future-goal-candidate/v1",
            decision.decisionFingerprint,
            scenario.id,
            java.lang.Double.toHexString(ranked.expectedImprovement),
        )
        val parentIds = linkedSetOf(decisionPhoton.id, record.photon.id) + scenario.sourcePhotonIds
        return Photon(
            id = PhotonId("future-goal-candidate-$fingerprint"),
            revision = 1,
            content = buildString {
                appendLine("scenario=${scenario.id}")
                appendLine("type=${scenario.type.name}")
                appendLine("horizon=${scenario.horizon.name}")
                appendLine("probability=${scenario.probability}")
                appendLine("expected_improvement=${ranked.expectedImprovement}")
                appendLine("resource_cost=${scenario.resourceCost}")
                appendLine("policy_revision=${decision.policyRevision}")
                appendLine("policy_fingerprint=${decision.policyFingerprint}")
                appendLine("resource_snapshot=${decision.resourceSnapshotFingerprint}")
                append("explanation=${scenario.explanation}")
            },
            mimeType = "application/vnd.lifeos.goal-candidate+text",
            phase = PhotonPhase.REFLECTING,
            semanticMass = record.photon.semanticMass,
            energy = record.photon.energy,
            confidence = minOf(record.photon.confidence, scenario.probability),
            provenance = Provenance(
                source = "future-planning",
                actor = "lifeos",
                createdAt = record.photon.provenance.createdAt,
                parentIds = parentIds,
            ),
            relations = setOf(
                PhotonRelation(decisionPhoton.id, RelationType.TRANSFORMS),
                PhotonRelation(record.photon.id, RelationType.DERIVED_FROM),
            ) + scenario.sourcePhotonIds.map { PhotonRelation(it, RelationType.REFERENCES) },
            tags = setOf(
                kindTag,
                "future-planning-output",
                "requires-goal-routing",
                "requires-owner-policy-at-execution",
                "non-executing",
            ),
        )
    }

    private fun lineageKey(scenario: FutureEvidenceScenario): String = scenario.sourcePhotonIds
        .map { it.value }
        .sorted()
        .joinToString("\u0000")
}

/** Keeps K planning on the same encrypted persistence path without recursively executing outputs. */
class FuturePlanningPersistence(
    private val delegate: CausalDerivedPhotonPersistence,
    private val planning: FuturePlanningCoordinator,
) : CausalDerivedPhotonPersistence {
    override suspend fun persist(photon: Photon, traceId: CausalTraceId) {
        delegate.persist(photon, traceId)
        planning.planPersisted(photon).forEach { planned -> delegate.persist(planned, traceId) }
    }
}
