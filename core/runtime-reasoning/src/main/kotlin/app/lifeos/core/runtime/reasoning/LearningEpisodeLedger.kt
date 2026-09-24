package app.lifeos.core.runtime.reasoning

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.reasoning.ProblemStateGraph
import app.lifeos.core.reasoning.ProblemStateGraphId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@JvmInline
value class LearningEpisodeId(val value: String) {
    init {
        require(value.startsWith(PREFIX))
        require(value.removePrefix(PREFIX).matches(Regex("[0-9a-f]{64}")))
    }

    companion object {
        const val PREFIX = "learning-episode:"
    }
}

enum class LearningEpisodeStatus {
    INCOMPLETE_OUTCOME,
    UNVERIFIED_OUTCOME,
    VERIFIED_OUTCOME,
    VERIFIED_WITH_CAUSAL_CREDIT,
}

data class LearningEpisodeSummary(
    val expectedActions: Int,
    val missingObservations: Int,
    val incompleteObservations: Int,
    val unverifiedObservations: Int,
    val withinExpectedBand: Int,
    val outsideExpectedBand: Int,
    val causalAssignments: Int,
) {
    init {
        val values = listOf(
            expectedActions,
            missingObservations,
            incompleteObservations,
            unverifiedObservations,
            withinExpectedBand,
            outsideExpectedBand,
            causalAssignments,
        )
        require(values.all { it >= 0 })
        require(missingObservations <= expectedActions)
        require(incompleteObservations <= expectedActions)
        require(unverifiedObservations <= expectedActions)
        require(withinExpectedBand + outsideExpectedBand <= expectedActions)
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "learning-episode-summary/v1",
        expectedActions.toString(),
        missingObservations.toString(),
        incompleteObservations.toString(),
        unverifiedObservations.toString(),
        withinExpectedBand.toString(),
        outsideExpectedBand.toString(),
        causalAssignments.toString(),
    )
}

data class LearningEpisode(
    val id: LearningEpisodeId,
    val sourceCycleId: String,
    val cycleRevision: Long,
    val predecessorId: LearningEpisodeId?,
    val problemGraphId: ProblemStateGraphId,
    val hypothesisSeedFingerprint: String,
    val reasoningSearchFingerprint: String,
    val counterfactualBatchFingerprint: String,
    val experimentPlanFingerprint: String,
    val expectationModelFingerprint: String,
    val predictionErrorReportFingerprint: String,
    val causalCreditReportFingerprint: String?,
    val status: LearningEpisodeStatus,
    val summary: LearningEpisodeSummary,
    val createdAt: Instant,
) {
    init {
        require(sourceCycleId.isNotBlank())
        require(cycleRevision > 0L)
        require((cycleRevision == 1L) == (predecessorId == null)) {
            "First learning episode revision must have no predecessor and later revisions require one"
        }
        require(hypothesisSeedFingerprint.isNotBlank())
        require(reasoningSearchFingerprint.isNotBlank())
        require(counterfactualBatchFingerprint.isNotBlank())
        require(experimentPlanFingerprint.isNotBlank())
        require(expectationModelFingerprint.isNotBlank())
        require(predictionErrorReportFingerprint.isNotBlank())
        causalCreditReportFingerprint?.let { require(it.isNotBlank()) }
        if (status == LearningEpisodeStatus.VERIFIED_WITH_CAUSAL_CREDIT) {
            require(causalCreditReportFingerprint != null)
            require(summary.causalAssignments > 0)
        } else {
            require(causalCreditReportFingerprint == null)
            require(summary.causalAssignments == 0)
        }
        require(id == expectedId())
    }

    val learningAuthority: Boolean
        get() = false

    val promotionAllowed: Boolean
        get() = false

    fun contentFingerprint(): String = episodeFingerprint(
        sourceCycleId = sourceCycleId,
        cycleRevision = cycleRevision,
        predecessorId = predecessorId,
        problemGraphId = problemGraphId,
        hypothesisSeedFingerprint = hypothesisSeedFingerprint,
        reasoningSearchFingerprint = reasoningSearchFingerprint,
        counterfactualBatchFingerprint = counterfactualBatchFingerprint,
        experimentPlanFingerprint = experimentPlanFingerprint,
        expectationModelFingerprint = expectationModelFingerprint,
        predictionErrorReportFingerprint = predictionErrorReportFingerprint,
        causalCreditReportFingerprint = causalCreditReportFingerprint,
        status = status,
        summary = summary,
        createdAt = createdAt,
    )

    private fun expectedId(): LearningEpisodeId =
        LearningEpisodeId(LearningEpisodeId.PREFIX + contentFingerprint())
}

