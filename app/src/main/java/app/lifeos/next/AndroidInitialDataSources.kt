package app.lifeos.next

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.BaseColumns
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import app.lifeos.core.model.StableCognitiveIds
import app.lifeos.core.runtime.life.InitialDataSourceAdapter
import app.lifeos.core.runtime.life.InitialDataSourcePage
import app.lifeos.core.runtime.life.InitialDataSourceStatus
import app.lifeos.core.runtime.life.LifeSourceDescriptor
import app.lifeos.core.runtime.life.LifeSourceRecord
import java.time.Instant

/** Device-owned source catalog for the automatic first-read. No restricted SMS/call-log access. */
internal class AndroidInitialDataSourceCatalog(
    private val context: Context,
) {
    val sources: List<InitialDataSourceAdapter> = listOf(
        AndroidContactsInitialDataSource(context),
        AndroidCalendarInitialDataSource(context),
        AndroidMediaInitialDataSource(context, AndroidMediaKind.IMAGE),
        AndroidMediaInitialDataSource(context, AndroidMediaKind.VIDEO),
        AndroidMediaInitialDataSource(context, AndroidMediaKind.AUDIO),
    )

    fun requiredRuntimePermissions(): List<String> = buildList {
        add(Manifest.permission.READ_CONTACTS)
        add(Manifest.permission.READ_CALENDAR)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.READ_MEDIA_IMAGES)
            add(Manifest.permission.READ_MEDIA_VIDEO)
            add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }.distinct().sorted()

    fun missingRuntimePermissions(): List<String> = requiredRuntimePermissions().filter { permission ->
        context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED
    }

    fun permissionSchemaFingerprint(): String = StableCognitiveIds.fingerprint(
        "android-initial-data-permissions/v1",
        *requiredRuntimePermissions().toTypedArray(),
    )
}

private abstract class AndroidPagedInitialDataSource(
    protected val context: Context,
    final override val descriptor: LifeSourceDescriptor,
    private val requiredPermission: String?,
    private val providerAuthority: String,
) : InitialDataSourceAdapter {
    protected val resolver: ContentResolver
        get() = context.contentResolver

    final override suspend fun status(): InitialDataSourceStatus = when {
        context.packageManager.resolveContentProvider(providerAuthority, 0) == null ->
            InitialDataSourceStatus.UNAVAILABLE
        requiredPermission == null -> InitialDataSourceStatus.UNAVAILABLE
        context.checkSelfPermission(requiredPermission) != PackageManager.PERMISSION_GRANTED ->
            InitialDataSourceStatus.UNAUTHORIZED
        else -> InitialDataSourceStatus.AVAILABLE
    }

    protected fun queryByIdPage(
        uri: Uri,
        projection: Array<String>,
        afterPosition: String?,
        limit: Int,
    ): Cursor? {
        val args = Bundle().apply {
            if (afterPosition != null) {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${BaseColumns._ID} > ?")
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(afterPosition))
            }
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(BaseColumns._ID))
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_ASCENDING)
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
        }
        return resolver.query(uri, projection, args, null)
    }

    protected fun nextPosition(rows: List<Pair<Long, LifeSourceRecord>>, limit: Int): String? =
        if (rows.size < limit) null else rows.lastOrNull()?.first?.toString()

    protected fun clean(value: String?): String = value.orEmpty()
        .replace('\n', ' ')
        .replace('\r', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()
}

private class AndroidContactsInitialDataSource(
    context: Context,
) : AndroidPagedInitialDataSource(
    context = context,
    descriptor = LifeSourceDescriptor(SOURCE_ID, ADAPTER_VERSION),
    requiredPermission = Manifest.permission.READ_CONTACTS,
    providerAuthority = ContactsContract.AUTHORITY,
) {
    override suspend fun readPage(afterPosition: String?, limit: Int): InitialDataSourcePage {
        check(status() == InitialDataSourceStatus.AVAILABLE) { "Contacts source is not currently authorized" }
        val projection = arrayOf(
            ContactsContract.Data._ID,
            ContactsContract.Data.CONTACT_ID,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
            ContactsContract.Data.MIMETYPE,
            ContactsContract.Data.DATA1,
            ContactsContract.Data.DATA2,
            ContactsContract.Data.DATA3,
            ContactsContract.Data.DATA4,
            ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP,
        )
        val rows = buildList {
            queryByIdPage(ContactsContract.Data.CONTENT_URI, projection, afterPosition, limit)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val rowId = cursor.long(ContactsContract.Data._ID)
                    val contactId = cursor.long(ContactsContract.Data.CONTACT_ID)
                    val name = clean(cursor.string(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY))
                    val mime = clean(cursor.string(ContactsContract.Data.MIMETYPE))
                    val data1 = clean(cursor.string(ContactsContract.Data.DATA1))
                    val data2 = clean(cursor.string(ContactsContract.Data.DATA2))
                    val data3 = clean(cursor.string(ContactsContract.Data.DATA3))
                    val data4 = clean(cursor.string(ContactsContract.Data.DATA4))
                    val updatedMillis = cursor.longOrNull(ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP) ?: 0L
                    val state = StableCognitiveIds.fingerprint(
                        "android-contact-data-row/v1",
                        contactId.toString(), name, mime, data1, data2, data3, data4,
                    )
                    val record = LifeSourceRecord(
                        sourceId = SOURCE_ID,
                        recordId = "data-$rowId-$state",
                        observedAt = instantFromMillis(updatedMillis),
                        payload = buildString {
                            appendLine("contact_id=$contactId")
                            appendLine("name=$name")
                            appendLine("mime_type=$mime")
                            appendLine("value=$data1")
                            appendLine("type=$data2")
                            appendLine("label=$data3")
                            append("aux=$data4")
                        },
                        mimeType = "application/vnd.lifeos.android-contact-data+text",
                        tags = buildSet {
                            add("contact")
                            if (name.isNotBlank()) add("person:$name")
                        },
                    )
                    add(rowId to record)
                }
            }
        }
        val next = nextPosition(rows, limit)
        return InitialDataSourcePage(rows.map { it.second }, next, complete = next == null)
    }

    private companion object {
        const val SOURCE_ID = "android-contacts"
        const val ADAPTER_VERSION = "android-contacts/v1"
    }
}

