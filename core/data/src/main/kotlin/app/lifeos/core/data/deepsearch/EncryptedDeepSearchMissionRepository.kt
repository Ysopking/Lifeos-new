package app.lifeos.core.data.deepsearch

import android.content.Context
import app.lifeos.core.data.security.EncryptedLedgerVaultSupport
import app.lifeos.core.runtime.deepsearch.DeepSearchMissionEvent
import app.lifeos.core.runtime.deepsearch.DeepSearchMissionEventLogCodec
import app.lifeos.core.runtime.deepsearch.DeepSearchMissionId
import app.lifeos.core.runtime.deepsearch.DeepSearchMissionRepository
import app.lifeos.core.runtime.deepsearch.DeepSearchMissionRepositoryLoadReport
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

/**
 * Encrypted mission-segmented DeepSearch ledger.
 *
 * Global event revisions remain strictly monotonic, while each mission owns its own encrypted event
 * unit. append() reads only the small manifest and the target mission. A manifest-side pending
 * transaction makes two-file commits crash-recoverable without scanning unrelated missions.
 */
class EncryptedDeepSearchMissionRepository(context: Context) : DeepSearchMissionRepository {
    private val directory = context.filesDir.resolve(ROOT_DIRECTORY)
    private val missionsDirectory = directory.resolve(MISSIONS_DIRECTORY)
    private val manifestFile = directory.resolve(MANIFEST_FILE_NAME)
    private val legacyFile = directory.resolve(LEGACY_FILE_NAME)
    private val key: SecretKey by lazy { EncryptedLedgerVaultSupport.loadOrCreateKey(KEY_ALIAS) }

    override suspend fun loadReport(): DeepSearchMissionRepositoryLoadReport = withContext(Dispatchers.IO) {
        processMutex.withLock {
            try {
                var manifest = initializeLocked()
                manifest = recoverPendingLocked(manifest)
                val events = manifest.missionIds
                    .sorted()
                    .flatMap { readMissionEventsLocked(DeepSearchMissionId(it)) }
                    .sortedBy { it.revision }
                require(events.map { it.revision } == (1L..manifest.currentRevision).toList()) {
                    "DeepSearch segmented mission revisions are not globally contiguous"
                }
                DeepSearchMissionRepositoryLoadReport(events)
            } catch (error: Exception) {
                DeepSearchMissionRepositoryLoadReport(
                    events = emptyList(),
                    unreadableEntries = listOf(
                        error.message?.takeIf { it.isNotBlank() } ?: MANIFEST_FILE_NAME
                    ),
                )
            }
        }
    }

    override suspend fun append(
        expectedRevision: Long,
        event: DeepSearchMissionEvent,
    ): Boolean = withContext(Dispatchers.IO) {
        processMutex.withLock {
            require(expectedRevision >= 0L)
            var manifest = initializeLocked()
            manifest = recoverPendingLocked(manifest)
            if (manifest.currentRevision != expectedRevision) return@withLock false
            require(event.revision == expectedRevision + 1L) {
                "DeepSearch mission append revision mismatch"
            }

            val pending = PendingMissionCommit(
                revision = event.revision,
                missionId = event.missionId.value,
                eventFingerprint = eventFingerprint(event),
            )
            writeManifestLocked(manifest.copy(pending = pending))

            val missionEvents = readMissionEventsLocked(event.missionId)
            require(missionEvents.none { it.revision == event.revision }) {
                "DeepSearch mission revision already exists"
            }
            require(missionEvents.lastOrNull()?.revision?.let { it < event.revision } ?: true) {
                "DeepSearch mission segment revision order regressed"
            }
            writeMissionEventsLocked(event.missionId, missionEvents + event)

            manifest = manifest.copy(
                currentRevision = event.revision,
                missionIds = manifest.missionIds + event.missionId.value,
                pending = null,
            )
            writeManifestLocked(manifest)
            true
        }
    }

