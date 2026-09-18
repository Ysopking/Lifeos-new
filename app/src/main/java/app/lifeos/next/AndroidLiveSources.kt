package app.lifeos.next

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.BaseColumns
import android.provider.ContactsContract
import android.provider.MediaStore
import app.lifeos.core.data.LiveSourceAdapter
import app.lifeos.core.data.LiveSourceDeltaRuntime
import app.lifeos.core.data.LiveSourceId
import app.lifeos.core.data.SourceChangeSet
import app.lifeos.core.data.SourceCursor
import app.lifeos.core.data.SourceDelta
import app.lifeos.core.data.SourceDeltaKind
import app.lifeos.core.data.SourceInventory
import app.lifeos.core.data.SourcePrivacyZone
import app.lifeos.core.model.StableCognitiveIds
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal object AndroidLiveSourceIds {
    val CONTACTS = LiveSourceId("android-contacts")
    val MEDIA_IMAGES = LiveSourceId("android-media-images")
    val MEDIA_VIDEO = LiveSourceId("android-media-video")
    val MEDIA_AUDIO = LiveSourceId("android-media-audio")
}

internal class AndroidLiveSourceCatalog(
    private val context: Context,
) {
    fun authorizedAdapters(): List<LiveSourceAdapter> = buildList {
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            add(AndroidContactsLiveSourceAdapter(context))
        }
        AndroidLiveMediaKind.entries.forEach { kind ->
            if (context.checkSelfPermission(mediaPermission(kind)) == PackageManager.PERMISSION_GRANTED) {
                add(AndroidMediaLiveSourceAdapter(context, kind))
            }
        }
    }
}

/**
 * Provider observers are only wake-up signals. Truth still comes from changesAfter(cursor), so
 * duplicate callbacks and process restarts cannot manufacture source revisions.
 */
internal class AndroidLiveSourceObserverRegistry(
    private val context: Context,
    private val scope: CoroutineScope,
    private val runtime: LiveSourceDeltaRuntime,
    private val sourceIds: Set<LiveSourceId>,
) {
    private val started = AtomicBoolean(false)
    private val observers = mutableListOf<ContentObserver>()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        register(ContactsContract.Contacts.CONTENT_URI, AndroidLiveSourceIds.CONTACTS)
        register(ContactsContract.DeletedContacts.CONTENT_URI, AndroidLiveSourceIds.CONTACTS)
        AndroidLiveMediaKind.entries.forEach { kind ->
            register(kind.uri, kind.sourceId)
        }
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        observers.forEach(context.contentResolver::unregisterContentObserver)
        observers.clear()
    }

    private fun register(uri: Uri, sourceId: LiveSourceId) {
        if (sourceId !in sourceIds) return
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                scope.launch {
                    runCatching { runtime.sync(sourceId) }
                }
            }
        }
        context.contentResolver.registerContentObserver(uri, true, observer)
        observers += observer
    }
}

