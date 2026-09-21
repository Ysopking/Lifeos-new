package app.lifeos.next

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.lifeos.core.data.SourceDeltaKind
import app.lifeos.core.model.StableCognitiveIds
import java.io.File

internal data class StorageInventoryEntry(
    val volumeId: String,
    val relativePath: String,
    val absolutePath: String,
    val sizeBytes: Long,
    val modifiedAtMillis: Long,
    val category: AndroidFileCategory,
    val suspectedEncrypted: Boolean,
    val hashOffsetBytes: Long,
    val hashChain: String?,
    val contentFingerprint: String?,
    val lastSeenScanId: String,
) {
    init {
        require(volumeId.isNotBlank())
        require(relativePath.isNotBlank())
        require(sizeBytes >= 0L)
        require(modifiedAtMillis >= 0L)
        require(hashOffsetBytes in 0L..sizeBytes)
        require(hashChain == null || hashChain.matches(Regex("[0-9a-f]{64}")))
        require(contentFingerprint == null || contentFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(lastSeenScanId.isNotBlank())
    }

    fun asIndexedFile(): StorageIndexedFile = StorageIndexedFile(
        volumeId = volumeId,
        relativePath = relativePath,
        absolutePath = absolutePath,
        sizeBytes = sizeBytes,
        modifiedAtMillis = modifiedAtMillis,
        category = category,
        suspectedEncrypted = suspectedEncrypted,
        contentFingerprint = contentFingerprint,
    )

    val metadataStateFingerprint: String
        get() = StableCognitiveIds.fingerprint(
            "android-storage-live-state/v1",
            sizeBytes.toString(),
            modifiedAtMillis.toString(),
            category.name,
            suspectedEncrypted.toString(),
        )
}

internal data class StorageChangeEntry(
    val revision: Long,
    val volumeId: String,
    val relativePath: String,
    val kind: SourceDeltaKind,
    val previousFingerprint: String?,
    val newFingerprint: String?,
    val observedAtMillis: Long,
) {
    init {
        require(revision > 0L)
        require(volumeId.isNotBlank())
        require(relativePath.isNotBlank())
        require(previousFingerprint == null || previousFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(newFingerprint == null || newFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(observedAtMillis >= 0L)
    }
}

/**
 * Durable metadata/content-fingerprint index for owner-reachable shared storage.
 *
 * The database contains metadata and resumable cryptographic fingerprints only. File contents are
 * never copied into this database. A metadata change resets hash progress so stale fingerprints
 * cannot be reused for deletion decisions.
 */
internal class AndroidStorageInventoryStore(
    context: Context,
) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION,
), StorageChangeJournal {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE storage_files (
                volume_id TEXT NOT NULL,
                relative_path TEXT NOT NULL,
                absolute_path TEXT NOT NULL,
                size_bytes INTEGER NOT NULL,
                modified_ms INTEGER NOT NULL,
                category TEXT NOT NULL,
                suspected_encrypted INTEGER NOT NULL,
                hash_offset_bytes INTEGER NOT NULL DEFAULT 0,
                hash_chain TEXT,
                content_fingerprint TEXT,
                last_seen_scan_id TEXT NOT NULL,
                PRIMARY KEY(volume_id, relative_path)
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX storage_files_hash_idx ON storage_files(size_bytes, content_fingerprint)"
        )
        db.execSQL(
            "CREATE INDEX storage_files_pending_hash_idx ON storage_files(content_fingerprint, hash_offset_bytes)"
        )
        db.execSQL(
            "CREATE INDEX storage_files_scan_idx ON storage_files(last_seen_scan_id)"
        )
        createChangeLog(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion == 1 && newVersion >= 2) {
            createChangeLog(db)
            db.execSQL(
                """
                INSERT INTO $CHANGE_TABLE(
                    $CHANGE_COL_VOLUME,
                    $CHANGE_COL_PATH,
                    $CHANGE_COL_KIND,
                    $CHANGE_COL_PREVIOUS,
                    $CHANGE_COL_NEW,
                    $CHANGE_COL_OBSERVED
                )
                SELECT
                    $COL_VOLUME,
                    $COL_PATH,
                    'CREATED',
                    NULL,
                    NULL,
                    $COL_MODIFIED
                FROM $TABLE
                ORDER BY $COL_VOLUME, $COL_PATH
                """.trimIndent()
            )
            return
        }
        error("Storage inventory schema has no migration from $oldVersion to $newVersion")
    }

    fun upsertMetadata(
        scanId: String,
        volumeId: String,
        files: List<StorageTreePager.Entry>,
    ) {
        require(scanId.isNotBlank())
        require(volumeId.isNotBlank())
        val db = writableDatabase
        db.beginTransaction()
        try {
            files.forEach { entry ->
                val file = entry.file
                val classification = AndroidFileMetadataClassifier.classify(
                    relativePath = entry.relativePath,
                    displayName = file.name,
                    mimeType = null,
                )
                val size = file.length().coerceAtLeast(0L)
                val modified = file.lastModified().coerceAtLeast(0L)
                val previous = loadInternal(db, volumeId, entry.relativePath)
                val unchanged = previous != null &&
                    previous.sizeBytes == size &&
                    previous.modifiedAtMillis == modified &&
                    previous.category == classification.category &&
                    previous.suspectedEncrypted == classification.suspectedEncrypted
                val previousFingerprint = previous?.let(::metadataFingerprint)
                val nextFingerprint = metadataFingerprint(
                    sizeBytes = size,
                    modifiedAtMillis = modified,
                    category = classification.category,
                    suspectedEncrypted = classification.suspectedEncrypted,
                )

                val values = ContentValues().apply {
                    put(COL_VOLUME, volumeId)
                    put(COL_PATH, entry.relativePath)
                    put(COL_ABSOLUTE, file.absolutePath)
                    put(COL_SIZE, size)
                    put(COL_MODIFIED, modified)
                    put(COL_CATEGORY, classification.category.name)
                    put(COL_ENCRYPTED, if (classification.suspectedEncrypted) 1 else 0)
                    put(COL_SCAN, scanId)
                    if (unchanged) {
                        put(COL_HASH_OFFSET, previous!!.hashOffsetBytes)
                        if (previous.hashChain == null) putNull(COL_HASH_CHAIN)
                        else put(COL_HASH_CHAIN, previous.hashChain)
                        if (previous.contentFingerprint == null) putNull(COL_FINGERPRINT)
                        else put(COL_FINGERPRINT, previous.contentFingerprint)
                    } else {
                        put(COL_HASH_OFFSET, 0L)
                        putNull(COL_HASH_CHAIN)
                        putNull(COL_FINGERPRINT)
                    }
                }
                val rowId = db.insertWithOnConflict(
                    TABLE,
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
                check(rowId != -1L) { "Storage inventory metadata upsert failed" }

                if (!unchanged) {
                    appendChange(
                        db = db,
                        volumeId = volumeId,
                        relativePath = entry.relativePath,
                        kind = if (previous == null) {
                            SourceDeltaKind.CREATED
                        } else {
                            SourceDeltaKind.UPDATED
                        },
                        previousFingerprint = previousFingerprint,
                        newFingerprint = nextFingerprint,
                        observedAtMillis = modified,
                    )
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun pendingHashEntries(limit: Int): List<StorageInventoryEntry> {
        require(limit > 0)
        val out = mutableListOf<StorageInventoryEntry>()
        readableDatabase.query(
            TABLE,
            COLUMNS,
            "$COL_FINGERPRINT IS NULL",
            null,
            null,
            null,
            "$COL_SIZE ASC, $COL_VOLUME ASC, $COL_PATH ASC",
            limit.toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) out += cursor.toEntry()
        }
        return out
    }

    fun updateHashProgress(
        entry: StorageInventoryEntry,
        nextOffsetBytes: Long,
        nextChain: String,
        finalFingerprint: String?,
    ): Boolean {
        require(nextOffsetBytes in 0L..entry.sizeBytes)
        require(nextChain.matches(Regex("[0-9a-f]{64}")))
        require(finalFingerprint == null || finalFingerprint.matches(Regex("[0-9a-f]{64}")))
        val values = ContentValues().apply {
            put(COL_HASH_OFFSET, nextOffsetBytes)
            put(COL_HASH_CHAIN, nextChain)
            if (finalFingerprint == null) putNull(COL_FINGERPRINT)
            else put(COL_FINGERPRINT, finalFingerprint)
        }
        return writableDatabase.update(
            TABLE,
            values,
            "$COL_VOLUME=? AND $COL_PATH=? AND $COL_SIZE=? AND $COL_MODIFIED=?",
            arrayOf(
                entry.volumeId,
                entry.relativePath,
                entry.sizeBytes.toString(),
                entry.modifiedAtMillis.toString(),
            ),
        ) == 1
    }

    fun resetHashProgress(entry: StorageInventoryEntry) {
        val values = ContentValues().apply {
            put(COL_HASH_OFFSET, 0L)
            putNull(COL_HASH_CHAIN)
            putNull(COL_FINGERPRINT)
        }
        writableDatabase.update(
            TABLE,
            values,
            "$COL_VOLUME=? AND $COL_PATH=?",
            arrayOf(entry.volumeId, entry.relativePath),
        )
    }

    fun purgeNotSeen(scanId: String): Int {
        require(scanId.isNotBlank())
        val db = writableDatabase
        db.beginTransaction()
        try {
            val stale = mutableListOf<StorageInventoryEntry>()
            db.query(
                TABLE,
                COLUMNS,
                "$COL_SCAN<>?",
                arrayOf(scanId),
                null,
                null,
                "$COL_VOLUME ASC, $COL_PATH ASC",
            ).use { cursor ->
                while (cursor.moveToNext()) stale += cursor.toEntry()
            }

            stale.forEach { entry ->
                appendChange(
                    db = db,
                    volumeId = entry.volumeId,
                    relativePath = entry.relativePath,
                    kind = SourceDeltaKind.DELETED,
                    previousFingerprint = metadataFingerprint(entry),
                    newFingerprint = null,
                    observedAtMillis = System.currentTimeMillis(),
                )
            }

            val deleted = db.delete(TABLE, "$COL_SCAN<>?", arrayOf(scanId))
            db.setTransactionSuccessful()
            return deleted
        } finally {
            db.endTransaction()
        }
    }

    fun loadPage(
        afterPosition: String?,
        limit: Int,
    ): StorageInventoryPage {
        require(limit > 0)
        val cursor = afterPosition?.let(::decodePosition)
        val entries = mutableListOf<StorageIndexedFile>()
        val selection = if (cursor == null) {
            null
        } else {
            "($COL_VOLUME>? OR ($COL_VOLUME=? AND $COL_PATH>?))"
        }
        val args = cursor?.let {
            arrayOf(it.first, it.first, it.second)
        }
        readableDatabase.query(
            TABLE,
            COLUMNS,
            selection,
            args,
            null,
            null,
            "$COL_VOLUME ASC, $COL_PATH ASC",
            limit.toString(),
        ).use { dbCursor ->
            while (dbCursor.moveToNext()) entries += dbCursor.toEntry().asIndexedFile()
        }
        val next = if (entries.size < limit) {
            null
        } else {
            entries.lastOrNull()?.let { encodePosition(it.volumeId, it.relativePath) }
        }
        return StorageInventoryPage(
            entries = entries,
            nextPosition = next,
            complete = next == null,
        )
    }

    fun exactDuplicateGroups(): List<List<StorageIndexedFile>> {
        val keys = mutableListOf<Pair<Long, String>>()
        readableDatabase.rawQuery(
            """
            SELECT $COL_SIZE, $COL_FINGERPRINT
            FROM $TABLE
            WHERE $COL_FINGERPRINT IS NOT NULL AND $COL_SIZE > 0
            GROUP BY $COL_SIZE, $COL_FINGERPRINT
            HAVING COUNT(*) > 1
            ORDER BY $COL_SIZE DESC, $COL_FINGERPRINT ASC
            """.trimIndent(),
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                keys += cursor.getLong(0) to cursor.getString(1)
            }
        }
        return keys.map { (size, fingerprint) ->
            val entries = mutableListOf<StorageIndexedFile>()
            readableDatabase.query(
                TABLE,
                COLUMNS,
                "$COL_SIZE=? AND $COL_FINGERPRINT=?",
                arrayOf(size.toString(), fingerprint),
                null,
                null,
                "$COL_VOLUME ASC, $COL_PATH ASC",
            ).use { cursor ->
                while (cursor.moveToNext()) entries += cursor.toEntry().asIndexedFile()
            }
            entries
        }
    }

    fun reviewEntries(): List<StorageIndexedFile> {
        val out = mutableListOf<StorageIndexedFile>()
        readableDatabase.query(
            TABLE,
            COLUMNS,
            "$COL_SIZE>=? OR lower($COL_PATH) LIKE ? OR lower($COL_PATH) LIKE ? OR lower($COL_PATH) LIKE ? OR lower($COL_PATH) LIKE ?",
            arrayOf(
                LARGE_REVIEW_BYTES.toString(),
                "%.tmp",
                "%.temp",
                "%.part",
                "%.apk",
            ),
            null,
            null,
            "$COL_SIZE DESC, $COL_VOLUME ASC, $COL_PATH ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) out += cursor.toEntry().asIndexedFile()
        }
        return out
    }

    override fun load(
        volumeId: String,
        relativePath: String,
    ): StorageInventoryEntry? {
        require(volumeId.isNotBlank())
        require(relativePath.isNotBlank())
        return loadInternal(readableDatabase, volumeId, relativePath)
    }

    override fun pruneChangesThrough(revisionInclusive: Long): Int {
        require(revisionInclusive >= 0L)
        if (revisionInclusive == 0L) return 0
        return writableDatabase.delete(
            CHANGE_TABLE,
            "$CHANGE_COL_REVISION<=?",
            arrayOf(revisionInclusive.toString()),
        )
    }

    override fun currentChangeRevision(): Long =
        readableDatabase.rawQuery(
            "SELECT COALESCE(MAX($CHANGE_COL_REVISION), 0) FROM $CHANGE_TABLE",
            null,
        ).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }

    override fun loadChangesAfter(
        revisionExclusive: Long,
        limit: Int,
    ): List<StorageChangeEntry> {
        require(revisionExclusive >= 0L)
        require(limit in 1..MAX_CHANGE_PAGE)
        val out = mutableListOf<StorageChangeEntry>()
        readableDatabase.query(
            CHANGE_TABLE,
            CHANGE_COLUMNS,
            "$CHANGE_COL_REVISION>?",
            arrayOf(revisionExclusive.toString()),
            null,
            null,
            "$CHANGE_COL_REVISION ASC",
            limit.toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) out += cursor.toChangeEntry()
        }
        return out
    }

    override fun loadChange(
        revision: Long,
    ): StorageChangeEntry? {
        require(revision > 0L)
        readableDatabase.query(
            CHANGE_TABLE,
            CHANGE_COLUMNS,
            "$CHANGE_COL_REVISION=?",
            arrayOf(revision.toString()),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            return if (cursor.moveToFirst()) {
                cursor.toChangeEntry()
            } else {
                null
            }
        }
    }

    fun summary(): StorageInventorySummary {
        readableDatabase.rawQuery(
            """
            SELECT COUNT(*), COALESCE(SUM($COL_SIZE),0),
                   SUM(CASE WHEN $COL_FINGERPRINT IS NOT NULL THEN 1 ELSE 0 END),
                   COALESCE(SUM(CASE WHEN $COL_FINGERPRINT IS NOT NULL THEN $COL_SIZE ELSE 0 END),0)
            FROM $TABLE
            """.trimIndent(),
            null,
        ).use { cursor ->
            check(cursor.moveToFirst())
            return StorageInventorySummary(
                indexedFiles = cursor.getLong(0),
                indexedBytes = cursor.getLong(1),
                fullyFingerprintedFiles = cursor.getLong(2),
                fullyFingerprintedBytes = cursor.getLong(3),
            )
        }
    }

    private fun appendChange(
        db: SQLiteDatabase,
        volumeId: String,
        relativePath: String,
        kind: SourceDeltaKind,
        previousFingerprint: String?,
        newFingerprint: String?,
        observedAtMillis: Long,
    ) {
        val values = ContentValues().apply {
            put(CHANGE_COL_VOLUME, volumeId)
            put(CHANGE_COL_PATH, relativePath)
            put(CHANGE_COL_KIND, kind.name)
            if (previousFingerprint == null) putNull(CHANGE_COL_PREVIOUS)
            else put(CHANGE_COL_PREVIOUS, previousFingerprint)
            if (newFingerprint == null) putNull(CHANGE_COL_NEW)
            else put(CHANGE_COL_NEW, newFingerprint)
            put(CHANGE_COL_OBSERVED, observedAtMillis.coerceAtLeast(0L))
        }
        check(db.insert(CHANGE_TABLE, null, values) != -1L) {
            "Storage change-log append failed"
        }
    }

    private fun metadataFingerprint(entry: StorageInventoryEntry): String =
        entry.metadataStateFingerprint

    private fun metadataFingerprint(
        sizeBytes: Long,
        modifiedAtMillis: Long,
        category: AndroidFileCategory,
        suspectedEncrypted: Boolean,
    ): String = StableCognitiveIds.fingerprint(
        "android-storage-live-state/v1",
        sizeBytes.toString(),
        modifiedAtMillis.toString(),
        category.name,
        suspectedEncrypted.toString(),
    )

    private fun loadInternal(
        db: SQLiteDatabase,
        volumeId: String,
        relativePath: String,
    ): StorageInventoryEntry? {
        db.query(
            TABLE,
            COLUMNS,
            "$COL_VOLUME=? AND $COL_PATH=?",
            arrayOf(volumeId, relativePath),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.toEntry() else null
        }
    }

    private fun android.database.Cursor.toChangeEntry(): StorageChangeEntry =
        StorageChangeEntry(
            revision = getLong(getColumnIndexOrThrow(CHANGE_COL_REVISION)),
            volumeId = getString(getColumnIndexOrThrow(CHANGE_COL_VOLUME)),
            relativePath = getString(getColumnIndexOrThrow(CHANGE_COL_PATH)),
            kind = SourceDeltaKind.valueOf(
                getString(getColumnIndexOrThrow(CHANGE_COL_KIND))
            ),
            previousFingerprint = getStringOrNull(
                getColumnIndexOrThrow(CHANGE_COL_PREVIOUS)
            ),
            newFingerprint = getStringOrNull(
                getColumnIndexOrThrow(CHANGE_COL_NEW)
            ),
            observedAtMillis = getLong(
                getColumnIndexOrThrow(CHANGE_COL_OBSERVED)
            ),
        )

    private fun android.database.Cursor.toEntry(): StorageInventoryEntry = StorageInventoryEntry(
        volumeId = getString(getColumnIndexOrThrow(COL_VOLUME)),
        relativePath = getString(getColumnIndexOrThrow(COL_PATH)),
        absolutePath = getString(getColumnIndexOrThrow(COL_ABSOLUTE)),
        sizeBytes = getLong(getColumnIndexOrThrow(COL_SIZE)),
        modifiedAtMillis = getLong(getColumnIndexOrThrow(COL_MODIFIED)),
        category = AndroidFileCategory.valueOf(getString(getColumnIndexOrThrow(COL_CATEGORY))),
        suspectedEncrypted = getInt(getColumnIndexOrThrow(COL_ENCRYPTED)) != 0,
        hashOffsetBytes = getLong(getColumnIndexOrThrow(COL_HASH_OFFSET)),
        hashChain = getStringOrNull(getColumnIndexOrThrow(COL_HASH_CHAIN)),
        contentFingerprint = getStringOrNull(getColumnIndexOrThrow(COL_FINGERPRINT)),
        lastSeenScanId = getString(getColumnIndexOrThrow(COL_SCAN)),
    )

    private fun android.database.Cursor.getStringOrNull(index: Int): String? =
        if (isNull(index)) null else getString(index)

    private fun encodePosition(volumeId: String, relativePath: String): String =
        volumeId.length.toString() + ":" + volumeId + relativePath

    private fun decodePosition(value: String): Pair<String, String> {
        val separator = value.indexOf(':')
        require(separator > 0) { "Invalid storage inventory position" }
        val volumeLength = value.substring(0, separator).toIntOrNull()
        require(volumeLength != null && volumeLength > 0) { "Invalid storage inventory volume length" }
        val volumeStart = separator + 1
        val pathStart = volumeStart + volumeLength
        require(pathStart < value.length) { "Invalid storage inventory position payload" }
        return value.substring(volumeStart, pathStart) to value.substring(pathStart)
    }

    companion object {
        const val LARGE_REVIEW_BYTES = 2L * 1024L * 1024L * 1024L
        private const val DATABASE_NAME = "lifeos-storage-inventory.db"
        private const val DATABASE_VERSION = 2
        private const val MAX_CHANGE_PAGE = 16_384
        private const val TABLE = "storage_files"
        private const val COL_VOLUME = "volume_id"
        private const val COL_PATH = "relative_path"
        private const val COL_ABSOLUTE = "absolute_path"
        private const val COL_SIZE = "size_bytes"
        private const val COL_MODIFIED = "modified_ms"
        private const val COL_CATEGORY = "category"
        private const val COL_ENCRYPTED = "suspected_encrypted"
        private const val COL_HASH_OFFSET = "hash_offset_bytes"
        private const val COL_HASH_CHAIN = "hash_chain"
        private const val COL_FINGERPRINT = "content_fingerprint"
        private const val COL_SCAN = "last_seen_scan_id"
        private val COLUMNS = arrayOf(
            COL_VOLUME,
            COL_PATH,
            COL_ABSOLUTE,
            COL_SIZE,
            COL_MODIFIED,
            COL_CATEGORY,
            COL_ENCRYPTED,
            COL_HASH_OFFSET,
            COL_HASH_CHAIN,
            COL_FINGERPRINT,
            COL_SCAN,
        )

        private const val CHANGE_TABLE = "storage_changes"
        private const val CHANGE_COL_REVISION = "revision"
        private const val CHANGE_COL_VOLUME = "volume_id"
        private const val CHANGE_COL_PATH = "relative_path"
        private const val CHANGE_COL_KIND = "delta_kind"
        private const val CHANGE_COL_PREVIOUS = "previous_fingerprint"
        private const val CHANGE_COL_NEW = "new_fingerprint"
        private const val CHANGE_COL_OBSERVED = "observed_ms"
        private val CHANGE_COLUMNS = arrayOf(
            CHANGE_COL_REVISION,
            CHANGE_COL_VOLUME,
            CHANGE_COL_PATH,
            CHANGE_COL_KIND,
            CHANGE_COL_PREVIOUS,
            CHANGE_COL_NEW,
            CHANGE_COL_OBSERVED,
        )

        private fun createChangeLog(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE $CHANGE_TABLE (
                    $CHANGE_COL_REVISION INTEGER PRIMARY KEY AUTOINCREMENT,
                    $CHANGE_COL_VOLUME TEXT NOT NULL,
                    $CHANGE_COL_PATH TEXT NOT NULL,
                    $CHANGE_COL_KIND TEXT NOT NULL,
                    $CHANGE_COL_PREVIOUS TEXT,
                    $CHANGE_COL_NEW TEXT,
                    $CHANGE_COL_OBSERVED INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX storage_changes_revision_idx ON $CHANGE_TABLE($CHANGE_COL_REVISION)"
            )
        }
    }
}

internal data class StorageInventorySummary(
    val indexedFiles: Long,
    val indexedBytes: Long,
    val fullyFingerprintedFiles: Long,
    val fullyFingerprintedBytes: Long,
) {
    val fingerprintComplete: Boolean
        get() = indexedFiles == fullyFingerprintedFiles
}


internal data class StorageInventoryPage(
    val entries: List<StorageIndexedFile>,
    val nextPosition: String?,
    val complete: Boolean,
) {
    init {
        require(complete == (nextPosition == null))
    }
}
