package app.lifeos.next

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import app.lifeos.core.model.StableCognitiveIds
import app.lifeos.core.runtime.life.InitialDataSourceAdapter
import app.lifeos.core.runtime.life.InitialDataSourcePage
import app.lifeos.core.runtime.life.InitialDataSourceStatus
import app.lifeos.core.runtime.life.LifeSourceDescriptor
import app.lifeos.core.runtime.life.LifeSourceRecord
import java.time.Instant

/**
 * Owner-authorized external-storage inventory for general files/documents.
 * Dedicated MediaStore adapters continue to own image/video/audio records.
 */
internal class AndroidSharedFilesInitialDataSource(
    private val context: Context,
) : InitialDataSourceAdapter {
    override val descriptor = LifeSourceDescriptor(SOURCE_ID, ADAPTER_VERSION)

    override suspend fun status(): InitialDataSourceStatus = when {
        context.packageManager.resolveContentProvider(MediaStore.AUTHORITY, 0) == null ->
            InitialDataSourceStatus.UNAVAILABLE
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager() ->
            InitialDataSourceStatus.UNAUTHORIZED
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R &&
            context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED ->
            InitialDataSourceStatus.UNAUTHORIZED
        else -> InitialDataSourceStatus.AVAILABLE
    }

    override suspend fun readPage(afterPosition: String?, limit: Int): InitialDataSourcePage {
        check(status() == InitialDataSourceStatus.AVAILABLE) {
            "Shared-file source is not currently authorized"
        }
        val uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = buildList {
            add(MediaStore.Files.FileColumns._ID)
            add(MediaStore.Files.FileColumns.DISPLAY_NAME)
            add(MediaStore.Files.FileColumns.MIME_TYPE)
            add(MediaStore.Files.FileColumns.SIZE)
            add(MediaStore.Files.FileColumns.DATE_ADDED)
            add(MediaStore.Files.FileColumns.DATE_MODIFIED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Files.FileColumns.RELATIVE_PATH)
            }
        }.toTypedArray()
        val args = Bundle().apply {
            val clauses = mutableListOf<String>()
            val values = mutableListOf<String>()
            if (afterPosition != null) {
                clauses += "${MediaStore.Files.FileColumns._ID} > ?"
                values += afterPosition
            }
            clauses += "(${MediaStore.Files.FileColumns.MIME_TYPE} IS NULL OR (" +
                "${MediaStore.Files.FileColumns.MIME_TYPE} NOT LIKE 'image/%' AND " +
                "${MediaStore.Files.FileColumns.MIME_TYPE} NOT LIKE 'video/%' AND " +
                "${MediaStore.Files.FileColumns.MIME_TYPE} NOT LIKE 'audio/%'))"
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, clauses.joinToString(" AND "))
            if (values.isNotEmpty()) {
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, values.toTypedArray())
            }
            putStringArray(
                ContentResolver.QUERY_ARG_SORT_COLUMNS,
                arrayOf(MediaStore.Files.FileColumns._ID),
            )
            putInt(
                ContentResolver.QUERY_ARG_SORT_DIRECTION,
                ContentResolver.QUERY_SORT_DIRECTION_ASCENDING,
            )
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
        }

        val rows = buildList {
            context.contentResolver.query(uri, projection, args, null)?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val nameIndex = cursor.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val mimeIndex = cursor.getColumnIndex(MediaStore.Files.FileColumns.MIME_TYPE)
                val sizeIndex = cursor.getColumnIndex(MediaStore.Files.FileColumns.SIZE)
                val addedIndex = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATE_ADDED)
                val modifiedIndex = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATE_MODIFIED)
                val pathIndex = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.Files.FileColumns.RELATIVE_PATH)
                } else -1

                while (cursor.moveToNext()) {
                    val rowId = cursor.getLong(idIndex)
                    val name = cursor.textOrEmpty(nameIndex)
                    val mime = cursor.textOrEmpty(mimeIndex)
                    val size = cursor.longOrZero(sizeIndex)
                    val added = cursor.longOrZero(addedIndex)
                    val modified = cursor.longOrZero(modifiedIndex)
                    val relativePath = cursor.textOrEmpty(pathIndex)
                    val itemUri = ContentUris.withAppendedId(uri, rowId).toString()
                    val state = StableCognitiveIds.fingerprint(
                        "android-shared-file-row/v1",
                        name,
                        mime,
                        size.toString(),
                        added.toString(),
                        modified.toString(),
                        relativePath,
                        itemUri,
                    )
                    add(
                        rowId to LifeSourceRecord(
                            sourceId = SOURCE_ID,
                            recordId = "file-$rowId-$state",
                            observedAt = if (modified > 0L) {
                                Instant.ofEpochSecond(modified)
                            } else if (added > 0L) {
                                Instant.ofEpochSecond(added)
                            } else {
                                Instant.EPOCH
                            },
                            payload = buildString {
                                appendLine("uri=$itemUri")
                                appendLine("name=$name")
                                appendLine("mime_type=$mime")
                                appendLine("size_bytes=$size")
                                appendLine("date_added_s=$added")
                                appendLine("date_modified_s=$modified")
                                append("relative_path=$relativePath")
                            },
                            mimeType = "application/vnd.lifeos.android-shared-file-metadata+text",
                            tags = buildSet {
                                add("file")
                                add("document")
                                add("shared-storage")
                                if (name.isNotBlank()) add("document:$name")
                            },
                        )
                    )
                }
            }
        }
        val next = if (rows.size < limit) null else rows.lastOrNull()?.first?.toString()
        return InitialDataSourcePage(
            records = rows.map { it.second },
            nextPosition = next,
            complete = next == null,
        )
    }

    private fun android.database.Cursor.textOrEmpty(index: Int): String =
        if (index >= 0 && !isNull(index)) getString(index).orEmpty().replace('\n', ' ').trim() else ""

    private fun android.database.Cursor.longOrZero(index: Int): Long =
        if (index >= 0 && !isNull(index)) getLong(index) else 0L

    companion object {
        const val SOURCE_ID = "android-shared-files"
        const val ADAPTER_VERSION = "android-shared-files/v1"
    }
}