/**
 * B374 creates one immutable revision of a learning episode from the exact B366-B373 lineage.
 * A later observation is an appended revision, never an in-place mutation. The episode records
 * provenance and learning signals only; it does not promote knowledge, skills, causal rules, or
 * reasoning strategies.
 */
class LearningEpisodeFactory {
    fun create(
        problem: ProblemStateGraph,
        hypothesisSeed: app.lifeos.core.reasoning.ProblemHypothesisSeed,
        search: app.lifeos.core.reasoning.ReasoningSearchResult,
        counterfactualBatch: ReasoningCounterfactualBatch,
        experimentPlan: ExperimentPlan,
        expectationModel: OutcomeExpectationModel,
        predictionErrorReport: PredictionErrorReport,
        causalCreditReport: CausalCreditAssignmentReport? = null,
        createdAt: Instant,
        previous: LearningEpisode? = null,
    ): LearningEpisode {
        require(hypothesisSeed.problemGraphId == problem.id) {
            "Hypothesis seed does not belong to the supplied problem graph"
        }
        require(search.seedFingerprint == hypothesisSeed.fingerprint) {
            "Reasoning search does not belong to the supplied hypothesis seed"
        }
        require(counterfactualBatch.searchFingerprint == search.fingerprint) {
            "Counterfactual batch does not belong to the supplied reasoning search"
        }
        require(experimentPlan.sourceCycleId.isNotBlank())
        require(experimentPlan.reasoningSearchFingerprint == search.fingerprint) {
            "Experiment plan does not belong to the supplied reasoning search"
        }
        require(experimentPlan.counterfactualBatchFingerprint == counterfactualBatch.fingerprint) {
            "Experiment plan does not belong to the supplied counterfactual batch"
        }
        require(expectationModel.sourceCycleId == experimentPlan.sourceCycleId)
        require(expectationModel.reasoningSearchFingerprint == search.fingerprint)
        require(expectationModel.counterfactualBatchFingerprint == counterfactualBatch.fingerprint)
        require(expectationModel.experimentPlanFingerprint == experimentPlan.fingerprint) {
            "Outcome expectation model does not belong to the supplied experiment plan"
        }
        require(predictionErrorReport.expectationModelFingerprint == expectationModel.fingerprint) {
            "Prediction error report does not belong to the supplied expectation model"
        }

        causalCreditReport?.let { causal ->
            require(predictionErrorReport.verifiedComplete) {
                "Causal credit cannot be attached to an unverified/incomplete learning episode"
            }
            require(causal.experimentPlanFingerprint == experimentPlan.fingerprint)
            require(causal.expectationModelFingerprint == expectationModel.fingerprint)
            require(causal.predictionErrorReportFingerprint == predictionErrorReport.fingerprint)
        }

        previous?.let {
            require(it.sourceCycleId == experimentPlan.sourceCycleId) {
                "Learning episode predecessor belongs to a different source cycle"
            }
            require(createdAt >= it.createdAt) {
                "Learning episode revision cannot predate its predecessor"
            }
        }

        val status = when {
            predictionErrorReport.missingObservationActionIds.isNotEmpty() ||
                predictionErrorReport.incompleteObservationActionIds.isNotEmpty() ->
                LearningEpisodeStatus.INCOMPLETE_OUTCOME
            predictionErrorReport.unverifiedObservationActionIds.isNotEmpty() ->
                LearningEpisodeStatus.UNVERIFIED_OUTCOME
            causalCreditReport != null ->
                LearningEpisodeStatus.VERIFIED_WITH_CAUSAL_CREDIT
            else -> LearningEpisodeStatus.VERIFIED_OUTCOME
        }
        val summary = LearningEpisodeSummary(
            expectedActions = predictionErrorReport.entries.size,
            missingObservations = predictionErrorReport.missingObservationActionIds.size,
            incompleteObservations = predictionErrorReport.incompleteObservationActionIds.size,
            unverifiedObservations = predictionErrorReport.unverifiedObservationActionIds.size,
            withinExpectedBand = predictionErrorReport.entries.count {
                it.state == PredictionErrorState.WITHIN_EXPECTED_BAND
            },
            outsideExpectedBand = predictionErrorReport.entries.count {
                it.state == PredictionErrorState.OUTSIDE_EXPECTED_BAND
            },
            causalAssignments = causalCreditReport?.assignments?.size ?: 0,
        )
        val cycleRevision = (previous?.cycleRevision ?: 0L) + 1L
        val predecessorId = previous?.id
        val fingerprint = episodeFingerprint(
            sourceCycleId = experimentPlan.sourceCycleId,
            cycleRevision = cycleRevision,
            predecessorId = predecessorId,
            problemGraphId = problem.id,
            hypothesisSeedFingerprint = hypothesisSeed.fingerprint,
            reasoningSearchFingerprint = search.fingerprint,
            counterfactualBatchFingerprint = counterfactualBatch.fingerprint,
            experimentPlanFingerprint = experimentPlan.fingerprint,
            expectationModelFingerprint = expectationModel.fingerprint,
            predictionErrorReportFingerprint = predictionErrorReport.fingerprint,
            causalCreditReportFingerprint = causalCreditReport?.fingerprint,
            status = status,
            summary = summary,
            createdAt = createdAt,
        )
        return LearningEpisode(
            id = LearningEpisodeId(LearningEpisodeId.PREFIX + fingerprint),
            sourceCycleId = experimentPlan.sourceCycleId,
            cycleRevision = cycleRevision,
            predecessorId = predecessorId,
            problemGraphId = problem.id,
            hypothesisSeedFingerprint = hypothesisSeed.fingerprint,
            reasoningSearchFingerprint = search.fingerprint,
            counterfactualBatchFingerprint = counterfactualBatch.fingerprint,
            experimentPlanFingerprint = experimentPlan.fingerprint,
            expectationModelFingerprint = expectationModel.fingerprint,
            predictionErrorReportFingerprint = predictionErrorReport.fingerprint,
            causalCreditReportFingerprint = causalCreditReport?.fingerprint,
            status = status,
            summary = summary,
            createdAt = createdAt,
        )
    }
}