    private fun initializeLocked(): MissionManifest {
        ensureDirectories()
        if (exists(manifestFile)) return readManifestLocked()

        val migrated = if (exists(legacyFile)) {
            val events = readLegacyEventsLocked()
            events.groupBy { it.missionId }.forEach { (missionId, missionEvents) ->
                val target = missionFile(missionId)
                if (!exists(target)) {
                    writeMissionEventsLocked(missionId, missionEvents.sortedBy { it.revision })
                } else {
                    require(readMissionEventsLocked(missionId) == missionEvents.sortedBy { it.revision }) {
                        "Legacy/segmented DeepSearch mission mismatch for ${missionId.value}"
                    }
                }
            }
            MissionManifest(
                currentRevision = events.lastOrNull()?.revision ?: 0L,
                missionIds = events.mapTo(sortedSetOf()) { it.missionId.value },
            )
        } else {
            MissionManifest(currentRevision = 0L, missionIds = emptySet())
        }
        writeManifestLocked(migrated)
        return migrated
    }

    /**
     * Recovery inspects only the mission named by the pending transaction.
     * - segment missing pending revision: commit never reached the effect file, roll manifest back.
     * - exact revision/fingerprint present: finalize the global manifest revision.
     */
    private fun recoverPendingLocked(manifest: MissionManifest): MissionManifest {
        val pending = manifest.pending ?: return manifest
        require(pending.revision == manifest.currentRevision + 1L) {
            "DeepSearch pending revision is not the next global revision"
        }
        val missionId = DeepSearchMissionId(pending.missionId)
        val event = readMissionEventsLocked(missionId)
            .firstOrNull { it.revision == pending.revision }

        val recovered = if (event == null) {
            manifest.copy(pending = null)
        } else {
            require(eventFingerprint(event) == pending.eventFingerprint) {
                "DeepSearch pending commit fingerprint mismatch"
            }
            manifest.copy(
                currentRevision = pending.revision,
                missionIds = manifest.missionIds + pending.missionId,
                pending = null,
            )
        }
        writeManifestLocked(recovered)
        return recovered
    }

    private fun readMissionEventsLocked(missionId: DeepSearchMissionId): List<DeepSearchMissionEvent> {
        val file = missionFile(missionId)
        if (!exists(file)) return emptyList()
        val container = EncryptedLedgerVaultSupport.readAtomic(
            target = file,
            maxPlaintextBytes = DeepSearchMissionEventLogCodec.MAX_PAYLOAD_BYTES,
        )
        val plaintext = EncryptedLedgerVaultSupport.decrypt(
            container = container,
            key = key,
            maxPlaintextBytes = DeepSearchMissionEventLogCodec.MAX_PAYLOAD_BYTES,
        )
        return DeepSearchMissionEventLogCodec.decode(plaintext).also { events ->
            require(events.all { it.missionId == missionId }) {
                "DeepSearch mission segment contains another mission"
            }
            require(events.map { it.revision } == events.map { it.revision }.sorted()) {
                "DeepSearch mission segment is not revision ordered"
            }
            require(events.map { it.revision }.distinct().size == events.size) {
                "DeepSearch mission segment contains duplicate global revisions"
            }
        }
    }

    private fun writeMissionEventsLocked(
        missionId: DeepSearchMissionId,
        events: List<DeepSearchMissionEvent>,
    ) {
        require(events.isNotEmpty())
        require(events.all { it.missionId == missionId })
        val plaintext = DeepSearchMissionEventLogCodec.encode(events)
        val container = EncryptedLedgerVaultSupport.encrypt(
            plaintext = plaintext,
            key = key,
            maxPlaintextBytes = DeepSearchMissionEventLogCodec.MAX_PAYLOAD_BYTES,
        )
        EncryptedLedgerVaultSupport.atomicWrite(missionFile(missionId), container)
    }

    private fun readLegacyEventsLocked(): List<DeepSearchMissionEvent> {
        val container = EncryptedLedgerVaultSupport.readAtomic(
            target = legacyFile,
            maxPlaintextBytes = DeepSearchMissionEventLogCodec.MAX_PAYLOAD_BYTES,
        )
        val plaintext = EncryptedLedgerVaultSupport.decrypt(
            container = container,
            key = key,
            maxPlaintextBytes = DeepSearchMissionEventLogCodec.MAX_PAYLOAD_BYTES,
        )
        return DeepSearchMissionEventLogCodec.decode(plaintext).also { events ->
            require(events.map { it.revision } == (1L..events.size.toLong()).toList()) {
                "Legacy DeepSearch mission revisions are not contiguous"
            }
        }
    }