private class AndroidContactsLiveSourceAdapter(
    private val context: Context,
) : LiveSourceAdapter {
    override val sourceId: LiveSourceId = AndroidLiveSourceIds.CONTACTS

    override suspend fun inventory(): SourceInventory {
        val resolver = context.contentResolver
        val updated = latestPosition(
            resolver = resolver,
            uri = ContactsContract.Contacts.CONTENT_URI,
            timestampColumn = ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP,
            idColumn = ContactsContract.Contacts._ID,
        )
        val deleted = latestPosition(
            resolver = resolver,
            uri = ContactsContract.DeletedContacts.CONTENT_URI,
            timestampColumn = ContactsContract.DeletedContacts.CONTACT_DELETED_TIMESTAMP,
            idColumn = ContactsContract.DeletedContacts.CONTACT_ID,
        )
        return SourceInventory(
            items = emptyList(),
            cursor = ContactCursor(
                updatedAt = updated.first,
                updatedId = updated.second,
                deletedAt = deleted.first,
                deletedId = deleted.second,
                observationRevision = 0L,
            ).encode(),
        )
    }

    override suspend fun changesAfter(cursor: SourceCursor): SourceChangeSet {
        val state = ContactCursor.decode(cursor)
        val updatedRows = queryContactsAfter(state)
        val deletedRows = queryDeletedContactsAfter(state)
        val ordered = (updatedRows + deletedRows)
            .sortedWith(compareBy<PendingContactDelta> { it.observedAt }.thenBy { it.externalKey })

        var revision = state.observationRevision
        val deltas = ordered.map { row ->
            revision += 1L
            SourceDelta(
                deltaId = StableCognitiveIds.fingerprint(
                    "android-contact-live-delta/v1",
                    row.externalKey,
                    revision.toString(),
                    row.fingerprint.orEmpty(),
                    row.kind.name,
                ),
                sourceId = sourceId,
                externalKey = row.externalKey,
                kind = row.kind,
                previousFingerprint = null,
                newFingerprint = row.fingerprint,
                observationRevision = revision,
                privacyZone = SourcePrivacyZone.SENSITIVE,
                payload = row.payload,
                mimeType = "application/vnd.lifeos.android-contact-live+text",
                observedAtEpochMillis = row.observedAt,
            )
        }

        val newestUpdated = updatedRows.maxWithOrNull(
            compareBy<PendingContactDelta> { it.observedAt }.thenBy { it.providerId }
        )
        val newestDeleted = deletedRows.maxWithOrNull(
            compareBy<PendingContactDelta> { it.observedAt }.thenBy { it.providerId }
        )
        val next = state.copy(
            updatedAt = newestUpdated?.observedAt ?: state.updatedAt,
            updatedId = newestUpdated?.providerId ?: state.updatedId,
            deletedAt = newestDeleted?.observedAt ?: state.deletedAt,
            deletedId = newestDeleted?.providerId ?: state.deletedId,
            observationRevision = revision,
        )
        return SourceChangeSet(deltas = deltas, nextCursor = next.encode())
    }

    private fun queryContactsAfter(state: ContactCursor): List<PendingContactDelta> {
        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.LOOKUP_KEY,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
            ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP,
        )
        val selection =
            "(${ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP} > ?) OR " +
                "(${ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP} = ? AND " +
                "${ContactsContract.Contacts._ID} > ?)"
        val args = arrayOf(
            state.updatedAt.toString(),
            state.updatedAt.toString(),
            state.updatedId.toString(),
        )
        return buildList {
            querySorted(
                resolver = context.contentResolver,
                uri = ContactsContract.Contacts.CONTENT_URI,
                projection = projection,
                selection = selection,
                selectionArgs = args,
                sortColumns = arrayOf(
                    ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP,
                    ContactsContract.Contacts._ID,
                ),
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.longValue(ContactsContract.Contacts._ID)
                    val lookup = cursor.stringValue(ContactsContract.Contacts.LOOKUP_KEY).orEmpty()
                    val name = cursor.stringValue(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY).orEmpty()
                    val updatedAt = cursor.longValue(ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP)
                    val fingerprint = StableCognitiveIds.fingerprint(
                        "android-contact-live-state/v1",
                        id.toString(),
                        lookup,
                        name,
                        updatedAt.toString(),
                    )
                    add(
                        PendingContactDelta(
                            providerId = id,
                            externalKey = "contact-$id",
                            observedAt = updatedAt,
                            fingerprint = fingerprint,
                            kind = SourceDeltaKind.UPDATED,
                            payload = buildString {
                                appendLine("contact_id=$id")
                                appendLine("lookup_key=$lookup")
                                appendLine("name=$name")
                                append("updated_at_ms=$updatedAt")
                            },
                        )
                    )
                }
            }
        }
    }

    private fun queryDeletedContactsAfter(state: ContactCursor): List<PendingContactDelta> {
        val projection = arrayOf(
            ContactsContract.DeletedContacts.CONTACT_ID,
            ContactsContract.DeletedContacts.CONTACT_DELETED_TIMESTAMP,
        )
        val selection =
            "(${ContactsContract.DeletedContacts.CONTACT_DELETED_TIMESTAMP} > ?) OR " +
                "(${ContactsContract.DeletedContacts.CONTACT_DELETED_TIMESTAMP} = ? AND " +
                "${ContactsContract.DeletedContacts.CONTACT_ID} > ?)"
        val args = arrayOf(
            state.deletedAt.toString(),
            state.deletedAt.toString(),
            state.deletedId.toString(),
        )
        return buildList {
            querySorted(
                resolver = context.contentResolver,
                uri = ContactsContract.DeletedContacts.CONTENT_URI,
                projection = projection,
                selection = selection,
                selectionArgs = args,
                sortColumns = arrayOf(
                    ContactsContract.DeletedContacts.CONTACT_DELETED_TIMESTAMP,
                    ContactsContract.DeletedContacts.CONTACT_ID,
                ),
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.longValue(ContactsContract.DeletedContacts.CONTACT_ID)
                    val deletedAt = cursor.longValue(ContactsContract.DeletedContacts.CONTACT_DELETED_TIMESTAMP)
                    add(
                        PendingContactDelta(
                            providerId = id,
                            externalKey = "contact-$id",
                            observedAt = deletedAt,
                            fingerprint = null,
                            kind = SourceDeltaKind.DELETED,
                            payload = "contact_id=$id\ndeleted_at_ms=$deletedAt",
                        )
                    )
                }
            }
        }
    }
}

