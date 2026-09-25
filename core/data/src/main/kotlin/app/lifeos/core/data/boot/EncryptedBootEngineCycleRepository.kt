package app.lifeos.core.data.boot

import android.content.Context
import app.lifeos.core.data.security.EncryptedLedgerVaultSupport
import app.lifeos.core.runtime.boot.BootEngineCycle
import app.lifeos.core.runtime.boot.BootEngineCycleLoadReport
import app.lifeos.core.runtime.boot.BootEngineCycleRepository
import app.lifeos.core.runtime.boot.BootEngineCycleState
import app.lifeos.core.runtime.boot.BootEngineCycleStoreHealth
import app.lifeos.core.runtime.world.CognitiveCycleId
import app.lifeos.core.runtime.world.PersonalContextBootBinding
import app.lifeos.core.runtime.world.WorldFormulaCycleContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileNotFoundException
import java.io.UTFDataFormatException
import java.security.MessageDigest
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.IllegalBlockSizeException
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class EncryptedBootEngineCycleRepository(
    context: Context,
) : BootEngineCycleRepository {
    private val root = context.filesDir.resolve(ROOT_DIRECTORY)
    private val records = root.resolve(RECORDS_DIRECTORY)
    private val quarantine = root.resolve(QUARANTINE_DIRECTORY)
    private val activePointer = root.resolve(ACTIVE_POINTER)
    private val committedPointer = root.resolve(COMMITTED_POINTER)
    private val key: SecretKey by lazy {
        EncryptedLedgerVaultSupport.loadOrCreateKey(KEY_ALIAS)
    }

    override suspend fun create(cycle: BootEngineCycle): Boolean =
        withContext(Dispatchers.IO) {
            processMutex.withLock {
                ensureDirectories()
                val report = loadReportLocked()
                requireUsable(report)
                if (exists(recordFile(cycle.cycleId))) return@withLock false
                if (report.activeCycle != null) return@withLock false
                writeCycle(cycle)
                writePointer(activePointer, CyclePointer(cycle.cycleId, cycle.fingerprint))
                true
            }
        }

    override suspend fun load(cycleId: CognitiveCycleId): BootEngineCycle? =
        withContext(Dispatchers.IO) {
            processMutex.withLock {
                ensureDirectories()
                val file = recordFile(cycleId)
                if (!exists(file)) return@withLock null
                readCycle(file, cycleId)
            }
        }

    override suspend fun loadActive(): BootEngineCycle? =
        withContext(Dispatchers.IO) {
            processMutex.withLock {
                ensureDirectories()
                val report = loadReportLocked()
                requireUsable(report)
                report.activeCycle
            }
        }

    override suspend fun loadLatestCommitted(): BootEngineCycle? =
        withContext(Dispatchers.IO) {
            processMutex.withLock {
                ensureDirectories()
                val report = loadReportLocked()
                requireUsable(report)
                report.latestCommitted
            }
        }

    override suspend fun compareAndSet(
        expectedFingerprint: String,
        next: BootEngineCycle,
    ): Boolean = withContext(Dispatchers.IO) {
        processMutex.withLock {
            ensureDirectories()
            requireUsable(loadReportLocked())
            require(expectedFingerprint.isNotBlank())
            val file = recordFile(next.cycleId)
            if (!exists(file)) return@withLock false
            val current = readCycle(file, next.cycleId)
            if (current.fingerprint != expectedFingerprint) return@withLock false
            require(current.cycleId == next.cycleId)
            require(current.context == next.context) {
                "BootEngine cycle cannot change frozen context during CAS"
            }
            writeCycle(next)
            val persisted = readCycle(file, next.cycleId)
            require(persisted.fingerprint == next.fingerprint)

            if (next.terminal) {
                deleteAtomic(activePointer)
                if (next.state == BootEngineCycleState.COMMITTED) {
                    val existingCommitted = readPointerIfPresent(committedPointer)
                        ?.let { loadPointerTarget(it, requireCommitted = true) }
                    require(
                        existingCommitted == null ||
                            next.productiveHeadRevision!! >= existingCommitted.productiveHeadRevision!!
                    ) {
                        "Committed BootEngine cycle head cannot move backwards"
                    }
                    writePointer(
                        committedPointer,
                        CyclePointer(next.cycleId, next.fingerprint),
                    )
                }
            } else {
                writePointer(activePointer, CyclePointer(next.cycleId, next.fingerprint))
            }
            true
        }
    }

    override suspend fun loadReport(): BootEngineCycleLoadReport =
        withContext(Dispatchers.IO) {
            processMutex.withLock {
                runCatching {
                    ensureDirectories()
                    loadReportLocked()
                }.getOrElse { error ->
                    BootEngineCycleLoadReport(
                        activeCycle = null,
                        latestCommitted = null,
                        health = BootEngineCycleStoreHealth.UNRECOVERABLE,
                        message = error.message ?: "boot-engine-cycle-store-unrecoverable",
                    )
                }
            }
        }

    private fun loadReportLocked(): BootEngineCycleLoadReport {
        var health = BootEngineCycleStoreHealth.HEALTHY
        val repairActions = mutableListOf<String>()
        val messages = mutableListOf<String>()
        var activeNeedsReconstruction = false
        var committedNeedsReconstruction = false

        fun resolvePointer(
            file: File,
            label: String,
            requireCommitted: Boolean,
        ): BootEngineCycle? {
            if (!exists(file)) return null
            return try {
                val pointer = requireNotNull(readPointerIfPresent(file))
                loadPointerTarget(pointer, requireCommitted)
            } catch (error: Exception) {
                if (!isRepairablePointerFailure(error)) throw error
                quarantinePointerLocked(file)
                repairActions += "$label-pointer-quarantined"
                messages += "$label-pointer:${error.message ?: error::class.simpleName}"
                health = worstHealth(health, BootEngineCycleStoreHealth.REPAIRED)
                if (file == activePointer) {
                    activeNeedsReconstruction = true
                } else {
                    committedNeedsReconstruction = true
                }
                null
            }
        }

        var active = resolvePointer(
            file = activePointer,
            label = "active",
            requireCommitted = false,
        )
        var committed = resolvePointer(
            file = committedPointer,
            label = "committed",
            requireCommitted = true,
        )

        if (active?.terminal == true) {
            deleteAtomic(activePointer)
            repairActions += "terminal-active-pointer-reconciled"
            health = worstHealth(health, BootEngineCycleStoreHealth.REPAIRED)
            if (active.state == BootEngineCycleState.COMMITTED) {
                val activeRevision = requireNotNull(active.productiveHeadRevision)
                val committedRevision = committed?.productiveHeadRevision
                if (committedRevision == null || activeRevision >= committedRevision) {
                    writePointer(
                        committedPointer,
                        CyclePointer(active.cycleId, active.fingerprint),
                    )
                    committed = active
                }
            }
            active = null
        }

        if (activeNeedsReconstruction || committedNeedsReconstruction) {
            val scan = scanValidCyclesLocked()
            if (scan.failures.isNotEmpty()) {
                health = worstHealth(health, BootEngineCycleStoreHealth.DEGRADED)
                messages += "record-scan-unreadable:${scan.failures.joinToString(",")}"
            }

            if (activeNeedsReconstruction) {
                val candidates = scan.cycles.filterNot { it.terminal }
                active = when (candidates.size) {
                    0 -> {
                        repairActions += "active-pointer-cleared-no-active-cycle"
                        null
                    }
                    1 -> candidates.single().also { cycle ->
                        writePointer(
                            activePointer,
                            CyclePointer(cycle.cycleId, cycle.fingerprint),
                        )
                        repairActions += "active-pointer-reconstructed"
                    }
                    else -> {
                        health = worstHealth(health, BootEngineCycleStoreHealth.DEGRADED)
                        messages += "active-pointer-ambiguous:${candidates.size}"
                        repairActions += "active-pointer-left-empty-ambiguous"
                        null
                    }
                }
            }

            if (committedNeedsReconstruction) {
                val committedCycles = scan.cycles.filter {
                    it.state == BootEngineCycleState.COMMITTED &&
                        it.productiveHeadRevision != null
                }
                val highestRevision = committedCycles.maxOfOrNull {
                    requireNotNull(it.productiveHeadRevision)
                }
                val candidates = if (highestRevision == null) {
                    emptyList()
                } else {
                    committedCycles.filter { it.productiveHeadRevision == highestRevision }
                }
                committed = when (candidates.size) {
                    0 -> {
                        repairActions += "committed-pointer-cleared-no-committed-cycle"
                        null
                    }
                    1 -> candidates.single().also { cycle ->
                        writePointer(
                            committedPointer,
                            CyclePointer(cycle.cycleId, cycle.fingerprint),
                        )
                        repairActions += "committed-pointer-reconstructed"
                    }
                    else -> {
                        health = worstHealth(health, BootEngineCycleStoreHealth.DEGRADED)
                        messages += "committed-pointer-ambiguous:${candidates.size}"
                        repairActions += "committed-pointer-left-empty-ambiguous"
                        null
                    }
                }
            }
        }

        return BootEngineCycleLoadReport(
            activeCycle = active,
            latestCommitted = committed,
            health = health,
            repairActions = repairActions.toList(),
            message = messages.takeIf { it.isNotEmpty() }?.joinToString(";"),
        )
    }

    private fun loadPointerTarget(
        pointer: CyclePointer,
        requireCommitted: Boolean,
    ): BootEngineCycle {
        val cycle = readCycle(recordFile(pointer.cycleId), pointer.cycleId)
        require(cycle.fingerprint == pointer.fingerprint) {
            "BootEngine pointer fingerprint mismatch"
        }
        if (requireCommitted) {
            require(cycle.state == BootEngineCycleState.COMMITTED) {
                "Committed pointer resolved to non-committed cycle"
            }
        }
        return cycle
    }

    private fun writeCycle(cycle: BootEngineCycle) {
        val file = recordFile(cycle.cycleId)
        val plaintext = BootEngineCycleCodec.encode(cycle)
        val encrypted = EncryptedLedgerVaultSupport.encrypt(
            plaintext = plaintext,
            key = key,
            maxPlaintextBytes = MAX_CYCLE_BYTES,
            associatedData = recordAssociatedData(file),
        )
        EncryptedLedgerVaultSupport.atomicWrite(file, encrypted)
    }

    private fun readCycle(
        file: File,
        expectedId: CognitiveCycleId,
    ): BootEngineCycle {
        require(file == recordFile(expectedId)) {
            "BootEngine cycle physical path does not match expected id"
        }
        val cycle = readCycleAtFile(file)
        require(cycle.cycleId == expectedId) {
            "BootEngine cycle payload id does not match physical record path"
        }
        return cycle
    }

    private fun readCycleAtFile(file: File): BootEngineCycle {
        require(file.parentFile == records) {
            "BootEngine cycle record must live in the records directory"
        }
        val plaintext = EncryptedLedgerVaultSupport.decrypt(
            container = EncryptedLedgerVaultSupport.readAtomic(file, MAX_CYCLE_BYTES),
            key = key,
            maxPlaintextBytes = MAX_CYCLE_BYTES,
            associatedData = recordAssociatedData(file),
        )
        val cycle = BootEngineCycleCodec.decode(plaintext)
        require(file == recordFile(cycle.cycleId)) {
            "BootEngine cycle payload/path binding mismatch"
        }
        return cycle
    }

    private fun writePointer(
        file: File,
        pointer: CyclePointer,
    ) {
        val plaintext = CyclePointerCodec.encode(pointer)
        val encrypted = EncryptedLedgerVaultSupport.encrypt(
            plaintext = plaintext,
            key = key,
            maxPlaintextBytes = MAX_POINTER_BYTES,
            associatedData = pointerAssociatedData(file),
        )
        EncryptedLedgerVaultSupport.atomicWrite(file, encrypted)
    }

    private fun readPointerIfPresent(file: File): CyclePointer? {
        if (!exists(file)) return null
        val plaintext = EncryptedLedgerVaultSupport.decrypt(
            container = EncryptedLedgerVaultSupport.readAtomic(file, MAX_POINTER_BYTES),
            key = key,
            maxPlaintextBytes = MAX_POINTER_BYTES,
            associatedData = pointerAssociatedData(file),
        )
        return CyclePointerCodec.decode(plaintext)
    }

    private fun recordFile(cycleId: CognitiveCycleId): File =
        records.resolve(sha256(cycleId.value) + RECORD_SUFFIX)

    private fun recordAssociatedData(file: File): ByteArray {
        require(file.parentFile == records)
        return "$ROOT_DIRECTORY/$RECORDS_DIRECTORY/${file.name}".encodeToByteArray()
    }

    private fun pointerAssociatedData(file: File): ByteArray {
        require(file == activePointer || file == committedPointer)
        return "$ROOT_DIRECTORY/${file.name}".encodeToByteArray()
    }

    private fun ensureDirectories() {
        check(root.isDirectory || root.mkdirs()) { "BootEngine cycle vault unavailable" }
        check(records.isDirectory || records.mkdirs()) {
            "BootEngine cycle record directory unavailable"
        }
        check(quarantine.isDirectory || quarantine.mkdirs()) {
            "BootEngine cycle quarantine directory unavailable"
        }
    }

    private fun requireUsable(report: BootEngineCycleLoadReport) {
        check(report.health != BootEngineCycleStoreHealth.UNRECOVERABLE) {
            "BootEngine cycle store unavailable: ${report.message}"
        }
    }

    private fun scanValidCyclesLocked(): CycleScan {
        val failures = mutableListOf<String>()
        val cycles = mutableListOf<BootEngineCycle>()
        recordBaseFilesLocked().forEach { file ->
            runCatching { readCycleAtFile(file) }
                .onSuccess(cycles::add)
                .onFailure { failures += file.name }
        }
        return CycleScan(
            cycles = cycles.distinctBy { it.cycleId }.sortedBy { it.cycleId.value },
            failures = failures.distinct().sorted(),
        )
    }

    private fun recordBaseFilesLocked(): List<File> =
        records.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isFile }
            .mapNotNull { file ->
                when {
                    file.name.endsWith(RECORD_SUFFIX) -> file
                    file.name.endsWith(RECORD_SUFFIX + BACKUP_SUFFIX) ->
                        records.resolve(file.name.removeSuffix(BACKUP_SUFFIX))
                    else -> null
                }
            }
            .distinctBy { it.name }
            .sortedBy { it.name }
            .toList()

    private fun quarantinePointerLocked(file: File) {
        require(file == activePointer || file == committedPointer)
        listOf(file, File(file.path + BACKUP_SUFFIX))
            .filter(File::exists)
            .forEach { source ->
                val discriminator = sha256(
                    source.name + "|" + source.length() + "|" + source.lastModified()
                )
                val target = quarantine.resolve(
                    source.name + "." + discriminator + CORRUPT_SUFFIX
                )
                if (target.exists()) {
                    check(source.delete()) {
                        "Unable to remove duplicate quarantined BootEngine pointer"
                    }
                } else {
                    check(source.renameTo(target)) {
                        "Unable to quarantine BootEngine pointer"
                    }
                }
            }
    }

    private fun isRepairablePointerFailure(error: Exception): Boolean =
        error is IllegalArgumentException ||
            error is EOFException ||
            error is UTFDataFormatException ||
            error is FileNotFoundException ||
            error is AEADBadTagException ||
            error is BadPaddingException ||
            error is IllegalBlockSizeException

    private fun worstHealth(
        first: BootEngineCycleStoreHealth,
        second: BootEngineCycleStoreHealth,
    ): BootEngineCycleStoreHealth =
        if (first.ordinal >= second.ordinal) first else second

    private fun deleteAtomic(file: File) {
        if (!exists(file)) return
        android.util.AtomicFile(file).delete()
    }

    private fun exists(file: File): Boolean =
        file.exists() || File("${file.path}.bak").exists()

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.encodeToByteArray())
            .joinToString("") { "%02x".format(it) }

    private data class CycleScan(
        val cycles: List<BootEngineCycle>,
        val failures: List<String>,
    )

    private data class CyclePointer(
        val cycleId: CognitiveCycleId,
        val fingerprint: String,
    ) {
        init {
            require(fingerprint.isNotBlank())
        }
    }

    private object CyclePointerCodec {
        private const val VERSION = 1

        fun encode(pointer: CyclePointer): ByteArray =
            ByteArrayOutputStream().let { output ->
                DataOutputStream(output).use { data ->
                    data.writeInt(VERSION)
                    data.writeUTF(pointer.cycleId.value)
                    data.writeUTF(pointer.fingerprint)
                }
                output.toByteArray()
            }

        fun decode(bytes: ByteArray): CyclePointer {
            val input = DataInputStream(ByteArrayInputStream(bytes))
            require(input.readInt() == VERSION)
            val pointer = CyclePointer(
                cycleId = CognitiveCycleId(input.readUTF()),
                fingerprint = input.readUTF(),
            )
            require(input.available() == 0)
            return pointer
        }
    }

    private object BootEngineCycleCodec {
        private const val VERSION = 2
        private const val LEGACY_VERSION = 1

        fun encode(cycle: BootEngineCycle): ByteArray =
            ByteArrayOutputStream().let { output ->
                DataOutputStream(output).use { data ->
                    data.writeInt(VERSION)
                    data.writeUTF(cycle.cycleId.value)
                    data.writeBoolean(cycle.context.previousWorldSnapshotId != null)
                    cycle.context.previousWorldSnapshotId?.let(data::writeUTF)
                    data.writeUTF(cycle.context.representationSnapshotId)
                    data.writeUTF(cycle.context.strategySnapshotId)
                    data.writeUTF(cycle.context.equationVersion)
                    data.writeUTF(cycle.context.resourceSnapshotId)
                    data.writeBoolean(cycle.context.perceptionBinding != null)
                    cycle.context.perceptionBinding?.let { binding ->
                        data.writeUTF(binding.personalContextSnapshotId)
                        data.writeUTF(binding.sensorRegistryFingerprint)
                        data.writeLong(binding.ownerObservationPolicyRevision)
                    }
                    data.writeUTF(cycle.frozenInputsFingerprint)
                    data.writeUTF(cycle.state.name)
                    data.writeBoolean(cycle.productiveRequestId != null)
                    cycle.productiveRequestId?.let(data::writeUTF)
                    data.writeBoolean(cycle.worldSnapshotId != null)
                    cycle.worldSnapshotId?.let(data::writeUTF)
                    data.writeBoolean(cycle.productiveHeadRevision != null)
                    cycle.productiveHeadRevision?.let(data::writeLong)
                    data.writeBoolean(cycle.failure != null)
                    cycle.failure?.let(data::writeUTF)
                    data.writeUTF(cycle.fingerprint)
                }
                output.toByteArray().also {
                    require(it.isNotEmpty() && it.size <= MAX_CYCLE_BYTES)
                }
            }

        fun decode(bytes: ByteArray): BootEngineCycle {
            require(bytes.isNotEmpty() && bytes.size <= MAX_CYCLE_BYTES)
            val input = DataInputStream(ByteArrayInputStream(bytes))
            val version = input.readInt()
            require(version == VERSION || version == LEGACY_VERSION) {
                "Unsupported BootEngine cycle codec version"
            }
            val cycleId = CognitiveCycleId(input.readUTF())
            val previous = if (input.readBoolean()) input.readUTF() else null
            val representationSnapshotId = input.readUTF()
            val strategySnapshotId = input.readUTF()
            val equationVersion = input.readUTF()
            val resourceSnapshotId = input.readUTF()
            val perceptionBinding = if (version >= VERSION && input.readBoolean()) {
                PersonalContextBootBinding(
                    personalContextSnapshotId = input.readUTF(),
                    sensorRegistryFingerprint = input.readUTF(),
                    ownerObservationPolicyRevision = input.readLong(),
                )
            } else {
                null
            }
            val context = WorldFormulaCycleContext(
                cycleId = cycleId,
                previousWorldSnapshotId = previous,
                representationSnapshotId = representationSnapshotId,
                strategySnapshotId = strategySnapshotId,
                equationVersion = equationVersion,
                resourceSnapshotId = resourceSnapshotId,
                perceptionBinding = perceptionBinding,
            )
            val frozenInputsFingerprint = input.readUTF()
            val state = BootEngineCycleState.valueOf(input.readUTF())
            val productiveRequestId = if (input.readBoolean()) input.readUTF() else null
            val worldSnapshotId = if (input.readBoolean()) input.readUTF() else null
            val productiveHeadRevision = if (input.readBoolean()) input.readLong() else null
            val failure = if (input.readBoolean()) input.readUTF() else null
            val fingerprint = input.readUTF()
            require(input.available() == 0) { "Trailing BootEngine cycle payload" }
            return BootEngineCycle.restore(
                cycleId = cycleId,
                context = context,
                frozenInputsFingerprint = frozenInputsFingerprint,
                state = state,
                productiveRequestId = productiveRequestId,
                worldSnapshotId = worldSnapshotId,
                productiveHeadRevision = productiveHeadRevision,
                failure = failure,
                fingerprint = fingerprint,
            )
        }
    }

    private companion object {
        const val ROOT_DIRECTORY = "boot-engine-cycle-vault"
        const val RECORDS_DIRECTORY = "records"
        const val QUARANTINE_DIRECTORY = "quarantine"
        const val ACTIVE_POINTER = "active.bcycle"
        const val COMMITTED_POINTER = "latest-committed.bcycle"
        const val RECORD_SUFFIX = ".bcycle"
        const val BACKUP_SUFFIX = ".bak"
        const val CORRUPT_SUFFIX = ".corrupt"
        const val KEY_ALIAS = "lifeos.boot.engine.cycle.v1"
        const val MAX_CYCLE_BYTES = 64 * 1024
        const val MAX_POINTER_BYTES = 8 * 1024
        val processMutex = Mutex()
    }
}