data class LearningEpisodeState(
    val revision: Long = 0L,
    val episodes: List<LearningEpisode> = emptyList(),
) {
    init {
        require(revision == episodes.size.toLong())
        require(episodes.map { it.id }.distinct().size == episodes.size)
        require(episodes == episodes.sortedBy { it.id.value })
    }

    val fingerprint: String = StableFieldIds.fingerprint(
        "learning-episode-state/v1",
        revision.toString(),
        *episodes.map { "episode:" + it.id.value }.toTypedArray(),
    )

    fun latestFor(sourceCycleId: String): LearningEpisode? =
        episodes.filter { it.sourceCycleId == sourceCycleId }.maxByOrNull { it.cycleRevision }
}

data class LearningEpisodeApplyResult(
    val state: LearningEpisodeState,
    val replayed: Boolean,
)

class LearningEpisodeReducer {
    fun apply(
        state: LearningEpisodeState,
        episode: LearningEpisode,
    ): LearningEpisodeApplyResult {
        state.episodes.firstOrNull { it.id == episode.id }?.let { existing ->
            require(existing == episode) { "Learning episode identity collision" }
            return LearningEpisodeApplyResult(state, replayed = true)
        }
        val previous = state.latestFor(episode.sourceCycleId)
        require(episode.predecessorId == previous?.id) {
            "Learning episode predecessor does not match source-cycle head"
        }
        require(episode.cycleRevision == (previous?.cycleRevision ?: 0L) + 1L) {
            "Learning episode cycle revision is not contiguous"
        }
        previous?.let {
            require(episode.createdAt >= it.createdAt) {
                "Learning episode revision cannot predate source-cycle head"
            }
        }

        val next = (state.episodes + episode).sortedBy { it.id.value }
        return LearningEpisodeApplyResult(
            state = LearningEpisodeState(
                revision = next.size.toLong(),
                episodes = next,
            ),
            replayed = false,
        )
    }

