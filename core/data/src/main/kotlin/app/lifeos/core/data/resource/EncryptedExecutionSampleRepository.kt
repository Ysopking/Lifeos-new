package app.lifeos.core.data.resource

import android.content.Context
import app.lifeos.core.data.security.EncryptedLedgerVaultSupport
import app.lifeos.core.runtime.resource.ExecutionMeasurement
import app.lifeos.core.runtime.resource.ExecutionSample
import app.lifeos.core.runtime.resource.ExecutionSampleRepository
import app.lifeos.core.runtime.resource.ExecutionTelemetryClass
import app.lifeos.core.runtime.resource.HardwareExecutionClass
import app.lifeos.core.runtime.resource.HardwareThermalState
import app.lifeos.core.runtime.resource.ResourceBudgetDomain
import app.lifeos.core.runtime.resource.ResourceBudgetUsage
import app.lifeos.core.runtime.resource.SequencedExecutionSample
import app.lifeos.core.runtime.resource.SoftCostEstimate
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.time.Instant
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Append-only encrypted execution evidence. Each ciphertext is validated against both its global
 * sequence path and its sample-fingerprint directory before it can be returned.
 */
class EncryptedExecutionSampleRepository(context: Context) : ExecutionSampleRepository {
    private val directory = context.filesDir.resolve(ROOT_DIRECTORY)
    private val samplesDirectory = directory.resolve("samples")
    private val headFile = directory.resolve("head.esample")
    private val key: SecretKey by lazy { EncryptedLedgerVaultSupport.loadOrCreateKey(KEY_ALIAS) }

    override suspend fun append(sample: ExecutionSample): SequencedExecutionSample =
        withContext(Dispatchers.IO) {
            processMutex.withLock {
                ensureDirectory()
                requireReadableHistory()
                val sequence = readHeadOrRecover() + 1L
                val entry = SequencedExecutionSample(sequence, sample)
                val target = entryFile(entry)
                if (exists(target)) {
                    require(readValidatedEntry(target) == entry) { "Execution sample sequence collision" }
                } else {
                    target.parentFile?.let { check(it.isDirectory || it.mkdirs()) }
                    writeEncrypted(target, ExecutionSampleCodec.encode(entry))
                }
                writeHead(sequence)
                entry
            }
        }

    override suspend fun readAfter(
        sequenceExclusive: Long,
        limit: Int,
    ): List<SequencedExecutionSample> = withContext(Dispatchers.IO) {
        processMutex.withLock {
            require(sequenceExclusive >= 0L)
            require(limit in 1..MAX_READ_LIMIT)
            ensureDirectory()
            requireReadableHistory()
            readHeadOrRecover()
            entryFiles()
                .asSequence()
                .map(::readValidatedEntry)
                .filter { it.sequence > sequenceExclusive }
                .sortedBy { it.sequence }
                .take(limit)
                .toList()
        }
    }

    override suspend fun latestCostEstimate(
        operationKind: String,
        hardwareClass: HardwareExecutionClass,
    ): SoftCostEstimate? = withContext(Dispatchers.IO) {
        processMutex.withLock {
            require(operationKind.isNotBlank())
            ensureDirectory()
            requireReadableHistory()
            val samples = entryFiles()
                .asSequence()
                .map(::readValidatedEntry)
                .filter {
                    it.sample.operationKind == operationKind &&
                        it.sample.executionClass == hardwareClass &&
                        it.sample.telemetryClass == ExecutionTelemetryClass.PRODUCTIVE
                }
                .sortedByDescending { it.sequence }
                .take(COST_WINDOW)
                .map { it.sample }
                .toList()
            if (samples.isEmpty()) return@withLock null
            val domains = samples.map { it.domain }.distinct()
            require(domains.size == 1) {
                "Execution operation kind spans multiple resource domains"
            }
            val usage = ResourceBudgetUsage(
                elapsedMillis = average(samples) { it.measurement.elapsedMillis },
                workUnits = average(samples) { it.measurement.workUnits },
                memoryBytes = average(samples) { it.measurement.peakMemoryBytes },
                ioBytes = average(samples) { it.measurement.ioBytes },
                networkBytes = average(samples) { it.measurement.networkBytes },
                candidates = average(samples) { it.measurement.candidates },
            )
            SoftCostEstimate(
                domain = domains.single(),
                operationKind = operationKind,
                estimated = usage,
                samples = samples.size.toLong(),
                confidence = (samples.size.toDouble() / COST_CONFIDENCE_SAMPLES).coerceIn(0.0, 1.0),
            )
        }
    }

    private fun average(samples: List<ExecutionSample>, value: (ExecutionSample) -> Long): Long =
        samples.map { value(it).toDouble() }.average().toLong().coerceAtLeast(0L)

