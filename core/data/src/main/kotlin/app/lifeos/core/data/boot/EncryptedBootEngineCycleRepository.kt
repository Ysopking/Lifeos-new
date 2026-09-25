package app.lifeos.core.data.boot

import android.content.Context
import app.lifeos.core.data.security.EncryptedLedgerVaultSupport
import app.lifeos.core.runtime.boot.BootEngineCycle
import app.lifeos.core.runtime.boot.BootEngineCycleLoadReport
import app.lifeos.core.runtime.boot.BootEngineCycleRepository
import app.lifeos.core.runtime.boot.BootEngineCycleStoreHealth
import app.lifeos.core.runtime.boot.BootEngineCycleState
import app.lifeos.core.runtime.world.CognitiveCycleId
import app.lifeos.core.runtime.world.PersonalContextBootBinding
import app.lifeos.core.runtime.world.WorldFormulaCycleContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
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
                require(report.health != BootEngineCycleStoreHealth.UNRECOVERABLE) {
                    "BootEngine cycle store is unrecoverable: ${report.message}"
                }
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
                require(report.health != BootEngineCycleStoreHealth.UNRECOVERABLE) {
                    "BootEngine cycle store is unrecoverable: ${report.message}"
                }
                report.activeCycle
            }
        }

    override suspend fun loadLatestCommitted(): BootEngineCycle? =
        withContext(Dispatchers.IO) {
            processMutex.withLock {
                ensureDirectories()
                val report = loadReportLocked()
                require(report.health != BootEngineCycleStoreHealth.UNRECOVERABLE) {
                    "BootEngine cycle store is unrecoverable: ${report.message}"
                }
                report.latestCommitted
            }
        }

    override suspend fun compareAndSet(
        expectedFingerprint: String,
        next: BootEngineCycle,
    ): Boolean = withContext(Dispatchers.IO) {
        processMutex.withLock {
            ensureDirectories()
            val report = loadReportLocked()
            require(report.health != BootEngineCycleStoreHealth.UNRECOVERABLE) {
                "BootEngine cycle store is unrecoverable: ${report.message}"
            }
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
                    val existingCommitted = report.latestCommitted
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
                        corrupted = true,
                        message = error.message ?: "boot-engine-cycle-unrecoverable",
                        health = BootEngineCycleStoreHealth.UNRECOVERABLE,
                    )
                }
            }
        }

    private fun loadReportLocked(): BootEngineCycleLoadReport {
        val repairActions = mutableListOf<String>()
        val diagnostics = mutableListOf<String>()

        repairPointerIfNeededLocked(
            file = activePointer,
            requireCommitted = false,
            repairActions = repairActions,
            diagnostics = diagnostics,
        )
        repairPointerIfNeededLocked(
            file = committedPointer,
            requireCommitted = true,
            repairActions = repairActions,
            diagnostics = diagnostics,
        )

        reconcilePointersLocked()

        val active = readPointerIfPresent(activePointer)
            ?.let { loadPointerTarget(it, requireCommitted = false) }
            ?.also {
                require(!it.terminal) {
                    "Active BootEngine pointer resolved to terminal cycle after reconciliation"
                }
            }
        val committed = readPointerIfPresent(committedPointer)
            ?.let { loadPointerTarget(it, requireCommitted = true) }

        val health = when {
            diagnostics.isNotEmpty() -> BootEngineCycleStoreHealth.DEGRADED
            repairActions.isNotEmpty() -> BootEngineCycleStoreHealth.REPAIRED
            else -> BootEngineCycleStoreHealth.HEALTHY
        }
        val message = (diagnostics + repairActions)
            .takeIf { it.isNotEmpty() }
            ?.joinToString(";")

        return BootEngineCycleLoadReport(
            activeCycle = active,
            latestCommitted = committed,
            corrupted = false,
            message = message,
            health = health,
            repairActions = repairActions.toList(),
        )
    }

    private fun repairPointerIfNeededLocked(
        file: File,
        requireCommitted: Boolean,
        repairActions: MutableList<String>,
        diagnostics: MutableList<String>,
    ) {
        if (!exists(file)) return

        try {
            val pointer = requireNotNull(readPointerIfPresent(file))
            loadPointerTarget(pointer, requireCommitted)
            return
        } catch (error: Exception) {
            val pointerName = if (file == activePointer) "active" else "committed"
            quarantinePointerLocked(file)
            repairActions += "${pointerName}-pointer-quarantined"

            val scan = scanCycleRecordsLocked()
            diagnostics += scan.failures.map { "${pointerName}-scan:$it" }
            val candidate = if (requireCommitted) {
                selectCommittedCandidate(scan.cycles, diagnostics)
            } else {
                selectActiveCandidate(scan.cycles, diagnostics)
            }

            if (candidate != null) {
                writePointer(file, CyclePointer(candidate.cycleId, candidate.fingerprint))
                repairActions += "${pointerName}-pointer-reconstructed"
            } else {
                diagnostics +=
                    "${pointerName}-pointer-cleared:${error.message ?: error::class.simpleName}"
            }
        }
    }

    private fun selectActiveCandidate(
        cycles: List<BootEngineCycle>,
        diagnostics: MutableList<String>,
    ): BootEngineCycle? {
        val candidates = cycles.filterNot { it.terminal }
        return when (candidates.size) {
            0 -> null
            1 -> candidates.single()
            else -> {
                diagnostics += "active-cycle-ambiguous:${candidates.size}"
                null
            }
        }
    }

    private fun selectCommittedCandidate(
        cycles: List<BootEngineCycle>,
        diagnostics: MutableList<String>,
    ): BootEngineCycle? {
        val committed = cycles.filter { it.state == BootEngineCycleState.COMMITTED }
        if (committed.isEmpty()) return null
        val highestRevision = committed.maxOf { requireNotNull(it.productiveHeadRevision) }
        val newest = committed.filter { it.productiveHeadRevision == highestRevision }
        return when (newest.size) {
            1 -> newest.single()
            else -> {
                diagnostics += "committed-cycle-ambiguous:${newest.size}:revision-$highestRevision"
                null
            }
        }
    }

    private fun scanCycleRecordsLocked(): CycleRecordScan {
        val failures = mutableListOf<String>()
        val cycles = cycleRecordFilesLocked().mapNotNull { file ->
            try {
                readCycleFromRecordFile(file)
            } catch (error: Exception) {
                failures += "${file.name}:${error.message ?: error::class.simpleName}"
                null
            }
        }
        return CycleRecordScan(
            cycles = cycles.sortedBy { it.cycleId.value },
            failures = failures.sorted(),
        )
    }

    private fun cycleRecordFilesLocked(): List<File> =
        records.listFiles().orEmpty()
            .asSequence()
            .filter { it.isFile }
            .mapNotNull { file ->
                when {
                    file.name.endsWith(RECORD_SUFFIX) -> file
                    file.name.endsWith(RECORD_SUFFIX + BACKUP_SUFFIX) ->
                        File(file.path.removeSuffix(BACKUP_SUFFIX))
                    else -> null
                }
            }
            .distinctBy { it.path }
            .sortedBy { it.name }
            .toList()

    private fun quarantinePointerLocked(file: File) {
        listOf(file, File(file.path + BACKUP_SUFFIX))
            .filter { it.exists() }
            .forEach { source ->
                var index = 0
                var target = quarantine.resolve("${source.name}.corrupt")
                while (target.exists()) {
                    index += 1
                    target = quarantine.resolve("${source.name}.corrupt.$index")
                }
                check(source.renameTo(target)) {
                    "Could not quarantine BootEngine pointer ${source.name}"
                }
            }
    }

    private fun reconcilePointersLocked() {
        val active = readPointerIfPresent(activePointer) ?: return
        val cycle = loadPointerTarget(active, requireCommitted = false)
        if (!cycle.terminal) return
        deleteAtomic(activePointer)
        if (cycle.state == BootEngineCycleState.COMMITTED) {
            writePointer(committedPointer, CyclePointer(cycle.cycleId, cycle.fingerprint))
        }
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
        val cycle = readCycleFromRecordFile(file)
        require(cycle.cycleId == expectedId) {
            "BootEngine cycle payload id does not match physical record path"
        }
        return cycle
    }

    private fun readCycleFromRecordFile(file: File): BootEngineCycle {
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

    private data class CycleRecordScan(
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
        const val KEY_ALIAS = "lifeos.boot.engine.cycle.v1"
        const val MAX_CYCLE_BYTES = 64 * 1024
        const val MAX_POINTER_BYTES = 8 * 1024
        val processMutex = Mutex()
    }
}
