package app.lifeos.core.field

import java.nio.charset.StandardCharsets
import java.util.Base64

data class FieldSnapshotHypothesis(
    val id: HypothesisId,
    val state: HypothesisState,
    val score: HypothesisScore,
)

/**
 * Persistable convergence checkpoint. The snapshot stores the complete numerical state plus the
 * final hypothesis decisions and binds them to the exact input, field set and explanation trace.
 */
data class FieldSnapshot(
    val id: FieldSnapshotId,
    val runId: FieldRunId,
    val domainId: FieldDomainId,
    val status: ConvergenceStatus,
    val state: FieldState,
    val hypotheses: List<FieldSnapshotHypothesis>,
    val inputFingerprint: String,
    val fieldSetFingerprint: String,
    val traceFingerprint: String,
) {
    init {
        require(state.runId == runId) { "Snapshot state run id must match snapshot run id" }
        require(state.domainId == domainId) { "Snapshot state domain must match snapshot domain" }
        require(hypotheses.map { it.id }.distinct().size == hypotheses.size) {
            "Snapshot hypothesis ids must be unique"
        }
        require(hypotheses.map { it.id }.toSet() == state.energy.hypothesisEnergy.keys) {
            "Snapshot hypotheses must exactly match hypothesis energy ids"
        }
        require(inputFingerprint.isNotBlank())
        require(fieldSetFingerprint.isNotBlank())
        require(traceFingerprint.isNotBlank())
        require(id == expectedId()) { "Snapshot id does not match snapshot content" }
    }

    fun contentFingerprint(): String = StableFieldIds.fingerprint(*contentParts().toTypedArray())

    private fun expectedId(): FieldSnapshotId = StableFieldIds.snapshot(domainId, contentFingerprint())

    private fun contentParts(): List<String> = buildList {
        add("field-snapshot/v1")
        add(runId.value)
        add(domainId.value)
        add(status.name)
        add(inputFingerprint)
        add(fieldSetFingerprint)
        add(traceFingerprint)
        add(state.iteration.index.toString())
        add(snapshotDouble(state.iteration.maxDelta))
        add(state.iteration.stableRounds.toString())
        add(state.iteration.fingerprint)
        state.energy.nodeEnergy.entries.sortedBy { it.key.value }.forEach { (id, energy) ->
            add("node")
            add(id.value)
            add(snapshotDouble(energy))
        }
        state.energy.hypothesisEnergy.entries.sortedBy { it.key.value }.forEach { (id, energy) ->
            add("hypothesis-energy")
            add(id.value)
            add(snapshotDouble(energy))
        }
        hypotheses.sortedBy { it.id.value }.forEach { hypothesis ->
            add("hypothesis")
            add(hypothesis.id.value)
            add(hypothesis.state.name)
            addScore(hypothesis.score)
        }
    }

    private fun MutableList<String>.addScore(score: HypothesisScore) {
        add(snapshotDouble(score.evidence))
        add(snapshotDouble(score.support))
        add(snapshotDouble(score.contradiction))
        add(snapshotDouble(score.context))
        add(snapshotDouble(score.temporal))
        add(snapshotDouble(score.authority))
        add(snapshotDouble(score.total))
    }

    companion object {
        fun create(
            status: ConvergenceStatus,
            state: FieldState,
            hypotheses: List<FieldHypothesis>,
            inputFingerprint: String,
            fieldSetFingerprint: String,
            traceFingerprint: String,
        ): FieldSnapshot {
            val snapshotHypotheses = hypotheses.map {
                FieldSnapshotHypothesis(id = it.id, state = it.state, score = it.score)
            }.sortedBy { it.id.value }
            val provisional = SnapshotContent(
                runId = state.runId,
                domainId = state.domainId,
                status = status,
                state = state,
                hypotheses = snapshotHypotheses,
                inputFingerprint = inputFingerprint,
                fieldSetFingerprint = fieldSetFingerprint,
                traceFingerprint = traceFingerprint,
            )
            val id = StableFieldIds.snapshot(state.domainId, provisional.contentFingerprint())
            return FieldSnapshot(
                id = id,
                runId = state.runId,
                domainId = state.domainId,
                status = status,
                state = state,
                hypotheses = snapshotHypotheses,
                inputFingerprint = inputFingerprint,
                fieldSetFingerprint = fieldSetFingerprint,
                traceFingerprint = traceFingerprint,
            )
        }
    }

    private data class SnapshotContent(
        val runId: FieldRunId,
        val domainId: FieldDomainId,
        val status: ConvergenceStatus,
        val state: FieldState,
        val hypotheses: List<FieldSnapshotHypothesis>,
        val inputFingerprint: String,
        val fieldSetFingerprint: String,
        val traceFingerprint: String,
    ) {
        fun contentFingerprint(): String {
            val parts = buildList {
                add("field-snapshot/v1")
                add(runId.value)
                add(domainId.value)
                add(status.name)
                add(inputFingerprint)
                add(fieldSetFingerprint)
                add(traceFingerprint)
                add(state.iteration.index.toString())
                add(snapshotDouble(state.iteration.maxDelta))
                add(state.iteration.stableRounds.toString())
                add(state.iteration.fingerprint)
                state.energy.nodeEnergy.entries.sortedBy { it.key.value }.forEach { (id, energy) ->
                    add("node"); add(id.value); add(snapshotDouble(energy))
                }
                state.energy.hypothesisEnergy.entries.sortedBy { it.key.value }.forEach { (id, energy) ->
                    add("hypothesis-energy"); add(id.value); add(snapshotDouble(energy))
                }
                hypotheses.sortedBy { it.id.value }.forEach { hypothesis ->
                    add("hypothesis")
                    add(hypothesis.id.value)
                    add(hypothesis.state.name)
                    val score = hypothesis.score
                    add(snapshotDouble(score.evidence))
                    add(snapshotDouble(score.support))
                    add(snapshotDouble(score.contradiction))
                    add(snapshotDouble(score.context))
                    add(snapshotDouble(score.temporal))
                    add(snapshotDouble(score.authority))
                    add(snapshotDouble(score.total))
                }
            }
            return StableFieldIds.fingerprint(*parts.toTypedArray())
        }
    }
}