    private fun requireReadableHistory() {
        entryFiles().forEach(::readValidatedEntry)
    }

    private fun readValidatedEntry(file: File): SequencedExecutionSample {
        val entry = ExecutionSampleCodec.decode(decrypt(file))
        require(entry.sequence == sequenceFrom(file)) {
            "Execution sample sequence does not match segment path"
        }
        val sampleDirectory = requireNotNull(file.parentFile) {
            "Execution sample segment has no sample directory"
        }
        require(sampleDirectory.parentFile == samplesDirectory) {
            "Execution sample segment is outside the sample directory"
        }
        require(sampleDirectory.name == sha256(entry.sample.fingerprint())) {
            "Execution sample fingerprint does not match segment path"
        }
        return entry
    }

    private fun entryFile(entry: SequencedExecutionSample): File =
        samplesDirectory.resolve(sha256(entry.sample.fingerprint()))
            .resolve("$SEGMENT_PREFIX${entry.sequence.toString().padStart(20, '0')}$SEGMENT_SUFFIX")

    private fun entryFiles(): List<File> {
        ensureDirectory()
        return samplesDirectory.walkTopDown()
            .filter { it.isFile && it.name.startsWith(SEGMENT_PREFIX) && it.name.endsWith(SEGMENT_SUFFIX) }
            .sortedBy(::sequenceFrom)
            .toList()
    }

    private fun sequenceFrom(file: File): Long = requireNotNull(
        file.name.removePrefix(SEGMENT_PREFIX).removeSuffix(SEGMENT_SUFFIX).toLongOrNull()
    ) { "Invalid execution sample segment name: ${file.name}" }

    private fun readHeadOrRecover(): Long {
        val files = entryFiles()
        val bySequence = files.groupBy(::sequenceFrom)
        require(bySequence.values.all { it.size == 1 }) { "Duplicate execution sample sequence" }
        val sequences = bySequence.keys.sorted()
        val recovered = sequences.lastOrNull() ?: 0L
        require(sequences == if (recovered == 0L) emptyList() else (1L..recovered).toList()) {
            "Execution sample sequence is not contiguous"
        }
        val stored = if (exists(headFile)) runCatching(::readHead).getOrNull() else null
        require(stored == null || stored <= recovered) { "Execution sample head points past durable tail" }
        if (stored != recovered) writeHead(recovered)
        return recovered
    }

    private fun readHead(): Long {
        val bytes = decrypt(headFile, 64)
        require(bytes.size == Long.SIZE_BYTES)
        return ByteBuffer.wrap(bytes).long.also { require(it >= 0L) }
    }

    private fun writeHead(sequence: Long) {
        writeEncrypted(headFile, ByteBuffer.allocate(Long.SIZE_BYTES).putLong(sequence).array(), 64)
    }

    private fun decrypt(file: File, maxBytes: Int = ExecutionSampleCodec.MAX_PAYLOAD_BYTES): ByteArray =
        EncryptedLedgerVaultSupport.decrypt(
            EncryptedLedgerVaultSupport.readAtomic(file, maxBytes),
            key,
            maxBytes,
        )

    private fun writeEncrypted(
        file: File,
        plaintext: ByteArray,
        maxBytes: Int = ExecutionSampleCodec.MAX_PAYLOAD_BYTES,
    ) {
        EncryptedLedgerVaultSupport.atomicWrite(
            file,
            EncryptedLedgerVaultSupport.encrypt(plaintext, key, maxBytes),
        )
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun ensureDirectory() {
        check(directory.isDirectory || directory.mkdirs()) { "Execution sample vault unavailable" }
        check(samplesDirectory.isDirectory || samplesDirectory.mkdirs()) {
            "Execution sample directory unavailable"
        }
    }

    private fun exists(file: File): Boolean =
        file.exists() || File("${file.path}.bak").exists()

    private companion object {
        const val ROOT_DIRECTORY = "execution-sample-ledger"
        const val SEGMENT_PREFIX = "sequence-"
        const val SEGMENT_SUFFIX = ".esample"
        const val KEY_ALIAS = "lifeos.execution.sample.v1"
        const val MAX_READ_LIMIT = 4096
        const val COST_WINDOW = 128
        const val COST_CONFIDENCE_SAMPLES = 16.0
        val processMutex = Mutex()
    }
}

private object ExecutionSampleCodec {
    const val MAX_PAYLOAD_BYTES = 128 * 1024
    private const val VERSION = 1
    private const val MAX_TEXT_BYTES = 16 * 1024

