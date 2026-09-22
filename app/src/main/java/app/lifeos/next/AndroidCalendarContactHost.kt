package app.lifeos.next

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.os.Bundle
import android.provider.BaseColumns
import android.provider.CalendarContract
import android.provider.ContactsContract
import app.lifeos.core.runtime.android.CalendarContactHost
import app.lifeos.core.runtime.android.CalendarCreateRequest
import app.lifeos.core.runtime.android.CalendarEventRef
import app.lifeos.core.runtime.android.CalendarEventRevision
import app.lifeos.core.runtime.android.CalendarEventState
import app.lifeos.core.runtime.android.CalendarQuery
import app.lifeos.core.runtime.android.CalendarUpdateRequest
import app.lifeos.core.runtime.android.ContactCreateRequest
import app.lifeos.core.runtime.android.ContactRef
import app.lifeos.core.runtime.android.ContactResolveQuery
import app.lifeos.core.runtime.android.ContactRevision
import app.lifeos.core.runtime.android.ContactState
import app.lifeos.core.runtime.android.ContactUpdateRequest
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class AndroidCalendarContactHost(
    private val context: Context,
    private val onCalendarMutation: () -> Unit,
    private val onContactMutation: () -> Unit,
) : CalendarContactHost {
    private val resolver: ContentResolver
        get() = context.contentResolver

    override suspend fun readCalendar(
        query: CalendarQuery,
    ): List<CalendarEventRevision> = withContext(Dispatchers.IO) {
        requireProvider(CalendarContract.AUTHORITY)
        requirePermission(Manifest.permission.READ_CALENDAR)
        queryCalendar(query)
    }

    override suspend fun createCalendarEvent(
        request: CalendarCreateRequest,
    ): CalendarEventRevision = withContext(Dispatchers.IO) {
        requireProvider(CalendarContract.AUTHORITY)
        requirePermission(Manifest.permission.READ_CALENDAR)
        requirePermission(Manifest.permission.WRITE_CALENDAR)
        val inserted = requireNotNull(
            resolver.insert(CalendarContract.Events.CONTENT_URI, calendarValues(request.state))
        ) { "calendar-event-insert-failed" }
        val eventId = ContentUris.parseId(inserted)
        require(eventId > 0L) { "calendar-event-insert-returned-invalid-id" }
        val ref = CalendarEventRef(request.state.calendarId, eventId)
        val revision = requireNotNull(readCalendarExact(ref)) {
            "calendar-event-insert-verification-failed"
        }
        onCalendarMutation()
        revision
    }

    override suspend fun updateCalendarEvent(
        request: CalendarUpdateRequest,
    ): CalendarEventRevision = withContext(Dispatchers.IO) {
        requireProvider(CalendarContract.AUTHORITY)
        requirePermission(Manifest.permission.READ_CALENDAR)
        requirePermission(Manifest.permission.WRITE_CALENDAR)
        val current = requireNotNull(readCalendarExact(request.ref)) {
            "calendar-event-not-found"
        }
        require(current == request.expectedRevision) { "calendar-event-revision-mismatch" }

        val uri = ContentUris.withAppendedId(
            CalendarContract.Events.CONTENT_URI,
            request.ref.eventId,
        )
        val count = resolver.update(uri, calendarValues(request.nextState), null, null)
        require(count == 1) { "calendar-event-update-count=" + count }
        val updated = requireNotNull(readCalendarExact(request.ref)) {
            "calendar-event-update-verification-failed"
        }
        require(updated.ref == request.ref)
        onCalendarMutation()
        updated
    }

    override suspend fun resolveContacts(
        query: ContactResolveQuery,
    ): List<ContactRevision> = withContext(Dispatchers.IO) {
        requireProvider(ContactsContract.AUTHORITY)
        requirePermission(Manifest.permission.READ_CONTACTS)
        queryContacts(query)
    }

    override suspend fun createContact(
        request: ContactCreateRequest,
    ): ContactRevision = withContext(Dispatchers.IO) {
        requireProvider(ContactsContract.AUTHORITY)
        requirePermission(Manifest.permission.READ_CONTACTS)
        requirePermission(Manifest.permission.WRITE_CONTACTS)

        val operations = arrayListOf<ContentProviderOperation>()
        operations += ContentProviderOperation
            .newInsert(ContactsContract.RawContacts.CONTENT_URI)
            .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
            .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
            .build()
        operations += ContentProviderOperation
            .newInsert(ContactsContract.Data.CONTENT_URI)
            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
            .withValue(
                ContactsContract.Data.MIMETYPE,
                ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
            )
            .withValue(
                ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME,
                request.state.displayName,
            )
            .build()
        request.state.phone?.let { phone ->
            operations += ContentProviderOperation
                .newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(
                    ContactsContract.Data.MIMETYPE,
                    ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
                )
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone)
                .build()
        }
        request.state.email?.let { email ->
            operations += ContentProviderOperation
                .newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(
                    ContactsContract.Data.MIMETYPE,
                    ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
                )
                .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, email)
                .build()
        }

        val results = resolver.applyBatch(ContactsContract.AUTHORITY, operations)
        val rawId = results.firstOrNull()?.uri?.let(ContentUris::parseId)
            ?: error("contact-create-missing-raw-id")
        require(rawId > 0L)
        val ref = requireNotNull(contactRefForRawId(rawId)) {
            "contact-create-missing-contact-id"
        }
        val revision = requireNotNull(readContactExact(ref)) {
            "contact-create-verification-failed"
        }
        onContactMutation()
        revision
    }

    override suspend fun updateContact(
        request: ContactUpdateRequest,
    ): ContactRevision = withContext(Dispatchers.IO) {
        requireProvider(ContactsContract.AUTHORITY)
        requirePermission(Manifest.permission.READ_CONTACTS)
        requirePermission(Manifest.permission.WRITE_CONTACTS)

        val current = requireNotNull(readContactExact(request.ref)) { "contact-not-found" }
        require(current == request.expectedRevision) { "contact-revision-mismatch" }

        val operations = arrayListOf<ContentProviderOperation>()
        operations += dataUpsertOperation(
            request.ref.rawContactId,
            ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME,
            request.nextState.displayName,
        )
        operations += dataUpsertOperation(
            request.ref.rawContactId,
            ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            request.nextState.phone.orEmpty(),
        )
        operations += dataUpsertOperation(
            request.ref.rawContactId,
            ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Email.ADDRESS,
            request.nextState.email.orEmpty(),
        )
        resolver.applyBatch(ContactsContract.AUTHORITY, operations)

        val updated = requireNotNull(readContactExact(request.ref)) {
            "contact-update-verification-failed"
        }
        require(updated.ref == request.ref)
        onContactMutation()
        updated
    }

    private fun queryCalendar(query: CalendarQuery): List<CalendarEventRevision> {
        val conditions = mutableListOf<String>()
        val args = mutableListOf<String>()
        query.fromMillis?.let {
            conditions += CalendarContract.Events.DTEND + " >= ?"
            args += it.toString()
        }
        query.untilMillis?.let {
            conditions += CalendarContract.Events.DTSTART + " <= ?"
            args += it.toString()
        }
        val bundle = Bundle().apply {
            if (conditions.isNotEmpty()) {
                putString(
                    ContentResolver.QUERY_ARG_SQL_SELECTION,
                    conditions.joinToString(" AND "),
                )
                putStringArray(
                    ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                    args.toTypedArray(),
                )
            }
            putStringArray(
                ContentResolver.QUERY_ARG_SORT_COLUMNS,
                arrayOf(CalendarContract.Events.DTSTART, BaseColumns._ID),
            )
            putInt(
                ContentResolver.QUERY_ARG_SORT_DIRECTION,
                ContentResolver.QUERY_SORT_DIRECTION_ASCENDING,
            )
            putInt(ContentResolver.QUERY_ARG_LIMIT, query.maxResults)
        }
        val out = mutableListOf<CalendarEventRevision>()
        resolver.query(
            CalendarContract.Events.CONTENT_URI,
            CALENDAR_PROJECTION,
            bundle,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext() && out.size < query.maxResults) {
                cursor.calendarRevisionOrNull()?.let(out::add)
            }
        }
        return out
    }

    private fun readCalendarExact(ref: CalendarEventRef): CalendarEventRevision? {
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, ref.eventId)
        return resolver.query(uri, CALENDAR_PROJECTION, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) null
            else cursor.calendarRevisionOrNull()?.takeIf { it.ref == ref }
        }
    }

    private fun Cursor.calendarRevisionOrNull(): CalendarEventRevision? {
        val eventId = longOrNull(CalendarContract.Events._ID) ?: return null
        val calendarId = longOrNull(CalendarContract.Events.CALENDAR_ID) ?: return null
        val start = longOrNull(CalendarContract.Events.DTSTART) ?: return null
        val end = longOrNull(CalendarContract.Events.DTEND) ?: start
        val zone = stringOrNull(CalendarContract.Events.EVENT_TIMEZONE)
            .orEmpty()
            .ifBlank { TimeZone.getDefault().id }
        val normalizedStart = start.coerceAtLeast(0L)
        val state = CalendarEventState(
            calendarId = calendarId,
            title = clean(stringOrNull(CalendarContract.Events.TITLE)),
            description = clean(stringOrNull(CalendarContract.Events.DESCRIPTION)),
            location = clean(stringOrNull(CalendarContract.Events.EVENT_LOCATION)),
            startMillis = normalizedStart,
            endMillis = end.coerceAtLeast(normalizedStart),
            allDay = (intOrNull(CalendarContract.Events.ALL_DAY) ?: 0) != 0,
            timeZone = zone,
        )
        return CalendarEventRevision(
            ref = CalendarEventRef(calendarId, eventId),
            state = state,
        )
    }

    private fun calendarValues(state: CalendarEventState): ContentValues =
        ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, state.calendarId)
            put(CalendarContract.Events.TITLE, state.title)
            put(CalendarContract.Events.DESCRIPTION, state.description)
            put(CalendarContract.Events.EVENT_LOCATION, state.location)
            put(CalendarContract.Events.DTSTART, state.startMillis)
            put(CalendarContract.Events.DTEND, state.endMillis)
            put(CalendarContract.Events.ALL_DAY, if (state.allDay) 1 else 0)
            put(CalendarContract.Events.EVENT_TIMEZONE, state.timeZone)
        }

    private fun queryContacts(query: ContactResolveQuery): List<ContactRevision> {
        val needle = "%" + escapeLike(query.normalized) + "%"
        val selection =
            "(" + ContactsContract.Contacts.DISPLAY_NAME_PRIMARY + " LIKE ? ESCAPE '\\' OR " +
                ContactsContract.Data.DATA1 + " LIKE ? ESCAPE '\\')"
        val bundle = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            putStringArray(
                ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                arrayOf(needle, needle),
            )
            putStringArray(
                ContentResolver.QUERY_ARG_SORT_COLUMNS,
                arrayOf(
                    ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                    ContactsContract.Data.CONTACT_ID,
                    ContactsContract.Data.RAW_CONTACT_ID,
                    ContactsContract.Data._ID,
                ),
            )
            putInt(
                ContentResolver.QUERY_ARG_SORT_DIRECTION,
                ContentResolver.QUERY_SORT_DIRECTION_ASCENDING,
            )
            putInt(
                ContentResolver.QUERY_ARG_LIMIT,
                minOf(CONTACT_ROW_QUERY_LIMIT, query.maxResults * CONTACT_ROW_MULTIPLIER),
            )
        }

        val rows = mutableListOf<ContactDataRow>()
        resolver.query(
            ContactsContract.Data.CONTENT_URI,
            CONTACT_PROJECTION,
            bundle,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) cursor.contactDataRowOrNull()?.let(rows::add)
        }
        return aggregateContacts(rows).take(query.maxResults)
    }

    private fun readContactExact(ref: ContactRef): ContactRevision? {
        val rows = mutableListOf<ContactDataRow>()
        resolver.query(
            ContactsContract.Data.CONTENT_URI,
            CONTACT_PROJECTION,
            ContactsContract.Data.RAW_CONTACT_ID + "=?",
            arrayOf(ref.rawContactId.toString()),
            ContactsContract.Data._ID + " ASC",
        )?.use { cursor ->
            while (cursor.moveToNext()) cursor.contactDataRowOrNull()?.let(rows::add)
        }
        return aggregateContacts(rows).singleOrNull { it.ref == ref }
    }

    private fun aggregateContacts(rows: List<ContactDataRow>): List<ContactRevision> =
        rows.groupBy { ContactRef(it.contactId, it.rawContactId) }
            .map { (ref, contactRows) ->
                val name = contactRows.asSequence()
                    .map { it.displayName }
                    .firstOrNull { it.isNotBlank() }
                    .orEmpty()
                val phone = contactRows.firstOrNull {
                    it.mimeType == ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE &&
                        it.value.isNotBlank()
                }?.value
                val email = contactRows.firstOrNull {
                    it.mimeType == ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE &&
                        it.value.isNotBlank()
                }?.value
                ContactRevision(
                    ref = ref,
                    state = ContactState(name, phone, email),
                    updatedAtMillis = contactRows.maxOfOrNull { it.updatedAtMillis } ?: 0L,
                )
            }
            .sortedWith(
                compareBy<ContactRevision> { it.state.displayName.lowercase() }
                    .thenBy { it.ref.contactId }
                    .thenBy { it.ref.rawContactId }
            )

    private fun Cursor.contactDataRowOrNull(): ContactDataRow? {
        val contactId = longOrNull(ContactsContract.Data.CONTACT_ID) ?: return null
        val rawContactId = longOrNull(ContactsContract.Data.RAW_CONTACT_ID) ?: return null
        return ContactDataRow(
            contactId = contactId,
            rawContactId = rawContactId,
            displayName = clean(stringOrNull(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)),
            mimeType = stringOrNull(ContactsContract.Data.MIMETYPE).orEmpty(),
            value = clean(stringOrNull(ContactsContract.Data.DATA1)),
            updatedAtMillis =
                longOrNull(ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP)?.coerceAtLeast(0L)
                    ?: 0L,
        )
    }

    private fun contactRefForRawId(rawContactId: Long): ContactRef? =
        resolver.query(
            ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, rawContactId),
            arrayOf(
                ContactsContract.RawContacts._ID,
                ContactsContract.RawContacts.CONTACT_ID,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) null
            else cursor.longOrNull(ContactsContract.RawContacts.CONTACT_ID)?.let {
                ContactRef(it, rawContactId)
            }
        }

    private fun dataUpsertOperation(
        rawContactId: Long,
        mimeType: String,
        valueColumn: String,
        value: String,
    ): ContentProviderOperation {
        val rowId = dataRowId(rawContactId, mimeType)
        return if (rowId == null) {
            ContentProviderOperation
                .newInsert(ContactsContract.Data.CONTENT_URI)
                .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
                .withValue(ContactsContract.Data.MIMETYPE, mimeType)
                .withValue(valueColumn, value)
                .build()
        } else {
            ContentProviderOperation
                .newUpdate(ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, rowId))
                .withValue(valueColumn, value)
                .build()
        }
    }

    private fun dataRowId(rawContactId: Long, mimeType: String): Long? =
        resolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data._ID),
            ContactsContract.Data.RAW_CONTACT_ID + "=? AND " +
                ContactsContract.Data.MIMETYPE + "=?",
            arrayOf(rawContactId.toString(), mimeType),
            ContactsContract.Data._ID + " ASC",
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else null
        }

    private fun requirePermission(permission: String) {
        require(context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            "android-permission-missing:" + permission
        }
    }

    private fun requireProvider(authority: String) {
        require(context.packageManager.resolveContentProvider(authority, 0) != null) {
            "android-provider-unavailable:" + authority
        }
    }

    private fun clean(value: String?): String =
        value.orEmpty()
            .replace('\n', ' ')
            .replace('\r', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun escapeLike(value: String): String =
        value.replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")

    private data class ContactDataRow(
        val contactId: Long,
        val rawContactId: Long,
        val displayName: String,
        val mimeType: String,
        val value: String,
        val updatedAtMillis: Long,
    )

    private companion object {
        val CALENDAR_PROJECTION = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.CALENDAR_ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.EVENT_TIMEZONE,
        )
        val CONTACT_PROJECTION = arrayOf(
            ContactsContract.Data._ID,
            ContactsContract.Data.CONTACT_ID,
            ContactsContract.Data.RAW_CONTACT_ID,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
            ContactsContract.Data.MIMETYPE,
            ContactsContract.Data.DATA1,
            ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP,
        )
        const val CONTACT_ROW_MULTIPLIER = 8
        const val CONTACT_ROW_QUERY_LIMIT = 1024
    }
}

private fun Cursor.stringOrNull(column: String): String? =
    getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getString)

private fun Cursor.longOrNull(column: String): Long? =
    getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getLong)

private fun Cursor.intOrNull(column: String): Int? =
    getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getInt)