    fun replay(episodes: List<LearningEpisode>): LearningEpisodeState {
        require(episodes.map { it.id }.distinct().size == episodes.size) {
            "Learning episode replay cannot contain duplicate physical events"
        }
        var state = LearningEpisodeState()
        val remaining = episodes.toMutableList()
        while (remaining.isNotEmpty()) {
            val knownIds = state.episodes.mapTo(mutableSetOf()) { it.id }
            val ready = remaining
                .filter { it.predecessorId == null || it.predecessorId in knownIds }
                .sortedWith(
                    compareBy<LearningEpisode> { it.sourceCycleId }
                        .thenBy { it.cycleRevision }
                        .thenBy { it.createdAt }
                        .thenBy { it.id.value }
                )
            require(ready.isNotEmpty()) {
                "Learning episode history has a missing/cyclic predecessor"
            }
            var progressed = false
            for (episode in ready) {
                val previous = state.latestFor(episode.sourceCycleId)
                if (episode.predecessorId != previous?.id) continue
                state = apply(state, episode).state
                remaining.remove(episode)
                progressed = true
            }
            require(progressed) {
                "Learning episode history forks or cannot be replayed"
            }
        }
        return state
    }
}

sealed interface LearningEpisodeWriteResult {
    val episode: LearningEpisode

    data class Stored(override val episode: LearningEpisode) : LearningEpisodeWriteResult
    data class Duplicate(override val episode: LearningEpisode) : LearningEpisodeWriteResult
}

data class LearningEpisodeLoadReport(
    val episodes: List<LearningEpisode>,
    val unreadableEntries: List<String>,
) {
    init {
        require(episodes.map { it.id }.distinct().size == episodes.size)
        require(unreadableEntries == unreadableEntries.distinct().sorted())
    }

    val isCorrupted: Boolean
        get() = unreadableEntries.isNotEmpty()
}

interface LearningEpisodeRepository {
    suspend fun save(episode: LearningEpisode): LearningEpisodeWriteResult
    suspend fun load(id: LearningEpisodeId): LearningEpisode?
    suspend fun loadReport(): LearningEpisodeLoadReport
}

data class LearningEpisodeRehydrateReport(
    val restoredEpisodes: Int,
    val state: LearningEpisodeState,
)

/** Persistence-before-RAM owner of the immutable B374 episode history. */
class DurableLearningEpisodeLedger(
    private val repository: LearningEpisodeRepository,
    private val reducer: LearningEpisodeReducer = LearningEpisodeReducer(),
) {
    private val mutex = Mutex()
    private var current = LearningEpisodeState()

    val state: LearningEpisodeState
        get() = current

    suspend fun append(episode: LearningEpisode): LearningEpisodeApplyResult = mutex.withLock {
        val persisted = repository.save(episode).episode
        require(persisted == episode) {
            "Persisted learning episode differs from requested event"
        }
        reducer.apply(current, persisted).also { current = it.state }
    }

    suspend fun rehydrate(): LearningEpisodeRehydrateReport = mutex.withLock {
        val report = repository.loadReport()
        require(!report.isCorrupted) {
            "Learning episode history is corrupted: " +
                report.unreadableEntries.joinToString(",")
        }
        val restored = reducer.replay(report.episodes)
        current = restored
        LearningEpisodeRehydrateReport(
            restoredEpisodes = report.episodes.size,
            state = restored,
        )
    }
}

/** Stable bounded binary codec for one immutable B374 episode revision. */
object LearningEpisodeCodec {
    const val MAX_PAYLOAD_BYTES = 512 * 1024
    const val CODEC_VERSION = 1
    private const val MAGIC = 0x4c455034 // LEP4
    private const val MAX_STRING_BYTES = 64 * 1024

