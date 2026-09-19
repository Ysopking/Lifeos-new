package app.lifeos.core.data.security

import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.SecretKey

internal enum class SegmentRevisionScope {
    PER_KEY,
    GLOBAL,
}

internal class SegmentRevisionPolicy<K, E>(
    val scope: SegmentRevisionScope,
    val keyOf: (E) -> K,
    val revisionOf: (E) -> Long,
) {
    fun validateEntry(entry: E) {
        require(revisionOf(entry) > 0L) { "Segment revision must be positive" }
    }

    fun validateHistory(entries: Collection<E>) {
        entries.forEach(::validateEntry)
        when (scope) {
            SegmentRevisionScope.GLOBAL -> {
                val revisions = entries.map(revisionOf)
                require(revisions.distinct().size == revisions.size) {
                    "Segmented ledger contains duplicate global revisions"
                }
                val ordered = revisions.sorted()
                val tail = ordered.lastOrNull() ?: 0L
                require(ordered == if (tail == 0L) emptyList() else (1L..tail).toList()) {
                    "Segmented ledger global revisions are not contiguous"
                }
            }

            SegmentRevisionScope.PER_KEY -> entries
                .groupBy(keyOf)
                .values
                .forEach { keyed ->
                    val revisions = keyed.map(revisionOf)
                    require(revisions.distinct().size == revisions.size) {
                        "Segmented ledger contains duplicate per-key revisions"
                    }
                    val ordered = revisions.sorted()
                    val tail = ordered.lastOrNull() ?: 0L
                    require(ordered == if (tail == 0L) emptyList() else (1L..tail).toList()) {
                        "Segmented ledger per-key revisions are not contiguous"
                    }
                }
        }
    }

    fun currentRevision(entries: Collection<E>, key: K): Long = when (scope) {
        SegmentRevisionScope.GLOBAL -> entries.maxOfOrNull(revisionOf) ?: 0L
        SegmentRevisionScope.PER_KEY -> entries
            .asSequence()
            .filter { keyOf(it) == key }
            .maxOfOrNull(revisionOf)
            ?: 0L
    }

    fun requireAppend(expectedRevision: Long, entry: E) {
        require(expectedRevision >= 0L)
        validateEntry(entry)
        require(revisionOf(entry) == expectedRevision + 1L) {
            "Segmented ledger append revision mismatch"
        }
    }
}

internal class SegmentPathBinding<K>(
    private val ledgerDomain: String,
    private val segmentsDirectory: File,
    private val keyFingerprint: (K) -> String,
    private val segmentPrefix: String,
    private val segmentSuffix: String,
) {
    init {
        require(ledgerDomain.isNotBlank())
        require(segmentPrefix.isNotBlank())
        require(segmentSuffix.isNotBlank())
    }

    fun segmentFile(key: K, revision: Long): File {
        require(revision > 0L)
        val keyDirectory = keyFingerprint(key)
        require(keyDirectory.matches(SHA256_REGEX)) {
            "Segment key path must be lowercase SHA-256"
        }
        return segmentsDirectory.resolve(keyDirectory)
            .resolve(segmentPrefix + revision.toString().padStart(20, '0') + segmentSuffix)
    }

    fun revisionFrom(file: File): Long = requireNotNull(
        file.name
            .removePrefix(segmentPrefix)
            .removeSuffix(segmentSuffix)
            .toLongOrNull()
    ) { "Invalid segmented-ledger revision path: " + file.name }

    fun isSegmentName(name: String): Boolean =
        name.startsWith(segmentPrefix) && name.endsWith(segmentSuffix)

    fun logicalFiles(): List<File> {
        if (!segmentsDirectory.exists()) return emptyList()
        return segmentsDirectory.walkTopDown()
            .filter { it.isFile }
            .mapNotNull { file ->
                when {
                    isSegmentName(file.name) -> file
                    isSegmentName(file.name.removeSuffix(ATOMIC_BACKUP_SUFFIX)) &&
                        file.name.endsWith(ATOMIC_BACKUP_SUFFIX) ->
                        File(file.path.removeSuffix(ATOMIC_BACKUP_SUFFIX))
                    else -> null
                }
            }
            .distinctBy { it.path }
            .sortedBy { it.path }
            .toList()
    }

    fun validatePath(file: File, key: K, revision: Long) {
        val expected = segmentFile(key, revision)
        require(file.absoluteFile.normalize() == expected.absoluteFile.normalize()) {
            "Segment payload identity/revision does not match physical path"
        }
    }

    fun associatedData(file: File): ByteArray {
        val relative = file.absoluteFile.normalize()
            .relativeTo(segmentsDirectory.parentFile.absoluteFile.normalize())
            .invariantSeparatorsPath
        return ("lifeos-segment-aad/v1|" + ledgerDomain + "|" + relative)
            .toByteArray(Charsets.UTF_8)
    }

    companion object {
        private val SHA256_REGEX = Regex("[0-9a-f]{64}")
        private const val ATOMIC_BACKUP_SUFFIX = ".bak"
    }
}