private data class PendingContactDelta(
    val providerId: Long,
    val externalKey: String,
    val observedAt: Long,
    val fingerprint: String?,
    val kind: SourceDeltaKind,
    val payload: String,
)

private data class ContactCursor(
    val updatedAt: Long,
    val updatedId: Long,
    val deletedAt: Long,
    val deletedId: Long,
    val observationRevision: Long,
) {
    fun encode(): SourceCursor = SourceCursor(
        listOf(VERSION, updatedAt, updatedId, deletedAt, deletedId, observationRevision).joinToString(":")
    )

    companion object {
        private const val VERSION = 1L

        fun decode(cursor: SourceCursor): ContactCursor {
            val parts = cursor.value.split(':').map(String::toLong)
            require(parts.size == 6 && parts[0] == VERSION) { "Unsupported contact LiveSource cursor" }
            return ContactCursor(parts[1], parts[2], parts[3], parts[4], parts[5])
        }
    }
}

private enum class AndroidLiveMediaKind(
    val sourceId: LiveSourceId,
    val uri: Uri,
) {
    IMAGE(AndroidLiveSourceIds.MEDIA_IMAGES, MediaStore.Images.Media.EXTERNAL_CONTENT_URI),
    VIDEO(AndroidLiveSourceIds.MEDIA_VIDEO, MediaStore.Video.Media.EXTERNAL_CONTENT_URI),
    AUDIO(AndroidLiveSourceIds.MEDIA_AUDIO, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI),
}

private class AndroidMediaLiveSourceAdapter(
    private val context: Context,
    private val kind: AndroidLiveMediaKind,
) : LiveSourceAdapter {
    override val sourceId: LiveSourceId = kind.sourceId

    override suspend fun inventory(): SourceInventory {
        val generationColumn = changeColumn()
        val latest = latestPosition(
            resolver = context.contentResolver,
            uri = kind.uri,
            timestampColumn = generationColumn,
            idColumn = MediaStore.MediaColumns._ID,
        )
        return SourceInventory(
            items = emptyList(),
            cursor = MediaCursor(latest.first, latest.second, 0L).encode(),
        )
    }

    override suspend fun changesAfter(cursor: SourceCursor): SourceChangeSet {
        val state = MediaCursor.decode(cursor)
        val changeColumn = changeColumn()
        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.MIME_TYPE)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.DATE_MODIFIED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add(MediaStore.MediaColumns.GENERATION_MODIFIED)
            }
        }.toTypedArray()
        val selection =
            "($changeColumn > ?) OR ($changeColumn = ? AND ${MediaStore.MediaColumns._ID} > ?)"
        val args = arrayOf(
            state.changeToken.toString(),
            state.changeToken.toString(),
            state.providerId.toString(),
        )

        var revision = state.observationRevision
        var lastToken = state.changeToken
        var lastId = state.providerId
        val deltas = buildList {
            querySorted(
                resolver = context.contentResolver,
                uri = kind.uri,
                projection = projection,
                selection = selection,
                selectionArgs = args,
                sortColumns = arrayOf(changeColumn, MediaStore.MediaColumns._ID),
            )?.use { cursorRows ->
                while (cursorRows.moveToNext()) {
                    val id = cursorRows.longValue(MediaStore.MediaColumns._ID)
                    val name = cursorRows.stringValue(MediaStore.MediaColumns.DISPLAY_NAME).orEmpty()
                    val mime = cursorRows.stringValue(MediaStore.MediaColumns.MIME_TYPE).orEmpty()
                    val size = cursorRows.longValueOrZero(MediaStore.MediaColumns.SIZE)
                    val modifiedSeconds = cursorRows.longValueOrZero(MediaStore.MediaColumns.DATE_MODIFIED)
                    val token = cursorRows.longValue(changeColumn)
                    val itemUri = Uri.withAppendedPath(kind.uri, id.toString()).toString()
                    val fingerprint = StableCognitiveIds.fingerprint(
                        "android-media-live-state/v1",
                        kind.name,
                        id.toString(),
                        name,
                        mime,
                        size.toString(),
                        modifiedSeconds.toString(),
                        token.toString(),
                        itemUri,
                    )
                    revision += 1L
                    add(
                        SourceDelta(
                            deltaId = StableCognitiveIds.fingerprint(
                                "android-media-live-delta/v1",
                                kind.name,
                                id.toString(),
                                revision.toString(),
                                fingerprint,
                            ),
                            sourceId = sourceId,
                            externalKey = "media-$id",
                            kind = SourceDeltaKind.UPDATED,
                            previousFingerprint = null,
                            newFingerprint = fingerprint,
                            observationRevision = revision,
                            privacyZone = SourcePrivacyZone.PRIVATE,
                            payload = buildString {
                                appendLine("uri=$itemUri")
                                appendLine("name=$name")
                                appendLine("mime_type=$mime")
                                appendLine("size_bytes=$size")
                                appendLine("date_modified_s=$modifiedSeconds")
                                append("change_token=$token")
                            },
                            mimeType = "application/vnd.lifeos.android-media-live+text",
                            observedAtEpochMillis = modifiedSeconds.coerceAtLeast(0L) * 1_000L,
                        )
                    )
                    lastToken = token
                    lastId = id
                }
            }
        }
        return SourceChangeSet(
            deltas = deltas,
            nextCursor = MediaCursor(lastToken, lastId, revision).encode(),
        )
    }

    private fun changeColumn(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            MediaStore.MediaColumns.GENERATION_MODIFIED
        } else {
            MediaStore.MediaColumns.DATE_MODIFIED
        }
}