private class AndroidCalendarInitialDataSource(
    context: Context,
) : AndroidPagedInitialDataSource(
    context = context,
    descriptor = LifeSourceDescriptor(SOURCE_ID, ADAPTER_VERSION),
    requiredPermission = Manifest.permission.READ_CALENDAR,
    providerAuthority = CalendarContract.AUTHORITY,
) {
    override suspend fun readPage(afterPosition: String?, limit: Int): InitialDataSourcePage {
        check(status() == InitialDataSourceStatus.AVAILABLE) { "Calendar source is not currently authorized" }
        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.CALENDAR_ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.STATUS,
            CalendarContract.Events.EVENT_TIMEZONE,
        )
        val rows = buildList {
            queryByIdPage(CalendarContract.Events.CONTENT_URI, projection, afterPosition, limit)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val rowId = cursor.long(CalendarContract.Events._ID)
                    val calendarId = cursor.long(CalendarContract.Events.CALENDAR_ID)
                    val title = clean(cursor.string(CalendarContract.Contacts.DISPLAY_NAME_PRIMARY))
                    val description = clean(cursor.string(CalendarContract.Events.DESCRIPTION))
                    val location = clean(cursor.string(CalendarContract.Events.EVENT_LOCATION))
                    val start = cursor.longOrNull(CalendarContract.Events.DTSTART) ?: 0L
                    val end = cursor.longOrNull(CalendarContract.Events.DTEND) ?: 0L
                    val allDay = cursor.intOrNull(CalendarContract.Events.ALL_DAY) ?: 0
                    val status = cursor.intOrNull(CalendarContract.Events.STATUS) ?: 0
                    val zone = clean(cursor.string(CalendarContract.Events.EVENT_TIMEZONE))
                    val state = StableCognitiveIds.fingerprint(
                        "android-calendar-event/v1",
                        calendarId.toString(), title, description, location,
                        start.toString(), end.toString(), allDay.toString(), status.toString(), zone,
                    )
                    val record = LifeSourceRecord(
                        sourceId = SOURCE_ID,
                        recordId = "event-$rowId-$state",
                        observedAt = instantFromMillis(start),
                        payload = buildString {
                            appendLine("calendar_id=$calendarId")
                            appendLine("title=$title")
                            appendLine("description=$description")
                            appendLine("location=$location")
                            appendLine("start_ms=$start")
                            appendLine("end_ms=$end")
                            appendLine("all_day=$allDay")
                            appendLine("status=$status")
                            append("timezone=$zone")
                        },
                        mimeType = "application/vnd.lifeos.android-calendar-event+text",
                        tags = buildSet {
                            add("event")
                            add("calendar-event")
                            if (location.isNotBlank()) add("location:$location")
                        },
                    )
                    add(rowId to record)
                }
            }
        }
        val next = nextPosition(rows, limit)
        return InitialDataSourcePage(rows.map { it.second }, next, complete = next == null)
    }

    private companion object {
        const val SOURCE_ID = "android-calendar"
        const val ADAPTER_VERSION = "android-calendar/v1"
    }
}

private enum class AndroidMediaKind(
    val sourceId: String,
    val adapterVersion: String,
    val tag: String,
) {
    IMAGE("android-media-images", "android-media-images/v1", "image"),
    VIDEO("android-media-video", "android-media-video/v1", "video"),
    AUDIO("android-media-audio", "android-media-audio/v1", "audio"),
}

