package app.lifeos.core.runtime.learning

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface LearningAdaptationWriteResult {
    val event: LearningAdaptation

    data class Stored(override val event: LearningAdaptation) : LearningAdaptationWriteResult
    data class Duplicate(override val event: LearningAdaptation) : LearningAdaptationWriteResult
}

data class LearningAdaptationLoadReport(
    val events: List<LearningAdaptation>,
    val unreadableEntries: List<String>,
) {
    init {
        require(events.map { it.id }.distinct().size == events.size) {
            "Learning adaptation load report contains duplicate ids"
        }
        require(unreadableEntries == unreadableEntries.distinct().sorted())
    }

    val isCorrupted: Boolean get() = unreadableEntries.isNotEmpty()
}

interface LearningAdaptationRepository {
    suspend fun save(event: LearningAdaptation): LearningAdaptationWriteResult
    suspend fun load(id: LearningAdaptationId): LearningAdaptation?
    suspend fun loadReport(): LearningAdaptationLoadReport
}

data class LearningAdaptationRehydrateReport(
    val restoredEvents: Int,
    val state: LearningAdaptationState,
)

/** Persistence-before-RAM owner of the append-only adaptation history. */
class DurableLearningAdaptationLedger(
    private val repository: LearningAdaptationRepository,
    private val reducer: LearningAdaptationReducer = LearningAdaptationReducer(),
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(LearningAdaptationState())
    val state: StateFlow<LearningAdaptationState> = mutableState.asStateFlow()

    suspend fun append(event: LearningAdaptation): LearningAdaptationApplyResult = mutex.withLock {
        val persisted = repository.save(event).event
        require(persisted == event) { "Persisted learning adaptation differs from requested event" }
        val applied = reducer.apply(mutableState.value, persisted)
        mutableState.value = applied.state
        applied
    }

    suspend fun rehydrate(): LearningAdaptationRehydrateReport = mutex.withLock {
        val report = repository.loadReport()
        require(!report.isCorrupted) {
            "Learning adaptation history is corrupted: ${report.unreadableEntries.joinToString(",")}" 
        }
        val restored = reducer.replay(report.events)
        mutableState.value = restored
        LearningAdaptationRehydrateReport(
            restoredEvents = report.events.size,
            state = restored,
        )
    }

    fun effectiveValue(target: LearningAdaptationTarget, baselineValue: Double): Double {
        require(baselineValue.isFinite() && baselineValue in 0.0..1.0)
        return mutableState.value.effectiveValues[target] ?: baselineValue
    }

    fun latestFor(target: LearningAdaptationTarget): LearningAdaptation? =
        mutableState.value.latestFor(target)
}

/** Stable bounded binary codec for one immutable adaptation event. */
object LearningAdaptationCodec {
    const val MAX_PAYLOAD_BYTES = 256 * 1024
    private const val MAGIC = 0x4c414436 // LAD6
    private const val VERSION = 1
    private const val MAX_STRING_BYTES = 32 * 1024

    fun encode(event: LearningAdaptation): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeString(event.id.value)
            out.writeString(event.target.kind.name)
            out.writeString(event.target.key)
            out.writeNullableString(event.predecessorId?.value)
            out.writeString(event.outcomePredictionId.value)
            out.writeString(event.outcomeScoreId.value)
            out.writeString(event.outcomeScoreFingerprint)
            out.writeDouble(event.baselineValue)
            out.writeDouble(event.priorEffectiveValue)
            out.writeDouble(event.delta)
            out.writeDouble(event.resultingEffectiveValue)
            out.writeString(event.policyFingerprint)
            out.writeString(event.createdAt.toString())
            out.writeNullableString(event.rollbackOf?.value)
        }
        return bytes.toByteArray().also {
            require(it.isNotEmpty() && it.size <= MAX_PAYLOAD_BYTES) {
                "Learning adaptation payload too large"
            }
        }
    }

    fun decode(payload: ByteArray): LearningAdaptation {
        require(payload.isNotEmpty() && payload.size <= MAX_PAYLOAD_BYTES) {
            "Invalid learning adaptation payload size"
        }
        val input = DataInputStream(ByteArrayInputStream(payload))
        require(input.readInt() == MAGIC) { "Unsupported learning adaptation magic" }
        require(input.readInt() == VERSION) { "Unsupported learning adaptation version" }
        val event = LearningAdaptation(
            id = LearningAdaptationId(input.readString()),
            target = LearningAdaptationTarget(
                kind = LearningAdaptationTargetKind.valueOf(input.readString()),
                key = input.readString(),
            ),
            predecessorId = input.readNullableString()?.let(::LearningAdaptationId),
            outcomePredictionId = OutcomePredictionId(input.readString()),
            outcomeScoreId = OutcomeScoreId(input.readString()),
            outcomeScoreFingerprint = input.readString(),
            baselineValue = input.readDouble(),
            priorEffectiveValue = input.readDouble(),
            delta = input.readDouble(),
            resultingEffectiveValue = input.readDouble(),
            policyFingerprint = input.readString(),
            createdAt = Instant.parse(input.readString()),
            rollbackOf = input.readNullableString()?.let(::LearningAdaptationId),
        )
        require(input.available() == 0) { "Trailing learning adaptation bytes" }
        return event
    }

    private fun DataOutputStream.writeNullableString(value: String?) {
        writeBoolean(value != null)
        if (value != null) writeString(value)
    }

    private fun DataInputStream.readNullableString(): String? =
        if (readBoolean()) readString() else null

    private fun DataOutputStream.writeString(value: String) {
        val encoded = value.toByteArray(StandardCharsets.UTF_8)
        require(encoded.size <= MAX_STRING_BYTES) { "Learning adaptation string too large" }
        writeInt(encoded.size)
        write(encoded)
    }

    private fun DataInputStream.readString(): String {
        val length = readInt()
        require(length in 0..MAX_STRING_BYTES) { "Invalid learning adaptation string length" }
        val bytes = ByteArray(length)
        readFully(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }
}