private data class MediaCursor(
    val changeToken: Long,
    val providerId: Long,
    val observationRevision: Long,
) {
    fun encode(): SourceCursor = SourceCursor(
        listOf(VERSION, changeToken, providerId, observationRevision).joinToString(":")
    )

    companion object {
        private const val VERSION = 1L

        fun decode(cursor: SourceCursor): MediaCursor {
            val parts = cursor.value.split(':').map(String::toLong)
            require(parts.size == 4 && parts[0] == VERSION) { "Unsupported Media LiveSource cursor" }
            return MediaCursor(parts[1], parts[2], parts[3])
        }
    }
}

private fun latestPosition(
    resolver: ContentResolver,
    uri: Uri,
    timestampColumn: String,
    idColumn: String,
): Pair<Long, Long> {
    val projection = arrayOf(timestampColumn, idColumn)
    val args = Bundle().apply {
        putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(timestampColumn, idColumn))
        putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
        putInt(ContentResolver.QUERY_ARG_LIMIT, 1)
    }
    return resolver.query(uri, projection, args, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            cursor.longValue(timestampColumn) to cursor.longValue(idColumn)
        } else {
            0L to 0L
        }
    } ?: (0L to 0L)
}

private fun querySorted(
    resolver: ContentResolver,
    uri: Uri,
    projection: Array<String>,
    selection: String,
    selectionArgs: Array<String>,
    sortColumns: Array<String>,
    limit: Int = MAX_CHANGE_ROWS,
): Cursor? {
    val args = Bundle().apply {
        putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
        putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs)
        putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, sortColumns)
        putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_ASCENDING)
        putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
    }
    return resolver.query(uri, projection, args, null)
}

private fun mediaPermission(kind: AndroidLiveMediaKind): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        when (kind) {
            AndroidLiveMediaKind.IMAGE -> Manifest.permission.READ_MEDIA_IMAGES
            AndroidLiveMediaKind.VIDEO -> Manifest.permission.READ_MEDIA_VIDEO
            AndroidLiveMediaKind.AUDIO -> Manifest.permission.READ_MEDIA_AUDIO
        }
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

private fun Cursor.stringValue(column: String): String? =
    getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getString)

private fun Cursor.longValue(column: String): Long = getLong(getColumnIndexOrThrow(column))

private fun Cursor.longValueOrZero(column: String): Long =
    getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getLong) ?: 0L

private const val MAX_CHANGE_ROWS = 2_000