private class AndroidMediaInitialDataSource(
    context: Context,
    private val kind: AndroidMediaKind,
) : AndroidPagedInitialDataSource(
    context = context,
    descriptor = LifeSourceDescriptor(kind.sourceId, kind.adapterVersion),
    requiredPermission = mediaPermission(kind),
    providerAuthority = MediaStore.AUTHORITY,
) {
    override suspend fun readPage(afterPosition: String?, limit: Int): InitialDataSourcePage {
        check(status() == InitialDataSourceStatus.AVAILABLE) { "Media source is not currently authorized" }
        val uri = collectionUri(kind)
        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.MIME_TYPE)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.DATE_ADDED)
            add(MediaStore.MediaColumns.DATE_MODIFIED)
            add(MediaStore.MediaColumns.WIDTH)
            add(MediaStore.MediaColumns.HEIGHT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(MediaStore.MediaColumns.RELATIVE_PATH)
            if (kind != AndroidMediaKind.IMAGE) add(MEDIA_DURATION_COLUMN)
        }.toTypedArray()
        val rows = buildList {
            queryByIdPage(uri, projection, afterPosition, limit)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val rowId = cursor.long(MediaStore.MediaColumns._ID)
                    val name = clean(cursor.string(MediaStore.MediaColumns.DISPLAY_NAME))
                    val mime = clean(cursor.string(MediaStore.MediaColumns.MIME_TYPE))
                    val size = cursor.longOrNull(MediaStore.MediaColumns.SIZE) ?: 0L
                    val added = cursor.longOrNull(MediaStore.MediaColumns.DATE_ADDED) ?: 0L
                    val modified = cursor.longOrNull(MediaStore.MediaColumns.DATE_MODIFIED) ?: 0L
                    val width = cursor.intOrNull(MediaStore.MediaColumns.WIDTH) ?: 0
                    val height = cursor.intOrNull(MediaStore.MediaColumns.HEIGHT) ?: 0
                    val relativePath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        clean(cursor.string(MediaStore.MediaColumns.RELATIVE_PATH))
                    } else ""
                    val duration = if (kind != AndroidMediaKind.IMAGE) {
                        cursor.longOrNull(MEDIA_DURATION_COLUMN) ?: 0L
                    } else 0L
                    val itemUri = ContentUris.withAppendedId(uri, rowId).toString()
                    val state = StableCognitiveIds.fingerprint(
                        "android-media-row/v1",
                        kind.name, name, mime, size.toString(), added.toString(), modified.toString(),
                        width.toString(), height.toString(), relativePath, duration.toString(), itemUri,
                    )
                    val record = LifeSourceRecord(
                        sourceId = kind.sourceId,
                        recordId = "media-$rowId-$state",
                        observedAt = instantFromSeconds(if (modified > 0L) modified else added),
                        payload = buildString {
                            appendLine("uri=$itemUri")
                            appendLine("name=$name")
                            appendLine("mime_type=$mime")
                            appendLine("size_bytes=$size")
                            appendLine("date_added_s=$added")
                            appendLine("date_modified_s=$modified")
                            appendLine("width=$width")
                            appendLine("height=$height")
                            appendLine("relative_path=$relativePath")
                            append("duration_ms=$duration")
                        },
                        mimeType = "application/vnd.lifeos.android-media-metadata+text",
                        tags = buildSet {
                            add("media")
                            add("media:${kind.tag}")
                            add("document")
                            if (name.isNotBlank()) add("document:$name")
                        },
                    )
                    add(rowId to record)
                }
            }
        }
        val next = nextPosition(rows, limit)
        return InitialDataSourcePage(rows.map { it.second }, next, complete = next == null)
    }
}

private fun mediaPermission(kind: AndroidMediaKind): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        when (kind) {
            AndroidMediaKind.IMAGE -> Manifest.permission.READ_MEDIA_IMAGES
            AndroidMediaKind.VIDEO -> Manifest.permission.READ_MEDIA_VIDEO
            AndroidMediaKind.AUDIO -> Manifest.permission.READ_MEDIA_AUDIO
        }
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

private fun collectionUri(kind: AndroidMediaKind): Uri = when (kind) {
    AndroidMediaKind.IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    AndroidMediaKind.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    AndroidMediaKind.AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
}

private fun Cursor.string(column: String): String? =
    getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getString)

private fun Cursor.long(column: String): Long = getLong(getColumnIndexOrThrow(column))

private fun Cursor.longOrNull(column: String): Long? =
    getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getLong)

private fun Cursor.intOrNull(column: String): Int? =
    getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getInt)

private fun instantFromMillis(value: Long): Instant =
    if (value > 0L) Instant.ofEpochMilli(value) else Instant.EPOCH

private fun instantFromSeconds(value: Long): Instant =
    if (value > 0L) Instant.ofEpochSecond(value) else Instant.EPOCH

private const val MEDIA_DURATION_COLUMN = "duration"