    fun encode(episode: LearningEpisode): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(CODEC_VERSION)
            out.writeString(episode.id.value)
            out.writeString(episode.sourceCycleId)
            out.writeLong(episode.cycleRevision)
            out.writeNullableString(episode.predecessorId?.value)
            out.writeString(episode.problemGraphId.value)
            out.writeString(episode.hypothesisSeedFingerprint)
            out.writeString(episode.reasoningSearchFingerprint)
            out.writeString(episode.counterfactualBatchFingerprint)
            out.writeString(episode.experimentPlanFingerprint)
            out.writeString(episode.expectationModelFingerprint)
            out.writeString(episode.predictionErrorReportFingerprint)
            out.writeNullableString(episode.causalCreditReportFingerprint)
            out.writeString(episode.status.name)
            out.writeInt(episode.summary.expectedActions)
            out.writeInt(episode.summary.missingObservations)
            out.writeInt(episode.summary.incompleteObservations)
            out.writeInt(episode.summary.unverifiedObservations)
            out.writeInt(episode.summary.withinExpectedBand)
            out.writeInt(episode.summary.outsideExpectedBand)
            out.writeInt(episode.summary.causalAssignments)
            out.writeString(episode.createdAt.toString())
        }
        return output.toByteArray().also {
            require(it.isNotEmpty() && it.size <= MAX_PAYLOAD_BYTES) {
                "Learning episode payload too large"
            }
        }
    }

    fun decode(payload: ByteArray): LearningEpisode {
        require(payload.isNotEmpty() && payload.size <= MAX_PAYLOAD_BYTES) {
            "Invalid learning episode payload size"
        }
        val input = DataInputStream(ByteArrayInputStream(payload))
        require(input.readInt() == MAGIC) { "Unsupported learning episode magic" }
        require(input.readInt() == CODEC_VERSION) { "Unsupported learning episode codec version" }
        val episode = LearningEpisode(
            id = LearningEpisodeId(input.readString()),
            sourceCycleId = input.readString(),
            cycleRevision = input.readLong(),
            predecessorId = input.readNullableString()?.let(::LearningEpisodeId),
            problemGraphId = ProblemStateGraphId(input.readString()),
            hypothesisSeedFingerprint = input.readString(),
            reasoningSearchFingerprint = input.readString(),
            counterfactualBatchFingerprint = input.readString(),
            experimentPlanFingerprint = input.readString(),
            expectationModelFingerprint = input.readString(),
            predictionErrorReportFingerprint = input.readString(),
            causalCreditReportFingerprint = input.readNullableString(),
            status = LearningEpisodeStatus.valueOf(input.readString()),
            summary = LearningEpisodeSummary(
                expectedActions = input.readInt(),
                missingObservations = input.readInt(),
                incompleteObservations = input.readInt(),
                unverifiedObservations = input.readInt(),
                withinExpectedBand = input.readInt(),
                outsideExpectedBand = input.readInt(),
                causalAssignments = input.readInt(),
            ),
            createdAt = Instant.parse(input.readString()),
        )
        require(input.available() == 0) { "Trailing learning episode bytes" }
        return episode
    }

    private fun DataOutputStream.writeNullableString(value: String?) {
        writeBoolean(value != null)
        if (value != null) writeString(value)
    }

    private fun DataInputStream.readNullableString(): String? =
        if (readBoolean()) readString() else null

    private fun DataOutputStream.writeString(value: String) {
        val encoded = value.toByteArray(StandardCharsets.UTF_8)
        require(encoded.size <= MAX_STRING_BYTES) { "Learning episode string too large" }
        writeInt(encoded.size)
        write(encoded)
    }

    private fun DataInputStream.readString(): String {
        val length = readInt()
        require(length in 0..MAX_STRING_BYTES) { "Invalid learning episode string length" }
        val bytes = ByteArray(length)
        readFully(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }
}