/** Stable, dependency-free text codec for encrypted stores, files or durable task payloads. */
object FieldSnapshotCodec {
    private const val HEADER = "LIFEOS_FIELD_SNAPSHOT_V1"

    fun encode(snapshot: FieldSnapshot): String = buildString {
        appendLine(HEADER)
        append("M|")
        append(token(snapshot.id.value)); append('|')
        append(token(snapshot.runId.value)); append('|')
        append(token(snapshot.domainId.value)); append('|')
        append(snapshot.status.name); append('|')
        append(snapshot.inputFingerprint); append('|')
        append(snapshot.fieldSetFingerprint); append('|')
        append(snapshot.traceFingerprint); append('|')
        append(snapshot.state.iteration.index); append('|')
        append(snapshotDouble(snapshot.state.iteration.maxDelta)); append('|')
        append(snapshot.state.iteration.stableRounds); append('|')
        appendLine(snapshot.state.iteration.fingerprint)
        snapshot.state.energy.nodeEnergy.entries.sortedBy { it.key.value }.forEach { (id, energy) ->
            append("N|"); append(token(id.value)); append('|'); appendLine(snapshotDouble(energy))
        }
        snapshot.state.energy.hypothesisEnergy.entries.sortedBy { it.key.value }.forEach { (id, energy) ->
            append("E|"); append(token(id.value)); append('|'); appendLine(snapshotDouble(energy))
        }
        snapshot.hypotheses.sortedBy { it.id.value }.forEach { hypothesis ->
            val score = hypothesis.score
            append("H|"); append(token(hypothesis.id.value)); append('|'); append(hypothesis.state.name)
            append('|'); append(snapshotDouble(score.evidence))
            append('|'); append(snapshotDouble(score.support))
            append('|'); append(snapshotDouble(score.contradiction))
            append('|'); append(snapshotDouble(score.context))
            append('|'); append(snapshotDouble(score.temporal))
            append('|'); append(snapshotDouble(score.authority))
            append('|'); appendLine(snapshotDouble(score.total))
        }
    }