    fun encode(entry: SequencedExecutionSample): ByteArray = ByteArrayOutputStream().let { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeInt(VERSION)
            out.writeLong(entry.sequence)
            out.text(entry.sample.fingerprint())
            val s = entry.sample
            out.text(s.domain.name)
            val m = s.measurement
            out.text(m.operationId)
            out.instant(m.measuredAt)
            out.writeLong(m.elapsedMillis)
            out.writeLong(m.workUnits)
            out.writeLong(m.peakMemoryBytes)
            out.writeLong(m.ioBytes)
            out.writeLong(m.networkBytes)
            out.writeLong(m.candidates)
            out.text(m.thermalState.name)
            out.optionalDouble(m.batteryFraction)
            out.text(m.outcomeCode)
            out.optionalDouble(m.utility)
            out.text(s.operationKind)
            out.text(s.executionClass.name)
            out.optionalText(s.workGraphId)
            out.optionalText(s.workNodeId)
            out.text(s.hardwareFingerprint)
            out.text(s.executionPlanFingerprint)
            out.text(s.strategyFingerprint)
            out.text(s.learningProfileFingerprint)
            out.writeLong(s.queueWaitMillis)
            out.optionalLong(s.cpuTimeMillis)
            out.writeInt(s.parallelism)
            out.writeInt(s.batchSize)
            out.writeBoolean(s.reused)
            out.text(s.telemetryClass.name)
        }
        bytes.toByteArray().also {
            require(it.isNotEmpty() && it.size <= MAX_PAYLOAD_BYTES)
        }
    }

    fun decode(payload: ByteArray): SequencedExecutionSample {
        require(payload.isNotEmpty() && payload.size <= MAX_PAYLOAD_BYTES)
        return DataInputStream(ByteArrayInputStream(payload)).use { input ->
            require(input.readInt() == VERSION) { "Unsupported execution sample version" }
            val sequence = input.readLong()
            val expectedFingerprint = input.text()
            val domain = enumValueOf<ResourceBudgetDomain>(input.text())
            val measurement = ExecutionMeasurement(
                operationId = input.text(),
                measuredAt = input.instant(),
                elapsedMillis = input.readLong(),
                workUnits = input.readLong(),
                peakMemoryBytes = input.readLong(),
                ioBytes = input.readLong(),
                networkBytes = input.readLong(),
                candidates = input.readLong(),
                thermalState = enumValueOf<HardwareThermalState>(input.text()),
                batteryFraction = input.optionalDouble(),
                outcomeCode = input.text(),
                utility = input.optionalDouble(),
            )
            val sample = ExecutionSample(
                measurement = measurement,
                domain = domain,
                operationKind = input.text(),
                executionClass = enumValueOf<HardwareExecutionClass>(input.text()),
                workGraphId = input.optionalText(),
                workNodeId = input.optionalText(),
                hardwareFingerprint = input.text(),
                executionPlanFingerprint = input.text(),
                strategyFingerprint = input.text(),
                learningProfileFingerprint = input.text(),
                queueWaitMillis = input.readLong(),
                cpuTimeMillis = input.optionalLong(),
                parallelism = input.readInt(),
                batchSize = input.readInt(),
                reused = input.readBoolean(),
                telemetryClass = enumValueOf<ExecutionTelemetryClass>(input.text()),
            )
            require(input.available() == 0) { "Trailing execution sample data" }
            require(sample.fingerprint() == expectedFingerprint) {
                "Execution sample fingerprint/content mismatch"
            }
            SequencedExecutionSample(sequence, sample)
        }
    }

    private fun DataOutputStream.text(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_TEXT_BYTES)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.text(): String {
        val length = readInt()
        require(length in 0..MAX_TEXT_BYTES && length <= available())
        val bytes = ByteArray(length).also(::readFully)
        return Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }

    private fun DataOutputStream.optionalText(value: String?) {
        writeBoolean(value != null)
        if (value != null) text(value)
    }

    private fun DataInputStream.optionalText(): String? = if (readBoolean()) text() else null

    private fun DataOutputStream.optionalDouble(value: Double?) {
        writeBoolean(value != null)
        if (value != null) writeDouble(value)
    }

    private fun DataInputStream.optionalDouble(): Double? = if (readBoolean()) readDouble() else null

    private fun DataOutputStream.optionalLong(value: Long?) {
        writeBoolean(value != null)
        if (value != null) writeLong(value)
    }

    private fun DataInputStream.optionalLong(): Long? = if (readBoolean()) readLong() else null

    private fun DataOutputStream.instant(value: Instant) {
        writeLong(value.epochSecond)
        writeInt(value.nano)
    }

    private fun DataInputStream.instant(): Instant =
        Instant.ofEpochSecond(readLong(), readInt().toLong())
}