    private fun readManifestLocked(): MissionManifest {
        val plaintext = EncryptedLedgerVaultSupport.decrypt(
            container = EncryptedLedgerVaultSupport.readAtomic(
                manifestFile,
                MAX_MANIFEST_PLAINTEXT_BYTES,
            ),
            key = key,
            maxPlaintextBytes = MAX_MANIFEST_PLAINTEXT_BYTES,
        )
        return DataInputStream(ByteArrayInputStream(plaintext)).use { data ->
            require(data.readInt() == MANIFEST_VERSION) {
                "Unsupported DeepSearch mission manifest version"
            }
            val currentRevision = data.readLong()
            require(currentRevision >= 0L)
            val count = data.readInt()
            require(count in 0..MAX_MISSIONS) { "Invalid DeepSearch mission manifest count" }
            val missionIds = buildSet {
                repeat(count) {
                    val missionId = data.readText()
                    DeepSearchMissionId(missionId)
                    require(add(missionId)) { "Duplicate DeepSearch mission manifest id" }
                }
            }
            val pending = if (data.readBoolean()) {
                PendingMissionCommit(
                    revision = data.readLong(),
                    missionId = data.readText().also(::DeepSearchMissionId),
                    eventFingerprint = data.readText().also {
                        require(it.matches(Regex("[0-9a-f]{64}")))
                    },
                )
            } else {
                null
            }
            require(data.available() == 0) { "Trailing DeepSearch mission manifest bytes" }
            MissionManifest(currentRevision, missionIds, pending)
        }
    }

    private fun writeManifestLocked(manifest: MissionManifest) {
        require(manifest.currentRevision >= 0L)
        require(manifest.missionIds.size <= MAX_MISSIONS)
        val plaintext = ByteArrayOutputStream().let { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(MANIFEST_VERSION)
                data.writeLong(manifest.currentRevision)
                data.writeInt(manifest.missionIds.size)
                manifest.missionIds.sorted().forEach(data::writeText)
                data.writeBoolean(manifest.pending != null)
                manifest.pending?.let { pending ->
                    data.writeLong(pending.revision)
                    data.writeText(pending.missionId)
                    data.writeText(pending.eventFingerprint)
                }
            }
            output.toByteArray()
        }
        val container = EncryptedLedgerVaultSupport.encrypt(
            plaintext = plaintext,
            key = key,
            maxPlaintextBytes = MAX_MANIFEST_PLAINTEXT_BYTES,
        )
        EncryptedLedgerVaultSupport.atomicWrite(manifestFile, container)
    }

    private fun eventFingerprint(event: DeepSearchMissionEvent): String =
        MessageDigest.getInstance("SHA-256")
            .digest(DeepSearchMissionEventLogCodec.encode(listOf(event)))
            .joinToString("") { "%02x".format(it) }

    private fun missionFile(missionId: DeepSearchMissionId): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(missionId.value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return missionsDirectory.resolve("mission-$digest.dsmission")
    }

    private fun ensureDirectories() {
        check(directory.isDirectory || directory.mkdirs()) { "DeepSearch mission vault unavailable" }
        check(missionsDirectory.isDirectory || missionsDirectory.mkdirs()) {
            "DeepSearch mission segment directory unavailable"
        }
    }

    private fun exists(target: File): Boolean =
        target.exists() || File("${target.path}.bak").exists()

    private fun DataOutputStream.writeText(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..MAX_TEXT_BYTES) { "DeepSearch manifest text exceeds limit" }
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readText(): String {
        val length = readInt()
        require(length in 1..MAX_TEXT_BYTES) { "Invalid DeepSearch manifest text length" }
        val bytes = ByteArray(length).also(::readFully)
        return bytes.toString(Charsets.UTF_8)
    }

    private data class MissionManifest(
        val currentRevision: Long,
        val missionIds: Set<String>,
        val pending: PendingMissionCommit? = null,
    )

    private data class PendingMissionCommit(
        val revision: Long,
        val missionId: String,
        val eventFingerprint: String,
    )

    private companion object {
        const val ROOT_DIRECTORY = "deep-search-v2"
        const val MISSIONS_DIRECTORY = "missions"
        const val MANIFEST_FILE_NAME = "mission-index.dsmanifest"
        const val LEGACY_FILE_NAME = "missions.dsmission"
        const val KEY_ALIAS = "lifeos.deep.search.mission.v2"
        const val MANIFEST_VERSION = 1
        const val MAX_MISSIONS = 50_000
        const val MAX_TEXT_BYTES = 4 * 1024
        const val MAX_MANIFEST_PLAINTEXT_BYTES = 4 * 1024 * 1024
        val processMutex = Mutex()
    }
}