private fun episodeFingerprint(
    sourceCycleId: String,
    cycleRevision: Long,
    predecessorId: LearningEpisodeId?,
    problemGraphId: ProblemStateGraphId,
    hypothesisSeedFingerprint: String,
    reasoningSearchFingerprint: String,
    counterfactualBatchFingerprint: String,
    experimentPlanFingerprint: String,
    expectationModelFingerprint: String,
    predictionErrorReportFingerprint: String,
    causalCreditReportFingerprint: String?,
    status: LearningEpisodeStatus,
    summary: LearningEpisodeSummary,
    createdAt: Instant,
): String = StableFieldIds.fingerprint(
    "learning-episode/v1",
    sourceCycleId,
    cycleRevision.toString(),
    predecessorId?.value.orEmpty(),
    problemGraphId.value,
    hypothesisSeedFingerprint,
    reasoningSearchFingerprint,
    counterfactualBatchFingerprint,
    experimentPlanFingerprint,
    expectationModelFingerprint,
    predictionErrorReportFingerprint,
    causalCreditReportFingerprint.orEmpty(),
    status.name,
    summary.fingerprint(),
    createdAt.toString(),
)


// ---- B458 Temporal Episode Graph ----

@JvmInline
value class TemporalEpisodeNodeId(val value: String) {
    init {
        require(value.startsWith(PREFIX))
        require(value.removePrefix(PREFIX).matches(Regex("[0-9a-f]{64}")))
    }

    companion object {
        const val PREFIX = "temporal-episode-node:"
    }
}

@JvmInline
value class TemporalEpisodeEdgeId(val value: String) {
    init {
        require(value.startsWith(PREFIX))
        require(value.removePrefix(PREFIX).matches(Regex("[0-9a-f]{64}")))
    }

    companion object {
        const val PREFIX = "temporal-episode-edge:"
    }
}

enum class TemporalEpisodeNodeKind {
    OBSERVATION,
    GOAL,
    ACTION,
    RECEIPT,
    OUTCOME,
}

enum class TemporalEpisodeEdgeKind {
    PRECEDES,
    REFERENCES,
    EXPECTS,
    RECEIPT_FOR,
    VERIFIES,
    ASSOCIATED_WITH,
}

data class TemporalEpisodeNode(
    val id: TemporalEpisodeNodeId,
    val kind: TemporalEpisodeNodeKind,
    val sourceRef: String,
    val occurredAt: Instant,
    val payloadFingerprint: String,
    val attributes: Map<String, String> = emptyMap(),
) {
    init {
        require(sourceRef.isNotBlank())
        require(payloadFingerprint.isNotBlank())
        require(attributes.keys.none { it.isBlank() })
        require(id == expectedId())
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "temporal-episode-node/v1",
        kind.name,
        sourceRef,
        occurredAt.toString(),
        payloadFingerprint,
        *attributes.toSortedMap().flatMap { (key, value) ->
            listOf(key, value)
        }.toTypedArray(),
    )

    private fun expectedId(): TemporalEpisodeNodeId =
        TemporalEpisodeNodeId(
            TemporalEpisodeNodeId.PREFIX + fingerprint()
        )

    companion object {
        fun create(
            kind: TemporalEpisodeNodeKind,
            sourceRef: String,
            occurredAt: Instant,
            payloadFingerprint: String,
            attributes: Map<String, String> = emptyMap(),
        ): TemporalEpisodeNode {
            val fingerprint = StableFieldIds.fingerprint(
                "temporal-episode-node/v1",
                kind.name,
                sourceRef,
                occurredAt.toString(),
                payloadFingerprint,
                *attributes.toSortedMap().flatMap { (key, value) ->
                    listOf(key, value)
                }.toTypedArray(),
            )
            return TemporalEpisodeNode(
                id = TemporalEpisodeNodeId(
                    TemporalEpisodeNodeId.PREFIX + fingerprint
                ),
                kind = kind,
                sourceRef = sourceRef,
                occurredAt = occurredAt,
                payloadFingerprint = payloadFingerprint,
                attributes = attributes.toSortedMap(),
            )
        }
    }
}