    fun decode(encoded: String): FieldSnapshot {
        val lines = encoded.lineSequence().filter { it.isNotBlank() }.toList()
        require(lines.firstOrNull() == HEADER) { "Unsupported field snapshot format" }
        val meta = lines.getOrNull(1)?.split('|') ?: error("Missing field snapshot metadata")
        require(meta.size == 12 && meta[0] == "M") { "Malformed field snapshot metadata" }

        val snapshotId = FieldSnapshotId(untoken(meta[1]))
        val runId = FieldRunId(untoken(meta[2]))
        val domainId = FieldDomainId(untoken(meta[3]))
        val status = ConvergenceStatus.valueOf(meta[4])
        val inputFingerprint = meta[5]
        val fieldSetFingerprint = meta[6]
        val traceFingerprint = meta[7]
        val iterationIndex = meta[8].toInt()
        val maxDelta = parseDouble(meta[9])
        val stableRounds = meta[10].toInt()
        val iterationFingerprint = meta[11]

        val nodeEnergy = linkedMapOf<FieldNodeId, Double>()
        val hypothesisEnergy = linkedMapOf<HypothesisId, Double>()
        val hypotheses = mutableListOf<FieldSnapshotHypothesis>()
        lines.drop(2).forEach { line ->
            val parts = line.split('|')
            when (parts.firstOrNull()) {
                "N" -> {
                    require(parts.size == 3) { "Malformed snapshot node energy" }
                    nodeEnergy[FieldNodeId(untoken(parts[1]))] = parseDouble(parts[2])
                }
                "E" -> {
                    require(parts.size == 3) { "Malformed snapshot hypothesis energy" }
                    hypothesisEnergy[HypothesisId(untoken(parts[1]))] = parseDouble(parts[2])
                }
                "H" -> {
                    require(parts.size == 10) { "Malformed snapshot hypothesis" }
                    hypotheses += FieldSnapshotHypothesis(
                        id = HypothesisId(untoken(parts[1])),
                        state = HypothesisState.valueOf(parts[2]),
                        score = HypothesisScore(
                            evidence = parseDouble(parts[3]),
                            support = parseDouble(parts[4]),
                            contradiction = parseDouble(parts[5]),
                            context = parseDouble(parts[6]),
                            temporal = parseDouble(parts[7]),
                            authority = parseDouble(parts[8]),
                            total = parseDouble(parts[9]),
                        ),
                    )
                }
                else -> error("Unknown field snapshot record")
            }
        }

        val energy = FieldEnergySnapshot(nodeEnergy, hypothesisEnergy)
        val iteration = FieldIteration(
            index = iterationIndex,
            maxDelta = maxDelta,
            stableRounds = stableRounds,
            fingerprint = iterationFingerprint,
        )
        val state = FieldState(runId = runId, domainId = domainId, energy = energy, iteration = iteration)
        return FieldSnapshot(
            id = snapshotId,
            runId = runId,
            domainId = domainId,
            status = status,
            state = state,
            hypotheses = hypotheses.sortedBy { it.id.value },
            inputFingerprint = inputFingerprint,
            fieldSetFingerprint = fieldSetFingerprint,
            traceFingerprint = traceFingerprint,
        )
    }

    private fun token(value: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun untoken(value: String): String = String(
        Base64.getUrlDecoder().decode(value),
        StandardCharsets.UTF_8,
    )
}

private fun snapshotDouble(value: Double): String = java.lang.Double.toHexString(value)
private fun parseDouble(value: String): Double = java.lang.Double.valueOf(value)
