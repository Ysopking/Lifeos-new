package app.lifeos.next

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.lifeos.core.model.StableCognitiveIds
import java.time.Instant

internal enum class StorageTrashState {
    PREPARED,
    TRASHED,
    RESTORED,
    PURGED,
}

internal data class StorageTrashRecord(
    val id: String,
    val volumeId: String,
    val originalRelativePath: String,
    val trashRelativePath: String,
    val sizeBytes: Long,
    val modifiedAtMillis: Long,
    val preparedAt: Instant,
    val state: StorageTrashState,
    val settledAt: Instant? = null,
) {
    init {
        require(id.matches(Regex("[0-9a-f]{64}")))
        require(volumeId.isNotBlank())
        require(originalRelativePath.isNotBlank())
        require(trashRelativePath.startsWith(StorageTreePager.TRASH_ROOT + "/"))
        require(sizeBytes >= 0L)
        require(modifiedAtMillis >= 0L)
        require((state == StorageTrashState.PREPARED) == (settledAt == null))
    }

    companion object {
        fun prepare(
            volumeId: String,
            originalRelativePath: String,
            sizeBytes: Long,
            modifiedAtMillis: Long,
            preparedAt: Instant,
        ): StorageTrashRecord {
            val id = StableCognitiveIds.fingerprint(
                "storage-trash-record/v1",
                volumeId,
                originalRelativePath,
                sizeBytes.toString(),
                modifiedAtMillis.toString(),
                preparedAt.toString(),
            )
            return StorageTrashRecord(
                id = id,
                volumeId = volumeId,
                originalRelativePath = originalRelativePath,
                trashRelativePath =
                    StorageTreePager.TRASH_ROOT + "/" + id + "/" + originalRelativePath,
                sizeBytes = sizeBytes,
                modifiedAtMillis = modifiedAtMillis,
                preparedAt = preparedAt,
                state = StorageTrashState.PREPARED,
            )
        }
    }
}

internal class AndroidStorageMaintenanceStore(
    context: Context,
) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION,
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE storage_trash (
                id TEXT NOT NULL PRIMARY KEY,
                volume_id TEXT NOT NULL,
                original_relative_path TEXT NOT NULL,
                trash_relative_path TEXT NOT NULL,
                size_bytes INTEGER NOT NULL,
                modified_ms INTEGER NOT NULL,
                prepared_at TEXT NOT NULL,
                state TEXT NOT NULL,
                settled_at TEXT
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX storage_trash_state_idx ON storage_trash(state, prepared_at)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("Storage maintenance schema has no migration from " + oldVersion + " to " + newVersion)
    }

    fun prepare(record: StorageTrashRecord): StorageTrashRecord {
        require(record.state == StorageTrashState.PREPARED)
        val existing = load(record.id)
        if (existing != null) {
            require(existing == record) { "Storage trash record identity collision" }
            return existing
        }
        val values = values(record)
        check(
            writableDatabase.insertWithOnConflict(
                TABLE,
                null,
                values,
                SQLiteDatabase.CONFLICT_ABORT,
            ) != -1L
        ) { "Could not prepare storage trash record" }
        return record
    }

    fun settle(
        id: String,
        expected: StorageTrashState,
        state: StorageTrashState,
        settledAt: Instant,
    ): StorageTrashRecord {
        require(state != StorageTrashState.PREPARED)
        val current = requireNotNull(load(id)) { "Storage trash record does not exist" }
        if (current.state == state) return current
        require(current.state == expected) {
            "Storage trash state mismatch: expected " + expected + " but was " + current.state
        }
        val values = ContentValues().apply {
            put(COL_STATE, state.name)
            put(COL_SETTLED_AT, settledAt.toString())
        }
        check(
            writableDatabase.update(
                TABLE,
                values,
                "$COL_ID=? AND $COL_STATE=?",
                arrayOf(id, expected.name),
            ) == 1
        ) { "Storage trash state transition lost its exact predecessor" }
        return requireNotNull(load(id))
    }

    fun load(id: String): StorageTrashRecord? {
        readableDatabase.query(
            TABLE,
            COLUMNS,
            "$COL_ID=?",
            arrayOf(id),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.toRecord() else null
        }
    }

    fun loadByStates(states: Set<StorageTrashState>): List<StorageTrashRecord> {
        if (states.isEmpty()) return emptyList()
        val placeholders = states.joinToString(",") { "?" }
        val out = mutableListOf<StorageTrashRecord>()
        readableDatabase.query(
            TABLE,
            COLUMNS,
            "$COL_STATE IN ($placeholders)",
            states.map { it.name }.toTypedArray(),
            null,
            null,
            "$COL_PREPARED_AT ASC, $COL_ID ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) out += cursor.toRecord()
        }
        return out
    }

    fun loadAll(): List<StorageTrashRecord> {
        val out = mutableListOf<StorageTrashRecord>()
        readableDatabase.query(
            TABLE,
            COLUMNS,
            null,
            null,
            null,
            null,
            "$COL_PREPARED_AT DESC, $COL_ID ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) out += cursor.toRecord()
        }
        return out
    }

    private fun values(record: StorageTrashRecord): ContentValues = ContentValues().apply {
        put(COL_ID, record.id)
        put(COL_VOLUME, record.volumeId)
        put(COL_ORIGINAL, record.originalRelativePath)
        put(COL_TRASH, record.trashRelativePath)
        put(COL_SIZE, record.sizeBytes)
        put(COL_MODIFIED, record.modifiedAtMillis)
        put(COL_PREPARED_AT, record.preparedAt.toString())
        put(COL_STATE, record.state.name)
        if (record.settledAt == null) putNull(COL_SETTLED_AT)
        else put(COL_SETTLED_AT, record.settledAt.toString())
    }

    private fun android.database.Cursor.toRecord(): StorageTrashRecord =
        StorageTrashRecord(
            id = getString(getColumnIndexOrThrow(COL_ID)),
            volumeId = getString(getColumnIndexOrThrow(COL_VOLUME)),
            originalRelativePath = getString(getColumnIndexOrThrow(COL_ORIGINAL)),
            trashRelativePath = getString(getColumnIndexOrThrow(COL_TRASH)),
            sizeBytes = getLong(getColumnIndexOrThrow(COL_SIZE)),
            modifiedAtMillis = getLong(getColumnIndexOrThrow(COL_MODIFIED)),
            preparedAt = Instant.parse(getString(getColumnIndexOrThrow(COL_PREPARED_AT))),
            state = StorageTrashState.valueOf(getString(getColumnIndexOrThrow(COL_STATE))),
            settledAt = getColumnIndexOrThrow(COL_SETTLED_AT).let { index ->
                if (isNull(index)) null else Instant.parse(getString(index))
            },
        )

    private companion object {
        const val DATABASE_NAME = "lifeos-storage-maintenance.db"
        const val DATABASE_VERSION = 1
        const val TABLE = "storage_trash"
        const val COL_ID = "id"
        const val COL_VOLUME = "volume_id"
        const val COL_ORIGINAL = "original_relative_path"
        const val COL_TRASH = "trash_relative_path"
        const val COL_SIZE = "size_bytes"
        const val COL_MODIFIED = "modified_ms"
        const val COL_PREPARED_AT = "prepared_at"
        const val COL_STATE = "state"
        const val COL_SETTLED_AT = "settled_at"
        val COLUMNS = arrayOf(
            COL_ID,
            COL_VOLUME,
            COL_ORIGINAL,
            COL_TRASH,
            COL_SIZE,
            COL_MODIFIED,
            COL_PREPARED_AT,
            COL_STATE,
            COL_SETTLED_AT,
        )
    }
}