data class TemporalEpisodeEdge(
    val id: TemporalEpisodeEdgeId,
    val source: TemporalEpisodeNodeId,
    val target: TemporalEpisodeNodeId,
    val kind: TemporalEpisodeEdgeKind,
    val provenanceFingerprint: String,
) {
    init {
        require(source != target) { "Temporal episode edge cannot point to itself" }
        require(provenanceFingerprint.isNotBlank())
        require(id == expectedId())
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "temporal-episode-edge/v1",
        source.value,
        target.value,
        kind.name,
        provenanceFingerprint,
    )

    private fun expectedId(): TemporalEpisodeEdgeId =
        TemporalEpisodeEdgeId(
            TemporalEpisodeEdgeId.PREFIX + fingerprint()
        )

    companion object {
        fun create(
            source: TemporalEpisodeNodeId,
            target: TemporalEpisodeNodeId,
            kind: TemporalEpisodeEdgeKind,
            provenanceFingerprint: String,
        ): TemporalEpisodeEdge {
            val fingerprint = StableFieldIds.fingerprint(
                "temporal-episode-edge/v1",
                source.value,
                target.value,
                kind.name,
                provenanceFingerprint,
            )
            return TemporalEpisodeEdge(
                id = TemporalEpisodeEdgeId(
                    TemporalEpisodeEdgeId.PREFIX + fingerprint
                ),
                source = source,
                target = target,
                kind = kind,
                provenanceFingerprint = provenanceFingerprint,
            )
        }
    }
}

/**
 * B458 immutable temporal association graph.
 *
 * The graph can express chronology, references, expectations and verification relationships. It
 * deliberately has no CAUSES edge. Temporal succession/association never becomes causal knowledge;
 * causal credit remains owned by the separate verified causal-learning path.
 */
data class TemporalEpisodeGraph private constructor(
    val id: String,
    val nodes: List<TemporalEpisodeNode>,
    val edges: List<TemporalEpisodeEdge>,
) {
    init {
        require(nodes.isNotEmpty()) { "Temporal episode graph requires at least one node" }
        require(nodes.map { it.id }.distinct().size == nodes.size)
        require(
            nodes == nodes.sortedWith(
                compareBy<TemporalEpisodeNode> { it.occurredAt }
                    .thenBy { it.id.value }
            )
        ) {
            "Temporal episode nodes must be deterministic"
        }
        require(edges.map { it.id }.distinct().size == edges.size)
        require(edges == edges.sortedBy { it.id.value }) {
            "Temporal episode edges must be deterministic"
        }

        val byId = nodes.associateBy { it.id }
        edges.forEach { edge ->
            val sourceNode = requireNotNull(byId[edge.source]) {
                "Temporal episode edge source is absent"
            }
            val targetNode = requireNotNull(byId[edge.target]) {
                "Temporal episode edge target is absent"
            }
            if (edge.kind == TemporalEpisodeEdgeKind.PRECEDES) {
                require(!sourceNode.occurredAt.isAfter(targetNode.occurredAt)) {
                    "PRECEDES edge contradicts node timestamps"
                }
            }
        }
        require(id == "temporal-episode:${fingerprint()}")
    }

    val causalClaimsAllowed: Boolean
        get() = false

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "temporal-episode-graph/v1",
        *buildList {
            nodes.forEach { add("node:${it.id.value}:${it.fingerprint()}") }
            edges.forEach { add("edge:${it.id.value}:${it.fingerprint()}") }
        }.toTypedArray(),
    )

    companion object {
        fun create(
            nodes: Collection<TemporalEpisodeNode>,
            edges: Collection<TemporalEpisodeEdge>,
        ): TemporalEpisodeGraph {
            val canonicalNodes = nodes.sortedWith(
                compareBy<TemporalEpisodeNode> { it.occurredAt }
                    .thenBy { it.id.value }
            )
            val canonicalEdges = edges.sortedBy { it.id.value }
            val fingerprint = StableFieldIds.fingerprint(
                "temporal-episode-graph/v1",
                *buildList {
                    canonicalNodes.forEach {
                        add("node:${it.id.value}:${it.fingerprint()}")
                    }
                    canonicalEdges.forEach {
                        add("edge:${it.id.value}:${it.fingerprint()}")
                    }
                }.toTypedArray(),
            )
            return TemporalEpisodeGraph(
                id = "temporal-episode:$fingerprint",
                nodes = canonicalNodes,
                edges = canonicalEdges,
            )
        }
    }
}
