package app.lifeos.core.data.goal

import android.content.Context
import app.lifeos.core.data.security.EncryptedLedgerVaultSupport
import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.model.PhotonId
import app.lifeos.core.runtime.goal.GoalCognitiveCycleBindingRecord
import app.lifeos.core.runtime.goal.GoalCognitiveCycleBindingRepository
import app.lifeos.core.runtime.goal.GoalCognitiveCycleBindingState
import app.lifeos.core.runtime.goal.GoalConvergenceCycleBinding
import app.lifeos.core.runtime.goal.GoalPlanId
import app.lifeos.core.runtime.world.CognitiveCycleId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class EncryptedGoalCognitiveCycleBindingRepository(
    context: Context,
) : GoalCognitiveCycleBindingRepository {
    private val directory = context.filesDir.resolve(ROOT_DIRECTORY)
    private val key: SecretKey by lazy {
        EncryptedLedgerVaultSupport.loadOrCreateKey(KEY_ALIAS)
    }

    override suspend fun load(
        planId: GoalPlanId,
    ): GoalCognitiveCycleBindingRecord? = withContext(Dispatchers.IO) {
        processMutex.withLock {
            ensureDirectory()
            val file = fileFor(planId)
            if (!exists(file)) return@withLock null
            read(file, planId)
        }
    }

    override suspend fun compareAndSet(
        planId: GoalPlanId,
        expectedRevision: Long?,
        next: GoalCognitiveCycleBindingRecord,
    ): Boolean = withContext(Dispatchers.IO) {
        processMutex.withLock {
            require(next.planId == planId) {
                "Goal cognitive-cycle binding CAS plan mismatch"
            }
            ensureDirectory()
            val file = fileFor(planId)
            val current = if (exists(file)) read(file, planId) else null
            if (current?.revision != expectedRevision) return@withLock false
            require(next.revision == (expectedRevision ?: 0L) + 1L) {
                "Goal cognitive-cycle binding revision must advance exactly once"
            }
            current?.let { previous ->
                require(previous.sourceGoalPhotonId == next.sourceGoalPhotonId)
                require(previous.sourceGoalPhotonRevision == next.sourceGoalPhotonRevision)
                require(previous.cycleBinding == next.cycleBinding) {
                    "Goal cognitive-cycle lineage cannot change after convergence"
                }
                require(next.state.ordinal >= previous.state.ordinal) {
                    "Goal cognitive-cycle binding state cannot move backwards"
                }
            }
            write(file, next)
            require(read(file, planId) == next) {
                "Goal cognitive-cycle binding read-after-write verification failed"
            }
            true
        }
    }

    private fun fileFor(planId: GoalPlanId): File {
        val key = StableFieldIds.fingerprint(
            "goal-cognitive-cycle-binding-path/v1",
            planId.value,
        )
        return directory.resolve("$key.gcycle")
    }

    private fun read(
        file: File,
        expectedPlanId: GoalPlanId,
    ): GoalCognitiveCycleBindingRecord {
        val plaintext = EncryptedLedgerVaultSupport.decrypt(
            container = EncryptedLedgerVaultSupport.readAtomic(file, MAX_PLAINTEXT_BYTES),
            key = key,
            maxPlaintextBytes = MAX_PLAINTEXT_BYTES,
            associatedData = associatedData(file),
        )
        val record = Codec.decode(plaintext)
        require(record.planId == expectedPlanId) {
            "Goal cognitive-cycle binding stored under wrong plan path"
        }
        return record
    }

    private fun write(
        file: File,
        record: GoalCognitiveCycleBindingRecord,
    ) {
        val encrypted = EncryptedLedgerVaultSupport.encrypt(
            plaintext = Codec.encode(record),
            key = key,
            maxPlaintextBytes = MAX_PLAINTEXT_BYTES,
            associatedData = associatedData(file),
        )
        EncryptedLedgerVaultSupport.atomicWrite(file, encrypted)
    }

    private fun associatedData(file: File): ByteArray =
        "$ROOT_DIRECTORY/${file.name}".encodeToByteArray()

    private fun ensureDirectory() {
        check(directory.isDirectory || directory.mkdirs()) {
            "Goal cognitive-cycle binding vault unavailable"
        }
    }

    private fun exists(file: File): Boolean =
        file.exists() || File("${file.path}.bak").exists()

    private object Codec {
        private const val VERSION = 1

        fun encode(record: GoalCognitiveCycleBindingRecord): ByteArray =
            ByteArrayOutputStream().let { output ->
                DataOutputStream(output).use { data ->
                    data.writeInt(VERSION)
                    data.writeUTF(record.planId.value)
                    data.writeLong(record.revision)
                    data.writeUTF(record.sourceGoalPhotonId.value)
                    data.writeLong(record.sourceGoalPhotonRevision)
                    data.writeUTF(record.cycleBinding.cycleId.value)
                    data.writeUTF(record.cycleBinding.sourceWorldSnapshotId)
                    data.writeUTF(record.cycleBinding.equationVersion)
                    data.writeUTF(record.state.name)
                    data.writeBoolean(record.outcomePhotonId != null)
                    record.outcomePhotonId?.let { data.writeUTF(it.value) }
                    data.writeBoolean(record.outcomePhotonRevision != null)
                    record.outcomePhotonRevision?.let(data::writeLong)
                    data.writeBoolean(record.outcomeWorldSnapshotId != null)
                    record.outcomeWorldSnapshotId?.let(data::writeUTF)
                    data.writeBoolean(record.learningWatermarkRevision != null)
                    record.learningWatermarkRevision?.let(data::writeLong)
                    data.writeUTF(record.fingerprint)
                }
                output.toByteArray().also {
                    require(it.isNotEmpty() && it.size <= MAX_PLAINTEXT_BYTES)
                }
            }

        fun decode(bytes: ByteArray): GoalCognitiveCycleBindingRecord {
            require(bytes.isNotEmpty() && bytes.size <= MAX_PLAINTEXT_BYTES)
            val input = DataInputStream(ByteArrayInputStream(bytes))
            require(input.readInt() == VERSION) {
                "Unsupported goal cognitive-cycle binding codec version"
            }
            val planId = GoalPlanId(input.readUTF())
            val revision = input.readLong()
            val sourceGoalPhotonId = PhotonId(input.readUTF())
            val sourceGoalPhotonRevision = input.readLong()
            val cycleBinding = GoalConvergenceCycleBinding(
                cycleId = CognitiveCycleId(input.readUTF()),
                sourceWorldSnapshotId = input.readUTF(),
                equationVersion = input.readUTF(),
            )
            val state = GoalCognitiveCycleBindingState.valueOf(input.readUTF())
            val outcomePhotonId = if (input.readBoolean()) PhotonId(input.readUTF()) else null
            val outcomePhotonRevision = if (input.readBoolean()) input.readLong() else null
            val outcomeWorldSnapshotId = if (input.readBoolean()) input.readUTF() else null
            val learningWatermarkRevision = if (input.readBoolean()) input.readLong() else null
            val fingerprint = input.readUTF()
            require(input.available() == 0) {
                "Trailing goal cognitive-cycle binding payload"
            }
            return GoalCognitiveCycleBindingRecord.restore(
                planId = planId,
                revision = revision,
                sourceGoalPhotonId = sourceGoalPhotonId,
                sourceGoalPhotonRevision = sourceGoalPhotonRevision,
                cycleBinding = cycleBinding,
                state = state,
                outcomePhotonId = outcomePhotonId,
                outcomePhotonRevision = outcomePhotonRevision,
                outcomeWorldSnapshotId = outcomeWorldSnapshotId,
                learningWatermarkRevision = learningWatermarkRevision,
                fingerprint = fingerprint,
            )
        }
    }

    private companion object {
        const val ROOT_DIRECTORY = "goal-cognitive-cycle-bindings"
        const val KEY_ALIAS = "lifeos.goal.cognitive.cycle.binding.v1"
        const val MAX_PLAINTEXT_BYTES = 64 * 1024
        val processMutex = Mutex()
    }
}