internal fun segmentKeySha256(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

internal data class SegmentedLedgerLoadReport<E>(
    val entries: List<E>,
    val unreadableEntries: List<String>,
)

internal class EncryptedCasHeadStore<T>(
    private val file: File,
    private val key: SecretKey,
    private val domain: String,
    private val maxPlaintextBytes: Int,
    private val encode: (T) -> ByteArray,
    private val decode: (ByteArray) -> T,
    private val validate: (T) -> Unit = {},
) {
    init {
        require(domain.isNotBlank())
        require(maxPlaintextBytes > 0)
    }

    fun exists(): Boolean = file.exists() || File(file.path + ".bak").exists()

    fun load(): T? {
        if (!exists()) return null
        return readBoundOrMigrateLegacy()
    }

    fun compareAndSet(expected: T?, update: T): Boolean {
        validate(update)
        val current = load()
        if (current != expected) return false
        write(update)
        return true
    }

    fun write(value: T) {
        validate(value)
        val plaintext = encode(value)
        EncryptedLedgerVaultSupport.atomicWrite(
            file,
            EncryptedLedgerVaultSupport.encrypt(
                plaintext = plaintext,
                key = key,
                maxPlaintextBytes = maxPlaintextBytes,
                associatedData = associatedData(),
            ),
        )
    }

    private fun readBoundOrMigrateLegacy(): T {
        val container = EncryptedLedgerVaultSupport.readAtomic(file, maxPlaintextBytes)
        val bound = runCatching {
            decode(
                EncryptedLedgerVaultSupport.decrypt(
                    container = container,
                    key = key,
                    maxPlaintextBytes = maxPlaintextBytes,
                    associatedData = associatedData(),
                )
            ).also(validate)
        }
        bound.getOrNull()?.let { return it }

        val legacy = decode(
            EncryptedLedgerVaultSupport.decrypt(
                container = container,
                key = key,
                maxPlaintextBytes = maxPlaintextBytes,
            )
        ).also(validate)
        write(legacy)
        return legacy
    }

    private fun associatedData(): ByteArray =
        ("lifeos-cas-head-aad/v1|" + domain + "|" + file.name)
            .toByteArray(Charsets.UTF_8)
}

internal class EncryptedSegmentedLedger<K, E>(
    private val rootDirectory: File,
    private val segmentsDirectory: File,
    private val key: SecretKey,
    private val maxPlaintextBytes: Int,
    private val pathBinding: SegmentPathBinding<K>,
    private val revisionPolicy: SegmentRevisionPolicy<K, E>,
    private val encode: (E) -> ByteArray,
    private val decode: (ByteArray) -> E,
    private val entryComparator: Comparator<E>,
) {
    fun loadReport(): SegmentedLedgerLoadReport<E> {
        ensureDirectory()
        val unreadable = mutableListOf<String>()
        val entries = pathBinding.logicalFiles().mapNotNull { file ->
            runCatching { readValidated(file) }
                .onFailure { unreadable += file.relativeTo(rootDirectory).invariantSeparatorsPath }
                .getOrNull()
        }
        if (unreadable.isEmpty()) revisionPolicy.validateHistory(entries)
        return SegmentedLedgerLoadReport(
            entries = entries.sortedWith(entryComparator),
            unreadableEntries = unreadable.distinct().sorted(),
        )
    }

    fun readStrict(): List<E> {
        val report = loadReport()
        require(report.unreadableEntries.isEmpty()) {
            "Segmented ledger contains unreadable entries: " +
                report.unreadableEntries.joinToString()
        }
        return report.entries
    }

    fun currentRevision(key: K): Long {
        val entries = readStrict()
        return revisionPolicy.currentRevision(entries, key)
    }

    fun append(expectedRevision: Long, entry: E): Boolean {
        val entryKey = revisionPolicy.keyOf(entry)
        val entries = readStrict()
        val current = revisionPolicy.currentRevision(entries, entryKey)
        if (current != expectedRevision) return false
        revisionPolicy.requireAppend(expectedRevision, entry)

        val target = pathBinding.segmentFile(entryKey, revisionPolicy.revisionOf(entry))
        if (exists(target)) {
            require(readValidated(target) == entry) {
                "Segmented ledger revision collision"
            }
            return true
        }
        target.parentFile?.let { check(it.isDirectory || it.mkdirs()) }
        writeBound(target, entry)
        return true
    }

    fun migrateIfEmpty(loadLegacy: () -> List<E>) {
        ensureDirectory()
        if (pathBinding.logicalFiles().isNotEmpty()) return
        val legacy = loadLegacy()
        if (legacy.isEmpty()) return
        revisionPolicy.validateHistory(legacy)
        legacy.sortedWith(entryComparator).forEach { entry ->
            val target = pathBinding.segmentFile(
                revisionPolicy.keyOf(entry),
                revisionPolicy.revisionOf(entry),
            )
            target.parentFile?.let { check(it.isDirectory || it.mkdirs()) }
            if (exists(target)) {
                require(readValidated(target) == entry) {
                    "Segmented ledger migration collision"
                }
            } else {
                writeBound(target, entry)
            }
        }
    }

    private fun readValidated(file: File): E {
        val container = EncryptedLedgerVaultSupport.readAtomic(file, maxPlaintextBytes)
        val bound = runCatching {
            decode(
                EncryptedLedgerVaultSupport.decrypt(
                    container = container,
                    key = key,
                    maxPlaintextBytes = maxPlaintextBytes,
                    associatedData = pathBinding.associatedData(file),
                )
            )
        }
        bound.getOrNull()?.let { entry ->
            validatePath(file, entry)
            return entry
        }

        // Previous repositories used unbound AES-GCM. Validate the decoded payload against the
        // physical path before rewriting it with path-bound AAD. Wrong-path legacy ciphertext is
        // therefore rejected rather than blessed into the new format.
        val legacy = decode(
            EncryptedLedgerVaultSupport.decrypt(
                container = container,
                key = key,
                maxPlaintextBytes = maxPlaintextBytes,
            )
        )
        validatePath(file, legacy)
        writeBound(file, legacy)
        return legacy
    }

    private fun validatePath(file: File, entry: E) {
        revisionPolicy.validateEntry(entry)
        pathBinding.validatePath(
            file = file,
            key = revisionPolicy.keyOf(entry),
            revision = revisionPolicy.revisionOf(entry),
        )
    }

    private fun writeBound(file: File, entry: E) {
        validatePath(file, entry)
        EncryptedLedgerVaultSupport.atomicWrite(
            file,
            EncryptedLedgerVaultSupport.encrypt(
                plaintext = encode(entry),
                key = key,
                maxPlaintextBytes = maxPlaintextBytes,
                associatedData = pathBinding.associatedData(file),
            ),
        )
    }

    private fun ensureDirectory() {
        check(rootDirectory.isDirectory || rootDirectory.mkdirs()) {
            "Segmented ledger root unavailable"
        }
        check(segmentsDirectory.isDirectory || segmentsDirectory.mkdirs()) {
            "Segmented ledger segment directory unavailable"
        }
    }

    private fun exists(file: File): Boolean =
        file.exists() || File(file.path + ".bak").exists()
}

internal object LedgerLongCodec {
    fun encode(value: Long): ByteArray {
        require(value >= 0L)
        return ByteBuffer.allocate(Long.SIZE_BYTES).putLong(value).array()
    }

    fun decode(bytes: ByteArray): Long {
        require(bytes.size == Long.SIZE_BYTES)
        return ByteBuffer.wrap(bytes).long.also { require(it >= 0L) }
    }
}
