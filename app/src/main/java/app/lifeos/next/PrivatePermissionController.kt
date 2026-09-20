package app.lifeos.next

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment

internal class PrivatePermissionController(
    private val application: Application,
    private val initialDataSources: () -> AndroidInitialDataSourceCatalog,
    private val startupReady: () -> Boolean,
) {
    fun initialDataPermissionsToRequest(): List<String> =
        initialDataSources().missingRuntimePermissions()

    fun allRuntimePermissionsToRequest(): List<String> {
        if (!startupReady()) return emptyList()
        return buildList {
            addAll(initialDataSources().missingRuntimePermissions())
            if (
                application.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.RECORD_AUDIO)
            }
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                application.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU &&
                application.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }.distinct().sorted()
    }

    fun hasBroadFileAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
            Environment.isExternalStorageManager()

    fun shouldRequestBroadFileAccess(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || hasBroadFileAccess()) {
            return false
        }
        return !preferences().getBoolean(BROAD_FILE_ACCESS_REQUESTED, false)
    }

    fun markBroadFileAccessRequested() {
        preferences()
            .edit()
            .putBoolean(BROAD_FILE_ACCESS_REQUESTED, true)
            .apply()
    }

    fun shouldRequestInitialDataPermissions(): Boolean {
        val sources = initialDataSources()
        if (sources.missingRuntimePermissions().isEmpty()) return false
        val schema = sources.permissionSchemaFingerprint()
        return preferences().getString(INITIAL_DATA_PERMISSION_SCHEMA, null) != schema
    }

    fun shouldRequestAllRuntimePermissions(): Boolean {
        if (allRuntimePermissionsToRequest().isEmpty()) return false
        return preferences().getString(ALL_RUNTIME_PERMISSION_SCHEMA, null) !=
            allRuntimePermissionSchema()
    }

    fun markInitialDataPermissionsRequested() {
        preferences()
            .edit()
            .putString(
                INITIAL_DATA_PERMISSION_SCHEMA,
                initialDataSources().permissionSchemaFingerprint(),
            )
            .apply()
    }

    fun markAllRuntimePermissionsRequested() {
        preferences()
            .edit()
            .putString(
                ALL_RUNTIME_PERMISSION_SCHEMA,
                allRuntimePermissionSchema(),
            )
            .apply()
    }

    private fun allRuntimePermissionSchema(): String = buildString {
        append(initialDataSources().permissionSchemaFingerprint())
        append("|record-audio")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            append("|post-notifications")
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            append("|read-external-storage")
        }
    }

    private fun preferences() =
        application.getSharedPreferences(
            INITIAL_DATA_PREFS,
            Context.MODE_PRIVATE,
        )

    private companion object {
        const val INITIAL_DATA_PREFS = "lifeos-initial-data-bootstrap"
        const val INITIAL_DATA_PERMISSION_SCHEMA = "permission-schema"
        const val ALL_RUNTIME_PERMISSION_SCHEMA = "all-runtime-permission-schema"
        const val BROAD_FILE_ACCESS_REQUESTED = "broad-file-access-requested"
    }
}
